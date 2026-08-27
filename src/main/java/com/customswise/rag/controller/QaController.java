package com.customswise.rag.controller;

import com.customswise.rag.dto.ApiResponse;
import com.customswise.rag.dto.QaRequest;
import com.customswise.rag.dto.QaResponse;
import com.customswise.rag.entity.QaHistory;
import com.customswise.rag.service.RAGService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/qa")
@Tag(name = "智能问答", description = "基于RAG的海关政策智能问答功能")
public class QaController {

    private final RAGService ragService;

    public QaController(RAGService ragService) {
        this.ragService = ragService;
    }

    @Operation(
        summary = "提交问答",
        description = "输入问题，获取基于政策知识库的智能回答。系统会优先参考现行政策，已废止政策会明确标注。",
        requestBody = @io.swagger.v3.oas.annotations.RequestBody(
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = QaRequest.class)
            )
        )
    )
    @PostMapping("/ask")
    public ApiResponse<QaResponse> ask(@RequestBody QaRequest request) {
        try {
            QaResponse response = ragService.ask(request);
            return ApiResponse.success(response);
        } catch (Exception e) {
            return ApiResponse.error("问答失败: " + e.getMessage());
        }
    }

    @Operation(summary = "获取问答历史", description = "查看历史问答记录，支持按会话ID筛选")
    @GetMapping("/history")
    public ApiResponse<Page<QaHistory>> history(
            @Parameter(description = "会话ID，用于筛选特定会话") @RequestParam(required = false) String sessionId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") int size) {

        Page<QaHistory> histories = ragService.getHistory(sessionId, page, size);
        return ApiResponse.success(histories);
    }
}
