package com.customswise.rag.service;

import com.customswise.rag.dto.DocumentUploadRequest;
import com.customswise.rag.entity.DocumentChunk;
import com.customswise.rag.entity.PolicyDocument;
import com.customswise.rag.repository.DocumentChunkRepository;
import com.customswise.rag.repository.PolicyDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
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
public class DocumentService {

    private final PolicyDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final MilvusService milvusService;
    private final MiniMaxService miniMaxService;

    @Value("${storage.upload-dir}")
    private String uploadDir;

    public DocumentService(PolicyDocumentRepository documentRepository,
                          DocumentChunkRepository chunkRepository,
                          MilvusService milvusService,
                          MiniMaxService miniMaxService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.milvusService = milvusService;
        this.miniMaxService = miniMaxService;
    }

    /**
     * 上传并处理政策文档
     */
    public PolicyDocument uploadDocument(MultipartFile file, DocumentUploadRequest metadata) throws IOException {
        // 1. 计算文件hash去重
        String fileHash = calculateHash(file.getBytes());
        if (documentRepository.existsByFileHash(fileHash)) {
            throw new RuntimeException("该文档已上传，请勿重复上传");
        }

        // 2. 保存文件
        String filePath = saveFile(file);

        // 3. 解析PDF提取文本
        String content = extractTextFromPdf(filePath);

        // 4. 语义切片
        List<String> chunks = semanticChunking(content);

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

        // 6. 向量化并存储到Milvus
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            float[] vector = miniMaxService.embed(chunkText);

            Map<String, String> metadataMap = new HashMap<>();
            metadataMap.put("document_id", String.valueOf(document.getId()));
            metadataMap.put("chunk_index", String.valueOf(i));
            metadataMap.put("status", document.getStatus());

            String vectorId = milvusService.insertVector(chunkText, vector, metadataMap);

            // 保存切片记录
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(i);
            chunk.setContent(chunkText);
            chunk.setVectorId(vectorId);
            chunk.setCreatedAt(LocalDateTime.now());
            chunkRepository.save(chunk);
        }

        return document;
    }

    /**
     * 按语义切片 - 简单实现，按段落和长度切分
     */
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
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    /**
     * 从PDF提取文本
     */
    private String extractTextFromPdf(String filePath) throws IOException {
        try (PDDocument document = Loader.loadPDF(new File(filePath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * 保存文件到本地
     */
    private String saveFile(MultipartFile file) throws IOException {
        Path uploadPath = Paths.get(uploadDir);
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
     * 删除文档
     */
    public void deleteDocument(Long id) {
        PolicyDocument doc = documentRepository.findById(id).orElseThrow(() -> new RuntimeException("文档不存在"));
        doc.setDeleted(true);
        doc.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(doc);

        // 删除Milvus中的向量
        milvusService.deleteByDocumentId(String.valueOf(id));

        // 删除切片记录
        chunkRepository.deleteByDocumentId(id);
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
