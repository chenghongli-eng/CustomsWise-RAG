package com.customswise.rag.service;

import com.customswise.rag.entity.DocumentChunk;
import com.customswise.rag.entity.DocumentIngestJob;
import com.customswise.rag.entity.PolicyDocument;
import com.customswise.rag.pdf.ExtractionResult;
import com.customswise.rag.pdf.SemanticChunker;
import com.customswise.rag.pdf.TextNormalizer;
import com.customswise.rag.repository.DocumentChunkRepository;
import com.customswise.rag.repository.DocumentIngestJobRepository;
import com.customswise.rag.repository.PolicyDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异步摄取流水线：在 {@code ingestExecutor} 线程池内执行 PDF 解析 + 向量化 + 入库。
 *
 * <p>核心契约：
 * <ul>
 *   <li>同步阶段（{@link #createJobFor(Long)}）只建库，立即返回 JobAck——HTTP 请求 P99 &lt; 500ms</li>
 *   <li>异步执行（{@link #processAsync(String)}）跑真正的重活：
 *       PENDING → RUNNING → SUCCESS / FAILED</li>
 *   <li>失败重试：attempts++，按 1min / 2min / 4min / 8min 指数退避；
 *       达到 maxAttempts 置 FAILED（终态）</li>
 *   <li>跨进程恢复：{@link IngestionScheduler} 每 30 秒扫描 PENDING 且 nextAttemptAt&lt;=now 的 job 重新提交</li>
 * </ul>
 *
 * <p>结构化日志贯穿各阶段（EXTRACT / CHUNKER / MILVUS / JOB），便于排障。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    /** PolicyDocument 仓储。 */
    private final PolicyDocumentRepository documentRepository;
    /** DocumentChunk 仓储。 */
    private final DocumentChunkRepository chunkRepository;
    /** DocumentIngestJob 仓储（job 状态/重试元数据）。 */
    private final DocumentIngestJobRepository jobRepository;
    /** PDF 文本提取（PDFBox → OCR）。 */
    private final TextExtractorService textExtractorService;
    /** MiniMax Embedding（生成 1536 维向量）。 */
    private final MiniMaxService miniMaxService;
    /** Milvus v1 主服务（insertVector / search）。 */
    private final MilvusService milvusService;

    /**
     * 同步阶段：仅为已落盘的 documentId 建一条 PENDING job。
     *
     * <p>调用方（DocumentController）在 stageUpload 落盘 PolicyDocument 后立即调用，
     * 然后 ingestionService.processAsync() 异步跑流水线。
     *
     * @param documentId PolicyDocument 主键
     * @return 新建的 DocumentIngestJob（含 UUID 与 PENDING 状态）
     */
    public DocumentIngestJob createJobFor(Long documentId) {
        DocumentIngestJob job = new DocumentIngestJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setDocumentId(documentId);
        job.setStatus(DocumentIngestJob.Status.PENDING);
        job.setMaxAttempts(3);
        job.setAttempts(0);
        job.setNextAttemptAt(LocalDateTime.now());
        job.setCreatedAt(LocalDateTime.now());
        return jobRepository.save(job);
    }

    /**
     * 异步执行：解析 + 切片 + 向量化 + 入库。失败按指数退避重试，达到 maxAttempts 置 FAILED。
     *
     * <p>在 {@code ingestExecutor} 线程池执行，调用方（controller / scheduler）立即返回。
     * job 状态机：PENDING → RUNNING → SUCCESS / FAILED / PENDING（重试）。
     *
     * @param jobId DocumentIngestJob.jobId
     */
    @org.springframework.scheduling.annotation.Async("ingestExecutor")
    public void processAsync(String jobId) {
        DocumentIngestJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("[JOB] jobId={} not found, skip", jobId);
            return;
        }

        long t0 = System.currentTimeMillis();
        job.setStatus(DocumentIngestJob.Status.RUNNING);
        jobRepository.save(job);

        PolicyDocument doc = documentRepository.findById(job.getDocumentId()).orElse(null);
        if (doc == null) {
            markFailed(job, "document not found");
            return;
        }
        if (Boolean.TRUE.equals(doc.getDeleted())) {
            markFailed(job, "document deleted");
            return;
        }

        try {
            File file = new File(doc.getFilePath());
            if (!file.exists()) {
                markFailed(job, "file missing: " + doc.getFilePath());
                return;
            }

            ExtractionResult er = textExtractorService.extractFromPath(Path.of(doc.getFilePath()));
            log.info("EXTRACT jobId={} docId={} extractor={} usedOcr={} pages={} chars={} elapsedMs={} error={}",
                    jobId, doc.getId(), er.extractor(), er.usedOcr(), er.pages(),
                    er.text().length(), er.elapsedMs(), er.error());

            if (er.isEmpty()) {
                markFailed(job, "extraction empty: " + er.error());
                return;
            }

            String normalized = TextNormalizer.normalize(er.text());
            List<SemanticChunker.Chunk> chunks = new SemanticChunker().split(normalized);
            log.info("CHUNKER jobId={} docId={} chunks={}", jobId, doc.getId(), chunks.size());

            int inserted = 0;
            for (SemanticChunker.Chunk c : chunks) {
                try {
                    float[] vector = miniMaxService.embed(c.text());
                    Map<String, String> meta = new HashMap<>();
                    meta.put("document_id", String.valueOf(doc.getId()));
                    meta.put("chunk_index", String.valueOf(c.chunkIndex()));
                    meta.put("anchor", c.anchor());
                    meta.put("status", doc.getStatus());
                    milvusService.insertVector(c.text(), vector, meta);

                    DocumentChunk dc = new DocumentChunk();
                    dc.setDocumentId(doc.getId());
                    dc.setChunkIndex(c.chunkIndex());
                    dc.setContent(c.text());
                    dc.setVectorId("async-" + doc.getId() + "-" + c.chunkIndex());
                    dc.setCreatedAt(LocalDateTime.now());
                    chunkRepository.save(dc);
                    inserted++;
                } catch (Exception e) {
                    log.error("EMBED/MILVUS jobId={} docId={} chunk {} failed: {}",
                            jobId, doc.getId(), c.chunkIndex(), e.getMessage());
                }
            }
            log.info("MILVUS jobId={} docId={} requested={} inserted={}", jobId, doc.getId(), chunks.size(), inserted);

            job.setStatus(DocumentIngestJob.Status.SUCCESS);
            job.setFinishedAt(LocalDateTime.now());
            jobRepository.save(job);
            log.info("JOB jobId={} docId={} status=SUCCESS elapsedMs={}",
                    jobId, doc.getId(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("JOB jobId={} docId={} failed: {}", jobId, doc.getId(), e.getMessage(), e);
            scheduleRetry(job, e);
        }
    }

    /**
     * 终态失败：直接置 FAILED，写 lastError 与 finishedAt。
     *
     * <p>区别于 {@link #scheduleRetry}：本方法不计数、不退避，用于"前置检查失败"
     * （document not found / deleted / file missing / extraction empty），
     * 这些重试也不会成功。
     *
     * @param job   当前 job
     * @param error 人类可读的错误描述（前缀场景，便于运维聚合）
     */
    private void markFailed(DocumentIngestJob job, String error) {
        job.setStatus(DocumentIngestJob.Status.FAILED);
        job.setLastError(error);
        job.setFinishedAt(LocalDateTime.now());
        jobRepository.save(job);
    }

    /**
     * 安排重试：attempts++，lastError 写前 3 行堆栈；达到 maxAttempts 置 FAILED，
     * 否则置 PENDING 并设置 nextAttemptAt = now + 2^(attempts-1) 分钟。
     *
     * <p>退避序列（maxAttempts=3）：1min → 2min → FAILED（不再重试）。
     *
     * @param job 当前 job
     * @param e   触发本次失败的异常
     */
    private void scheduleRetry(DocumentIngestJob job, Exception e) {
        job.setAttempts(job.getAttempts() + 1);
        job.setLastError(stackHead(e, 3));
        if (job.getAttempts() >= job.getMaxAttempts()) {
            job.setStatus(DocumentIngestJob.Status.FAILED);
            job.setFinishedAt(LocalDateTime.now());
        } else {
            job.setStatus(DocumentIngestJob.Status.PENDING);
            // 指数退避：1min, 2min, 4min...
            long backoffMin = 1L << (job.getAttempts() - 1);
            job.setNextAttemptAt(LocalDateTime.now().plusMinutes(backoffMin));
        }
        jobRepository.save(job);
    }

    /**
     * 取异常的 message + 堆栈前 N 行，便于 lastError 字段存储。
     *
     * @param t     异常
     * @param lines 要保留的堆栈行数
     * @return message + "at ..." 形式的精简堆栈
     */
    private String stackHead(Throwable t, int lines) {
        StringBuilder sb = new StringBuilder(String.valueOf(t.getMessage()));
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(lines, st.length); i++) {
            sb.append("\n  at ").append(st[i]);
        }
        return sb.toString();
    }

    /**
     * 同步入口：由 {@link IngestionScheduler} 或外部触发。
     *
     * <p>本方法本身不加 {@code @Async}，由 {@code ingestExecutor} 通过 Spring 代理转发到
     * {@link #processAsync(String)}，这样调度器线程调用不会被自身代理拦截——避免自调用导致 @Async 失效。
     *
     * @param jobId DocumentIngestJob.jobId
     */
    public void processNow(String jobId) {
        processAsync(jobId);
    }
}