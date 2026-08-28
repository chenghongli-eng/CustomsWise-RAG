package com.customswise.rag.controller;

import com.customswise.rag.dto.ApiResponse;
import com.customswise.rag.dto.DocumentUploadRequest;
import com.customswise.rag.dto.JobAck;
import com.customswise.rag.entity.PolicyDocument;
import com.customswise.rag.repository.PolicyDocumentRepository;
import com.customswise.rag.service.DocumentService;
import com.customswise.rag.service.IngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
@Tag(name = "文档管理", description = "政策文档的上传、查询、删除等管理功能")
public class DocumentController {

    private final DocumentService documentService;
    private final PolicyDocumentRepository documentRepository;
    private final IngestionService ingestionService;

    public DocumentController(DocumentService documentService,
                             PolicyDocumentRepository documentRepository,
                             IngestionService ingestionService) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.ingestionService = ingestionService;
    }

    @Operation(
        summary = "上传政策文档（异步）",
        description = "上传PDF格式的政策文档，立即返回 JobAck；解析与向量化在后台异步执行"
    )
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ApiResponse<JobAck> upload(
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "政策标题") @RequestParam("title") String title,
            @Parameter(description = "公告编号") @RequestParam(value = "documentNumber", required = false) String documentNumber,
            @Parameter(description = "发布时间 yyyy-MM-dd") @RequestParam(value = "publishDate", required = false) String publishDate,
            @Parameter(description = "生效时间 yyyy-MM-dd") @RequestParam(value = "effectiveDate", required = false) String effectiveDate,
            @Parameter(description = "失效时间 yyyy-MM-dd") @RequestParam(value = "expireDate", required = false) String expireDate,
            @Parameter(description = "状态：现行/已废止") @RequestParam(value = "status", defaultValue = "现行") String status,
            @Parameter(description = "适用业务标签，多个用逗号分隔") @RequestParam(value = "applicableBusiness", required = false) String applicableBusiness,
            @Parameter(description = "政策摘要") @RequestParam(value = "summary", required = false) String summary) {

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

            JobAck ack = documentService.stageUpload(file, request);
            // 触发异步处理；status=DUPLICATE 不重复处理
            if (ack.getJobId() != null) {
                ingestionService.processNow(ack.getJobId());
            }
            return ApiResponse.success("文档接收成功", ack);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @Operation(summary = "获取文档列表", description = "分页查询政策文档，支持按状态和业务标签筛选")
    @GetMapping
    public ApiResponse<Page<PolicyDocument>> list(
            @Parameter(description = "页码") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "状态筛选：现行/已废止") @RequestParam(required = false) String status,
            @Parameter(description = "适用业务标签") @RequestParam(required = false) String applicableBusiness) {

        Page<PolicyDocument> documents = documentRepository.findByFilters(
                status,
                applicableBusiness,
                PageRequest.of(page, size)
        );
        return ApiResponse.success(documents);
    }

    @Operation(summary = "获取单个文档", description = "根据ID获取政策文档详情")
    @GetMapping("/{id}")
    public ApiResponse<PolicyDocument> getById(
            @Parameter(description = "文档ID") @PathVariable Long id) {
        return documentRepository.findById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("文档不存在"));
    }

    @Operation(summary = "删除文档", description = "软删除政策文档，同时清理Milvus中的向量")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @Parameter(description = "文档ID") @PathVariable Long id) {
        try {
            documentService.deleteDocument(id);
            return ApiResponse.success("删除成功", null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @Operation(summary = "更新文档元数据", description = "更新政策文档的元数据信息")
    @PutMapping("/{id}")
    public ApiResponse<PolicyDocument> update(
            @Parameter(description = "文档ID") @PathVariable Long id,
            @RequestBody DocumentUploadRequest request) {
        try {
            PolicyDocument document = documentService.updateDocument(id, request);
            return ApiResponse.success("更新成功", document);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }
}
