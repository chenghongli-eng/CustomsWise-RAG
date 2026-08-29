package com.customswise.rag.service;

import com.customswise.rag.dto.RerankScore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Rerank 编排服务：在 Milvus 粗召回之后、LLM 之前对候选 chunk 做语义精排。
 *
 * <p>当前使用本地部署的 BGE-rerank-v2-m3（http://127.0.0.1:8001/v1/rerank）。
 * 任何失败（超时 / 连接失败 / parse error）走降级路径，返回入参原顺序。
 *
 */
@Slf4j
@Service
public class RerankService {

    /** 共享 HTTP 客户端。 */
    private final HttpClient httpClient;

    /** JSON 序列化工具。 */
    private final ObjectMapper objectMapper;

    /** 总开关：false 时直接返回原列表。 */
    @Value("${rag.rerank.enabled:true}")
    private boolean enabled;

    /** BGE rerank 服务地址。 */
    @Value("${rag.rerank.url:http://127.0.0.1:8001/v1/rerank}")
    private String rerankUrl;

    /** rerank 服务超时（毫秒）。 */
    @Value("${rag.rerank.timeout-ms:5000}")
    private int timeoutMs;

    public RerankService(
            @Qualifier("minimaxHttpClient") HttpClient httpClient,
            ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 对入参候选列表按与 query 的相关性重排。
     *
     * <p>降级策略（按优先级）：
     * <ol>
     *   <li>关闭或入参为空 → 返回原列表</li>
     *   <li>BGE rerank 请求失败 → 返回原列表</li>
     *   <li>BGE rerank 返回的 index 越界 / 缺失 → 返回原列表</li>
     * </ol>
     *
     * @param query         用户问题
     * @param items         候选列表（按"业务加权"已粗排）
     * @param textExtractor 从候选元素抽取纯文本（送 rerank API 用）
     * @return 重排后的列表；失败时返回入参原引用
     */
    public <T> List<T> rerank(String query, List<T> items, Function<T, String> textExtractor) {
        if (!enabled) {
            return items;
        }
        if (items == null || items.isEmpty()) {
            return items;
        }
        try {
            List<String> texts = items.stream().map(textExtractor).toList();
            List<RerankScore> scores = callBgeRerank(query, texts, items.size());
            if (scores == null || scores.isEmpty()) {
                log.info("[FALLBACK] rerank_fallback=true reason=api_returned_null candidates={}",
                        items.size());
                return items;
            }

            List<T> reranked = new ArrayList<>(scores.size());
            for (RerankScore s : scores) {
                if (s.index() >= 0 && s.index() < items.size()) {
                    reranked.add(items.get(s.index()));
                }
            }
            // 防御：rerank 结果少于原列表时，补齐末尾（按原顺序追加未出现的元素）
            if (reranked.size() < items.size()) {
                List<T> seen = new ArrayList<>(reranked);
                for (T orig : items) {
                    if (!seen.contains(orig)) {
                        reranked.add(orig);
                    }
                }
            }
            return reranked;
        } catch (Exception e) {
            log.error("[FALLBACK] rerank_fallback=true reason={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return items;
        }
    }

    /**
     * 调用本地 BGE-rerank-v2-m3 服务。
     *
     * @param query    用户问题
     * @param documents 候选文档列表
     * @param topN     返回条数
     * @return RerankScore 列表；失败时返回 null
     */
    private List<RerankScore> callBgeRerank(String query, List<String> documents, int topN) throws IOException {
        var requestBody = new java.util.HashMap<String, Object>();
        requestBody.put("model", "bge-reranker-v2-m3");
        requestBody.put("query", query);
        requestBody.put("documents", documents);

        HttpPost post = new HttpPost(rerankUrl);
        post.setHeader("Content-Type", "application/json");
        post.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(
                objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8));

        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                .build();
        post.setConfig(requestConfig);

        String jsonResponse = httpClient.execute(post, (HttpClientResponseHandler<String>) response -> {
            EntityUtils.consume(response.getEntity());
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        });

        JsonNode root = objectMapper.readTree(jsonResponse);
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            log.warn("[BGE_RERANK] 响应缺少 results 字段: {}", jsonResponse);
            return null;
        }

        List<RerankScore> scores = new ArrayList<>();
        for (JsonNode item : results) {
            int idx = item.get("index").asInt();
            double score = item.get("relevance_score").asDouble();
            scores.add(new RerankScore(idx, (float) score));
        }
        log.info("[BGE_RERANK] rerank 成功，candidates={} returned={}", documents.size(), scores.size());
        return scores;
    }
}
