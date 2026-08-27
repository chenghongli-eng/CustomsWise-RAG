package com.customswise.rag.controller;

import com.customswise.rag.dto.ApiResponse;
import com.customswise.rag.dto.QaRequest;
import com.customswise.rag.dto.QaResponse;
import com.customswise.rag.entity.QaHistory;
import com.customswise.rag.service.RAGService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final RAGService ragService;

    public QaController(RAGService ragService) {
        this.ragService = ragService;
    }

    /**
     * 提交问答
     */
    @PostMapping("/ask")
    public ApiResponse<QaResponse> ask(@RequestBody QaRequest request) {
        try {
            QaResponse response = ragService.ask(request);
            return ApiResponse.success(response);
        } catch (Exception e) {
            return ApiResponse.error("问答失败: " + e.getMessage());
        }
    }

    /**
     * 获取问答历史
     */
    @GetMapping("/history")
    public ApiResponse<Page<QaHistory>> history(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<QaHistory> histories = ragService.getHistory(sessionId, page, size);
        return ApiResponse.success(histories);
    }
}
