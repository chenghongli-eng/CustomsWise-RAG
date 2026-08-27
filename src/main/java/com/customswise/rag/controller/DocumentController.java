package com.customswise.rag.controller;

import com.customswise.rag.dto.ApiResponse;
import com.customswise.rag.dto.DocumentUploadRequest;
import com.customswise.rag.entity.PolicyDocument;
import com.customswise.rag.repository.PolicyDocumentRepository;
import com.customswise.rag.service.DocumentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final PolicyDocumentRepository documentRepository;

    public DocumentController(DocumentService documentService,
                             PolicyDocumentRepository documentRepository) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
    }

    /**
     * 上传政策文档
     */
    @PostMapping("/upload")
    public ApiResponse<PolicyDocument> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "documentNumber", required = false) String documentNumber,
            @RequestParam(value = "publishDate", required = false) String publishDate,
            @RequestParam(value = "effectiveDate", required = false) String effectiveDate,
            @RequestParam(value = "expireDate", required = false) String expireDate,
            @RequestParam(value = "status", defaultValue = "现行") String status,
            @RequestParam(value = "applicableBusiness", required = false) String applicableBusiness,
            @RequestParam(value = "summary", required = false) String summary) {

        try {
            DocumentUploadRequest request = new DocumentUploadRequest();
            request.setTitle(title);
            request.setDocumentNumber(documentNumber);
            request.setStatus(status);
            request.setApplicableBusiness(applicableBusiness);
            request.setSummary(summary);

            if (publishDate != null && !publishDate.isEmpty()) {
                request.setPublishDate(java.time.LocalDate.parse(publishDate));
            }
            if (effectiveDate != null && !effectiveDate.isEmpty()) {
                request.setEffectiveDate(java.time.LocalDate.parse(effectiveDate));
            }
            if (expireDate != null && !expireDate.isEmpty()) {
                request.setExpireDate(java.time.LocalDate.parse(expireDate));
            }

            PolicyDocument document = documentService.uploadDocument(file, request);
            return ApiResponse.success("文档上传成功", document);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取文档列表
     */
    @GetMapping
    public ApiResponse<Page<PolicyDocument>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String applicableBusiness) {

        Page<PolicyDocument> documents = documentRepository.findByFilters(
                status,
                applicableBusiness,
                PageRequest.of(page, size)
        );
        return ApiResponse.success(documents);
    }

    /**
     * 获取单个文档
     */
    @GetMapping("/{id}")
    public ApiResponse<PolicyDocument> getById(@PathVariable Long id) {
        return documentRepository.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("文档不存在"));
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            documentService.deleteDocument(id);
            return ApiResponse.success("删除成功", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 更新文档元数据
     */
    @PutMapping("/{id}")
    public ApiResponse<PolicyDocument> update(@PathVariable Long id, @RequestBody DocumentUploadRequest request) {
        try {
            PolicyDocument document = documentService.updateDocument(id, request);
            return ApiResponse.success("更新成功", document);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
