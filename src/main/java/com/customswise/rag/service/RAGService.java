package com.customswise.rag.service;

import com.customswise.rag.dto.QaRequest;
import com.customswise.rag.dto.QaResponse;
import com.customswise.rag.entity.PolicyDocument;
import com.customswise.rag.entity.QaHistory;
import com.customswise.rag.repository.PolicyDocumentRepository;
import com.customswise.rag.repository.QaHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class RAGService {

    private final MiniMaxService miniMaxService;
    private final MilvusService milvusService;
    private final PolicyDocumentRepository documentRepository;
    private final QaHistoryRepository qaHistoryRepository;
    private final ObjectMapper objectMapper;

    @Value("${rag.top-k}")
    private int topK;

    @Value("${rag.rerank-top-k}")
    private int rerankTopK;

    @Value("${rag.current-policy-boost}")
    private float currentPolicyBoost;

    @Value("${rag.expired-policy-penalty}")
    private float expiredPolicyPenalty;

    public RAGService(MiniMaxService miniMaxService,
                     MilvusService milvusService,
                     PolicyDocumentRepository documentRepository,
                     QaHistoryRepository qaHistoryRepository) {
        this.miniMaxService = miniMaxService;
        this.milvusService = milvusService;
        this.documentRepository = documentRepository;
        this.qaHistoryRepository = qaHistoryRepository;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 问答
     */
    public QaResponse ask(QaRequest request) {
        // 1. 查询向量化和检索
        float[] queryVector = miniMaxService.embed(request.getQuestion());

        // 2. 混合检索（向量 + 状态权重）
        List<Map<String, Object>> searchResults = milvusService.searchVectors(queryVector, topK * 2, null);

        // 3. 根据状态重新排序 - 现行政策优先
        searchResults.sort((a, b) -> {
            float scoreA = (float) a.get("score");
            float scoreB = (float) b.get("score");
            String statusA = (String) a.getOrDefault("status", "现行");
            String statusB = (String) b.getOrDefault("status", "现行");

            // 现行政策加分，已废止减分
            if ("现行".equals(statusA)) scoreA *= currentPolicyBoost;
            else scoreA *= expiredPolicyPenalty;

            if ("现行".equals(statusB)) scoreB *= currentPolicyBoost;
            else scoreB *= expiredPolicyPenalty;

            return Float.compare(scoreB, scoreA);
        });

        // 4. 取topK
        List<Map<String, Object>> topResults = searchResults.subList(0, Math.min(rerankTopK, searchResults.size()));

        // 5. 构建上下文
        StringBuilder context = new StringBuilder();
        List<QaResponse.Reference> references = new ArrayList<>();

        for (Map<String, Object> result : topResults) {
            String text = (String) result.get("text");
            String docId = (String) result.get("document_id");
            String status = (String) result.getOrDefault("status", "现行");

            context.append("【").append(status).append("】").append(text).append("\n\n");

            // 获取文档信息
            try {
                Long docIdLong = Long.parseLong(docId);
                PolicyDocument doc = documentRepository.findById(docIdLong).orElse(null);
                if (doc != null) {
                    QaResponse.Reference ref = new QaResponse.Reference();
                    ref.setDocumentId(doc.getId());
                    ref.setTitle(doc.getTitle());
                    ref.setDocumentNumber(doc.getDocumentNumber());
                    ref.setStatus(doc.getStatus());
                    ref.setContent(text.length() > 200 ? text.substring(0, 200) + "..." : text);
                    references.add(ref);

                    // 更新引用计数
                    doc.setReferenceCount(doc.getReferenceCount() + 1);
                    documentRepository.save(doc);
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid document_id: {}", docId);
            }
        }

        // 6. 组装Prompt
        String prompt = buildPrompt(request.getQuestion(), request.getUserConditions(), context.toString());

        // 7. 调用LLM
        String answer = miniMaxService.chat(prompt);

        // 8. 保存问答历史
        saveHistory(request, answer, references);

        // 9. 返回结果
        QaResponse response = new QaResponse();
        response.setAnswer(answer);
        response.setReferences(references);

        return response;
    }

    /**
     * 构建Prompt
     */
    private String buildPrompt(String question, String userConditions, String context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的跨境电商海关政策助手。请根据以下参考资料回答用户的问题。\n\n");

        if (userConditions != null && !userConditions.isEmpty()) {
            prompt.append("用户条件：").append(userConditions).append("\n\n");
        }

        prompt.append("【参考资料】\n").append(context).append("\n\n");

        prompt.append("【问题】").append(question).append("\n\n");

        prompt.append("【回答要求】\n");
        prompt.append("1. 优先参考标注为【现行】的政策\n");
        prompt.append("2. 如果参考了【已废止】的政策，必须明确提醒用户该政策已废止\n");
        prompt.append("3. 必须列出引用的政策来源（标题、公告编号）\n");
        prompt.append("4. 如果没有找到相关资料，请如实告知，不要编造\n");
        prompt.append("5. 强调本回答仅供参考，不构成报关法律依据\n\n");

        prompt.append("【回答】");

        return prompt.toString();
    }

    /**
     * 保存问答历史
     */
    private void saveHistory(QaRequest request, String answer, List<QaResponse.Reference> references) {
        try {
            QaHistory history = new QaHistory();
            history.setSessionId(request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString());
            history.setUserQuery(request.getQuestion());
            history.setUserConditions(request.getUserConditions());
            history.setAiResponse(answer);
            history.setReferencesInfo(objectMapper.writeValueAsString(references));
            history.setCreatedAt(LocalDateTime.now());
            qaHistoryRepository.save(history);
        } catch (JsonProcessingException e) {
            log.error("Failed to save QA history", e);
        }
    }

    /**
     * 获取问答历史
     */
    public Page<QaHistory> getHistory(String sessionId, int page, int size) {
        if (sessionId != null && !sessionId.isEmpty()) {
            return qaHistoryRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(page, size));
        }
        return qaHistoryRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }
}
