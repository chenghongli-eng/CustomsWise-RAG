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
        // 1. 生成问题向量
        float[] queryVector = miniMaxService.embed(request.getQuestion());
        log.info("问题: {}", request.getQuestion());
        log.info("问题向量维度: {}", queryVector.length);

        // 2. 搜索
        List<Map<String, Object>> searchResults = milvusService.searchVectors(queryVector, topK * 2);
        log.info("检索到 {} 条结果", searchResults.size());

        // 3. 把 Milvus 的 L2 距离转成相似度（越大越相关），并打印
        for (int i = 0; i < searchResults.size(); i++) {
            Map<String, Object> result = searchResults.get(i);
            float l2 = (float) result.getOrDefault("score", 0f);
            float similarity = 1f / (1f + l2);   // 距离→相似度
            result.put("similarity", similarity);

            String text = (String) result.getOrDefault("text", "");
            String docId = (String) result.getOrDefault("document_id", "");
            String status = (String) result.getOrDefault("status", "现行");
            log.info("结果{} - docId: {}, status: {}, similarity: {:.4f}, text: {}",
                    i, docId, status, similarity,
                    text.length() > 50 ? text.substring(0, 50) + "..." : text);
        }

        // 4. 根据状态重新排序 - 现行政策加权优先
        searchResults.sort((a, b) -> {
            float simA = (float) a.get("similarity");
            float simB = (float) b.get("similarity");
            String statusA = (String) a.getOrDefault("status", "现行");
            String statusB = (String) b.getOrDefault("status", "现行");

            // 现行政策加分，已废止减分
            if ("现行".equals(statusA)) simA *= currentPolicyBoost;
            else simA *= expiredPolicyPenalty;

            if ("现行".equals(statusB)) simB *= currentPolicyBoost;
            else simB *= expiredPolicyPenalty;

            return Float.compare(simB, simA);
        });

        // 5. 取 topK 用于生成上下文
        List<Map<String, Object>> topResults = searchResults.subList(0, Math.min(rerankTopK, searchResults.size()));
        log.info("排序后选 top{}:", rerankTopK);
        for (int i = 0; i < topResults.size(); i++) {
            Map<String, Object> r = topResults.get(i);
            log.info("  top{} - docId: {}, status: {}, similarity: {:.4f}",
                    i, r.get("document_id"), r.get("status"), r.get("similarity"));
        }

        // 6. 构建上下文
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

        // 7. 组装Prompt
        String prompt = buildPrompt(request.getQuestion(), request.getUserConditions(), context.toString());

        // 8. 调用LLM
        String answer = miniMaxService.chat(prompt);

        // 9. 保存问答历史
        saveHistory(request, answer, references);

        // 10. 返回结果
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
            history.setReferences(references);
            history.setCreatedAt(LocalDateTime.now());
            qaHistoryRepository.save(history);
        } catch (Exception e) {
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
