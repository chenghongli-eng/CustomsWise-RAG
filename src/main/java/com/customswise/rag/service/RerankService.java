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

    /** rerank 服务连接超时（毫秒）。本地服务通常 ms 级，但 GPU 重载时会慢。 */
    @Value("${rag.rerank.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    /** rerank 服务读取超时（毫秒）。BGE-reranker-v2-m3 推理 10 条长中文文档通常 5~15s，
     * 给到30s 留余量；超时走降级，不阻塞主链路。 */
    @Value("${rag.rerank.timeout-ms:30000}")
    private int timeoutMs;

    /** 单条 chunk 送 rerank 前的最大字符数。bge-reranker-v2-m3 是 cross-encoder，attention O(n²)，
     * 长 chunk 推理时间爆炸；500 字 ≈ 125 token 覆盖大多数政策条款。 */
    @Value("${rag.rerank.max-chars-per-doc:500}")
    private int maxCharsPerDoc;

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
            List<String> rawTexts = items.stream().map(textExtractor).toList();
            int[] truncatedCount = {0};
            List<String> texts = rawTexts.stream()
                    .map(t -> {
                        if (t != null && t.length() > maxCharsPerDoc) {
                            truncatedCount[0]++;
                            return t.substring(0, maxCharsPerDoc);
                        }
                        return t;
                    })
                    .toList();
            int totalRawChars = rawTexts.stream().mapToInt(t -> t == null ? 0 : t.length()).sum();
            int totalSentChars = texts.stream().mapToInt(t -> t == null ? 0 : t.length()).sum();
            log.info("[BGE_RERANK_PREP] candidates={} truncated={} avgRawChars={} maxRawChars={} avgSentChars={} totalRawChars={} totalSentChars={} cap={}",
                    texts.size(), truncatedCount[0],
                    totalRawChars / Math.max(1, texts.size()),
                    rawTexts.stream().mapToInt(t -> t == null ? 0 : t.length()).max().orElse(0),
                    totalSentChars / Math.max(1, texts.size()),
                    totalRawChars, totalSentChars, maxCharsPerDoc);
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
        long startMs = System.currentTimeMillis();
        var requestBody = new java.util.HashMap<String, Object>();
        requestBody.put("model", "bge-reranker-v2-m3");
        requestBody.put("query", query);
        requestBody.put("documents", documents);

        log.info("[BGE_RERANK] candidates={} topN={} query={}", documents.size(), topN, query);

        HttpPost post = new HttpPost(rerankUrl);
        post.setHeader("Content-Type", "application/json");
        post.setEntity(new org.apache.hc.core5.http.io.entity.StringEntity(
                objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8));

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                .build();
        post.setConfig(requestConfig);

        String jsonResponse = httpClient.execute(post, response ->
                EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));

        JsonNode root = objectMapper.readTree(jsonResponse);
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.warn("[BGE_RERANK] 响应缺少 results 字段: {} elapsedMs={}", jsonResponse, elapsed);
            return null;
        }

        List<RerankScore> scores = new ArrayList<>();
        float minScore = Float.MAX_VALUE, maxScore = -Float.MAX_VALUE, sumScore = 0;
        for (JsonNode item : results) {
            int idx = item.get("index").asInt();
            double score = item.get("relevance_score").asDouble();
            scores.add(new RerankScore(idx, (float) score));
            minScore = (float) Math.min(minScore, score);
            maxScore = (float) Math.max(maxScore, score);
            sumScore += (float) score;
        }
        long elapsed = System.currentTimeMillis() - startMs;
        float avgScore = scores.isEmpty() ? 0 : sumScore / scores.size();
        log.info("[BGE_RERANK] success candidates={} returned={} elapsedMs={} scoreRange=[{}, {}] avg={}",
                documents.size(), scores.size(), elapsed,
                String.format("%.3f", minScore), String.format("%.3f", maxScore), String.format("%.3f", avgScore));
        return scores;
    }
}
