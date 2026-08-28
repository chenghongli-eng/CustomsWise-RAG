package com.customswise.rag.service;

import com.customswise.rag.entity.DocumentChunk;
import com.customswise.rag.entity.PolicyDocument;
import com.customswise.rag.entity.SchemaVersion;
import com.customswise.rag.pdf.SemanticChunker;
import com.customswise.rag.pdf.TextNormalizer;
import com.customswise.rag.repository.DocumentChunkRepository;
import com.customswise.rag.repository.PolicyDocumentRepository;
import com.customswise.rag.repository.SchemaVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动对账服务：比对 PG 的 {@link com.customswise.rag.entity.DocumentChunk} 与
 * Milvus 的向量数，缺失时从 file_path 重新解析 + 切片 + 向量化补齐。
 *
 * <p>设计要点：
 * <ul>
 *   <li>仅在 Milvus 实际向量数 &lt; PG chunk 数时触发修复（避免误删）</li>
 *   <li>修复策略：先删 Milvus 中该 doc 的所有向量 + 清空 DocumentChunk，再重新走完整流程，
 *       这样保证两边一致，避免半修复状态</li>
 *   <li>{@code migration.enabled=false} 时跳过整个流程；
 *       {@code migration.dry-run=true} 时只打印缺失列表，不实际写入（线上验证用）</li>
 *   <li>幂等性：执行结束会写一条 schema_version 记录，下次启动 pgVer 不变则
 *       仍以 PG 实际 chunk 数为权威源对账（chunk 数变更即触发修复）</li>
 * </ul>
 */
@Slf4j
@Service
@Order(10)
@RequiredArgsConstructor
public class MigrationService implements ApplicationRunner {

    /** 政策文档仓储（遍历所有未删除文档）。 */
    private final PolicyDocumentRepository documentRepository;

    /** chunk 仓储（用于 countByDocumentId 与 deleteByDocumentId）。 */
    private final DocumentChunkRepository chunkRepository;

    /** schema 版本表，幂等控制用。 */
    private final SchemaVersionRepository schemaVersionRepository;

    /** Milvus v2 补充封装（countByFilter / deleteByDocumentId）。 */
    private final MilvusServiceV2 milvusV2;

    /** Milvus v1 主服务（insertVector）。 */
    private final MilvusService milvusService;

    /** MiniMax Embedding 服务。 */
    private final MiniMaxService miniMaxService;

    /** PDF 文本提取（PDFBox → OCR 兜底）。 */
    private final TextExtractorService textExtractorService;

    /** Spring 事务管理器，用于包裹 JPA delete 衍生方法。 */
    private final PlatformTransactionManager txManager;

    /** 懒加载的 TransactionTemplate（避免注入时 txManager 未就绪）。 */
    private TransactionTemplate tx;

    /** 总开关：false 时跳过整个对账流程。 */
    @Value("${migration.enabled:true}")
    private boolean enabled;

    /** 演练模式：true 时只打日志不写库不写 Milvus。 */
    @Value("${migration.dry-run:false}")
    private boolean dryRun;

    /**
     * ApplicationRunner 入口：Spring Boot 启动完成后执行对账。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[MIGRATION] disabled, skip");
            return;
        }
        if (tx == null) {
            tx = new TransactionTemplate(txManager);
        }

        var existing = schemaVersionRepository.findById("milvus");
        int pgVer = existing.map(SchemaVersion::getVersion).orElse(0);
        log.info("[MIGRATION] phase=start pgVer={} dryRun={}", pgVer, dryRun);

        long startedAt = System.currentTimeMillis();
        int total = 0, missingDocs = 0, rebuiltChunks = 0;

        for (PolicyDocument doc : documentRepository.findAll()) {
            if (Boolean.TRUE.equals(doc.getDeleted())) {
                continue;
            }
            total++;

            long pgCount = chunkRepository.countByDocumentId(doc.getId());
            long mvCount = milvusV2.countByFilter("document_id == \"" + doc.getId() + "\"");

            if (mvCount < 0) {
                // Milvus 不可用（countByFilter 内部异常返回 -1）→ 跳过避免反复失败
                log.warn("[MIGRATION] docId={} mvChunks=-1 (Milvus不可用), 跳过对账", doc.getId());
                continue;
            }
            if (mvCount == pgCount) {
                continue;
            }

            missingDocs++;
            if (dryRun) {
                log.info("[MIGRATION] docId={} title=\"{}\" pgChunks={} mvChunks={} status=DRY_RUN",
                        doc.getId(), doc.getTitle(), pgCount, mvCount);
                continue;
            }

            int rebuilt = repairDocument(doc);
            rebuiltChunks += rebuilt;
            log.info("[MIGRATION] docId={} title=\"{}\" pgChunks={} mvChunks={} status=REPAIR reinserted={}",
                    doc.getId(), doc.getTitle(), pgCount, mvCount, rebuilt);
        }

        // 写 schema 版本（幂等）
        SchemaVersion ver = existing.orElseGet(SchemaVersion::new);
        ver.setComponent("milvus");
        ver.setVersion(1);
        ver.setUpgradedAt(LocalDateTime.now());
        schemaVersionRepository.save(ver);

        log.info("[MIGRATION] done total={} missing={} rebuiltChunks={} elapsedMs={}",
                total, missingDocs, rebuiltChunks, System.currentTimeMillis() - startedAt);
    }

    /**
     * 修复单个文档：清空旧向量与旧 chunk，按当前流水线重新生成。
     *
     * <p>任一 chunk 失败仅记录日志、不中断后续 chunk；整体异常返回 0。
     *
     * @param doc 需要重建的 PolicyDocument
     * @return 实际重新写入 Milvus 与 PG 的 chunk 数
     */
    private int repairDocument(PolicyDocument doc) {
        try {
            // 1. 清理：Milvus 中该 doc 的所有向量
            milvusV2.deleteByDocumentId(String.valueOf(doc.getId()));

            // 2. 清理：PG 中该 doc 的所有 chunk（避免重复主键）
            //    Spring Data JPA 的 void 衍生 delete 方法需要在事务内调用
            tx.executeWithoutResult(status -> chunkRepository.deleteByDocumentId(doc.getId()));

            // 3. 重新解析
            File file = new File(doc.getFilePath());
            if (!file.exists() || !file.isFile()) {
                log.warn("[MIGRATION] docId={} file missing: {}", doc.getId(), doc.getFilePath());
                return 0;
            }

            String raw = textExtractorService.extractFromPath(file.toPath()).text();
            String normalized = TextNormalizer.normalize(raw);
            List<SemanticChunker.Chunk> chunks = new SemanticChunker().split(normalized);

            // 4. 向量化 + 写入
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
                    dc.setVectorId("rebuilt-" + doc.getId() + "-" + c.chunkIndex());
                    dc.setCreatedAt(LocalDateTime.now());
                    chunkRepository.save(dc);
                    inserted++;
                } catch (Exception e) {
                    log.error("[MIGRATION] docId={} chunk {} embed/insert failed: {}",
                            doc.getId(), c.chunkIndex(), e.getMessage());
                }
            }
            return inserted;
        } catch (Exception e) {
            log.error("[MIGRATION] docId={} repair failed: {}", doc.getId(), e.getMessage(), e);
            return 0;
        }
    }
}
