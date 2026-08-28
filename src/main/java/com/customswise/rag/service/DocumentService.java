package com.customswise.rag.service;

import com.customswise.rag.dto.DocumentUploadRequest;
import com.customswise.rag.dto.JobAck;
import com.customswise.rag.entity.DocumentChunk;
import com.customswise.rag.entity.DocumentIngestJob;
import com.customswise.rag.entity.PolicyDocument;
import com.customswise.rag.pdf.ExtractionResult;
import com.customswise.rag.pdf.SemanticChunker;
import com.customswise.rag.pdf.TextNormalizer;
import com.customswise.rag.repository.DocumentChunkRepository;
import com.customswise.rag.repository.PolicyDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final PolicyDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final MilvusService milvusService;
    private final MiniMaxService miniMaxService;
    private final TextExtractorService textExtractorService;
    private final IngestionService ingestionService;

    @Value("${storage.upload-dir}")
    private String uploadDir;

    /**
     * 同步阶段：落盘 + 建 PolicyDocument + 建 IngestJob，立即返回 JobAck。
     * 真正的解析向量化由 Controller 触发 ingestionService.processAsync() 异步执行。
     * 重复上传（fileHash 命中）→ 返回 status="DUPLICATE"，不建新文档/任务。
     */
    public JobAck stageUpload(MultipartFile file, DocumentUploadRequest metadata) throws IOException {
        // 1. 计算文件hash去重
        String fileHash = calculateHash(file.getBytes());
        Optional<PolicyDocument> existing = documentRepository.findAll().stream()
                .filter(d -> fileHash.equals(d.getFileHash()) && !Boolean.TRUE.equals(d.getDeleted()))
                .findFirst();
        if (existing.isPresent()) {
            PolicyDocument d = existing.get();
            log.info("UPLOAD duplicate fileHash={} docId={} title=\"{}\"", fileHash, d.getId(), d.getTitle());
            return new JobAck(d.getId(), null, "DUPLICATE", "已上传过该文档");
        }

        // 2. 保存文件
        String filePath = saveFile(file);

        // 3. 建 PolicyDocument（status 字段用作"现行/已废止"，不影响摄取阶段）
        PolicyDocument document = new PolicyDocument();
        document.setTitle(metadata.getTitle());
        document.setDocumentNumber(metadata.getDocumentNumber());
        document.setPublishDate(metadata.getPublishDate());
        document.setEffectiveDate(metadata.getEffectiveDate());
        document.setExpireDate(metadata.getExpireDate());
        document.setStatus(metadata.getStatus() != null ? metadata.getStatus() : "现行");
        document.setApplicableBusiness(metadata.getApplicableBusiness());
        document.setSummary(metadata.getSummary());
        document.setFilePath(filePath);
        document.setFileHash(fileHash);
        document.setMilvusCollection("customswise_docs");
        document.setCreatedAt(LocalDateTime.now());
        document.setReferenceCount(0);
        document = documentRepository.save(document);

        // 4. 建 IngestJob
        DocumentIngestJob job = ingestionService.createJobFor(document.getId());
        log.info("UPLOAD staged docId={} fileHash={} fileSize={}B jobId={}",
                document.getId(), fileHash, file.getSize(), job.getJobId());
        return new JobAck(document.getId(), job.getJobId(), "PENDING", "已接收，处理中");
    }

    /**
     * 上传并处理政策文档（同步阻塞，保留为向后兼容）。
     *
     * 新代码请用 stageUpload() + ingestionService.processAsync(jobId) 实现异步。
     */
    @Deprecated
    public PolicyDocument uploadDocument(MultipartFile file, DocumentUploadRequest metadata) throws IOException {
        // 1. 计算文件hash去重
        String fileHash = calculateHash(file.getBytes());
        if (documentRepository.existsByFileHash(fileHash)) {
            throw new RuntimeException("该文档已上传，请勿重复上传");
        }

        // 2. 保存文件
        String filePath = saveFile(file);

        // 3. 解析PDF提取文本（PDFBox 主路径 + OCR 兜底）
        ExtractionResult er = textExtractorService.extractFromPath(Path.of(filePath));
        log.info("UPLOAD fileHash={} fileSize={}B pages={} usedOcr={} chars={} elapsedMs={} extractor={}",
                fileHash, file.getSize(), er.pages(), er.usedOcr(), er.text().length(), er.elapsedMs(), er.extractor());

        // 4. 规范化 + 语义切片（条款锚粗切 + 滑窗）
        String normalized = TextNormalizer.normalize(er.text());
        List<SemanticChunker.Chunk> chunks = new SemanticChunker().split(normalized);
        log.info("NORMALIZE charsIn={} charsOut={} paragraphs={}",
                er.text().length(), normalized.length(), chunks.size());

        // 5. 创建文档记录
        PolicyDocument document = new PolicyDocument();
        document.setTitle(metadata.getTitle());
        document.setDocumentNumber(metadata.getDocumentNumber());
        document.setPublishDate(metadata.getPublishDate());
        document.setEffectiveDate(metadata.getEffectiveDate());
        document.setExpireDate(metadata.getExpireDate());
        document.setStatus(metadata.getStatus() != null ? metadata.getStatus() : "现行");
        document.setApplicableBusiness(metadata.getApplicableBusiness());
        document.setSummary(metadata.getSummary());
        document.setFilePath(filePath);
        document.setFileHash(fileHash);
        document.setMilvusCollection("customswise_docs");
        document.setCreatedAt(LocalDateTime.now());
        document.setReferenceCount(0);

        document = documentRepository.save(document);

        log.info("CHUNKER docId={} chunks={} avgChars={}",
                document.getId(),
                chunks.size(),
                chunks.isEmpty() ? 0 : chunks.stream().mapToInt(c -> c.text().length()).sum() / chunks.size());

        // 6. 向量化并存储到Milvus
        for (SemanticChunker.Chunk chunk : chunks) {
            String chunkText = chunk.text();
            float[] vector = miniMaxService.embed(chunkText);

            Map<String, String> metadataMap = new HashMap<>();
            metadataMap.put("document_id", String.valueOf(document.getId()));
            metadataMap.put("chunk_index", String.valueOf(chunk.chunkIndex()));
            metadataMap.put("anchor", chunk.anchor());
            metadataMap.put("status", document.getStatus());

            String vectorId = milvusService.insertVector(chunkText, vector, metadataMap);

            // 保存切片记录
            DocumentChunk dc = new DocumentChunk();
            dc.setDocumentId(document.getId());
            dc.setChunkIndex(chunk.chunkIndex());
            dc.setContent(chunkText);
            dc.setVectorId(vectorId);
            dc.setCreatedAt(LocalDateTime.now());
            chunkRepository.save(dc);
        }

        return document;
    }

    /**
     * 按语义切片 - 旧实现已废弃，由 SemanticChunker 替代。
     * 保留此方法为兼容旧测试；新逻辑见 pdf/SemanticChunker。
     */
    @Deprecated
    private List<String> semanticChunking(String content) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\n\n");
        StringBuilder currentChunk = new StringBuilder();

        for (String para : paragraphs) {
            if (currentChunk.length() + para.length() > 500) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
            }
            currentChunk.append(para).append("\n\n");
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    /**
     * 从PDF提取文本（已迁移到 TextExtractorService / PdfExtractorFactory）。
     * 保留此方法为兼容旧测试，新代码请直接用 TextExtractorService。
     */
    @Deprecated
    private String extractTextFromPdf(String filePath) throws IOException {
        return textExtractorService.extractFromPdf(new java.io.File(filePath));
    }

    /**
     * 保存文件到本地
     */
    private String saveFile(MultipartFile file) throws IOException {
        // 使用绝对路径，确保在项目根目录下
        Path uploadPath = Paths.get(System.getProperty("user.dir"), uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Path filePath = uploadPath.resolve(fileName);
        file.transferTo(filePath.toFile());

        return filePath.toString();
    }

    /**
     * 计算文件MD5
     */
    private String calculateHash(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 删除文档（软删除 PolicyDocument + 物理删除 Milvus 向量 + DocumentChunk）。
     * 整体事务：chunkRepository.deleteByDocumentId 需要事务上下文。
     */
    @Transactional
    public void deleteDocument(Long id) {
        PolicyDocument doc = documentRepository.findById(id).orElseThrow(() -> new RuntimeException("文档不存在"));
        doc.setDeleted(true);
        doc.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(doc);

        // 删除切片记录（必须在事务内）
        chunkRepository.deleteByDocumentId(id);

        // 删除 Milvus 中的向量（事务外也 OK，但放在事务里保证一致性）
        try {
            milvusService.deleteByDocumentId(String.valueOf(id));
        } catch (Exception e) {
            log.warn("Milvus delete failed for docId={}, will rely on next migration reconcile: {}",
                    id, e.getMessage());
        }
    }

    /**
     * 更新文档元数据
     */
    public PolicyDocument updateDocument(Long id, DocumentUploadRequest metadata) {
        PolicyDocument doc = documentRepository.findById(id).orElseThrow(() -> new RuntimeException("文档不存在"));
        doc.setTitle(metadata.getTitle());
        doc.setDocumentNumber(metadata.getDocumentNumber());
        doc.setPublishDate(metadata.getPublishDate());
        doc.setEffectiveDate(metadata.getEffectiveDate());
        doc.setExpireDate(metadata.getExpireDate());
        doc.setStatus(metadata.getStatus());
        doc.setApplicableBusiness(metadata.getApplicableBusiness());
        doc.setSummary(metadata.getSummary());
        doc.setUpdatedAt(LocalDateTime.now());
        return documentRepository.save(doc);
    }
}
