package com.customswise.rag.service;

import com.customswise.rag.config.MiniMaxConfig;
import com.customswise.rag.dto.RerankScore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class MiniMaxService {

    private final MiniMaxConfig config;
    private final ObjectMapper objectMapper;
    /** 共享 HTTP 客户端（连接池复用，默认 3s/5s 超时，由 MiniMaxConfig 配置）。 */
    private final CloseableHttpClient httpClient;

    /** chat 单独覆盖的 response-timeout（LLM 生成 token 慢）。 */
    private static final int CHAT_RESPONSE_TIMEOUT_MS = 30_000;

    public MiniMaxService(MiniMaxConfig config,
                         @Qualifier("minimaxHttpClient") CloseableHttpClient httpClient) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.httpClient = httpClient;
    }

    /** 给本方法用的 RequestConfig：chat 单独放宽 response-timeout 到 30s。 */
    private RequestConfig chatRequestConfig() {
        return RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(config.getConnectTimeoutMs()))
                .setResponseTimeout(Timeout.ofMilliseconds(CHAT_RESPONSE_TIMEOUT_MS))
                .build();
    }

    /**
     * 调用MiniMax API生成文本
     */
    public String chat(String prompt) {
        try {
            String url = config.getBaseUrl() + "/v1/text/chatcompletion_v2";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getModel());
            requestBody.put("tokens_to_generate", 2048);

            Map<String, Object> role = new HashMap<>();
            role.put("role", "user");
            role.put("content", prompt);
            requestBody.put("messages", Collections.singletonList(role));

            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Bearer " + config.getApiKey());
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8));
            post.setConfig(chatRequestConfig());

            var response = httpClient.execute(post);
                int statusCode = response.getCode();
                String jsonResponse = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

                // HTTP 4xx → 业务故障（鉴权/参数/配额），由调用方捕获后返回业务错误文案
                if (statusCode >= 400 && statusCode < 500) {
                    log.error("[FALLBACK] chat_error=true reason=business status={} body={}",
                            statusCode, jsonResponse);
                    throw new MiniMaxException.Business(statusCode, jsonResponse);
                }
                // HTTP 5xx → 服务端故障，由调用方捕获后返回业务错误文案
                if (statusCode >= 500) {
                    log.error("[FALLBACK] chat_error=true reason=server_error status={} body={}",
                            statusCode, jsonResponse);
                    throw new MiniMaxException.ServerError(statusCode, jsonResponse);
                }

                log.info("MiniMax chat response: {}", jsonResponse);
                JsonNode root = objectMapper.readTree(jsonResponse);
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode first = choices.get(0);
                    String answer = first.path("message").path("content").asText("");
                    if (answer.isEmpty()) {
                        answer = first.path("messages").asText("");
                    }
                    if (answer.isEmpty()) {
                        answer = first.path("text").asText("");
                    }
                    return answer;
                }
                return "MiniMax API响应格式异常";
        } catch (java.net.SocketTimeoutException e) {
            // 连接/读取超时——可降级为友好提示文案，HTTP 仍 200
            // 注：HttpClient 4 的 ConnectTimeoutException 已迁到 HC5 后不可用，SocketTimeoutException
            // 是其父类，单独 catch 即可覆盖连接+读取超时
            log.warn("[FALLBACK] chat_timeout=true reason=socket_timeout elapsedMs={}",
                    CHAT_RESPONSE_TIMEOUT_MS);
            return "调用MiniMax API超时（" + CHAT_RESPONSE_TIMEOUT_MS + "ms）";
        } catch (MiniMaxException e) {
            // 已经在 throw 前打了 [FALLBACK] 标记，这里直接抛给 RAGService 处理
            throw e;
        } catch (Exception e) {
            // 网络层/解析层异常，包装为 Network 让 RAGService 走业务错误路径
            log.error("[FALLBACK] chat_error=true reason=network msg={}", e.getMessage());
            throw new MiniMaxException.Network(e.getMessage(), e);
        }
    }

    /**
     * 调用MiniMax Embedding接口
     */
    public float[] embed(String text) {
        try {
            String url = config.getBaseUrl() + "/v1/embeddings";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getEmbeddingModel());
            requestBody.put("texts", Collections.singletonList(text));
            requestBody.put("type", "db");

            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Bearer " + config.getApiKey());
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8));

            var response = httpClient.execute(post);
            int statusCode = response.getCode();
            String jsonResponse = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            // 完整 body 在 debug；生产环境避免 1536 维向量污染日志
            log.debug("MiniMax Embedding response: {}", jsonResponse);

            if (statusCode != 200) {
                log.warn("[FALLBACK] embed_fallback=true reason=non_2xx status={} textLen={}",
                        statusCode, text.length());
                return generateFallbackVector(text);
            }

            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode vectors = root.path("vectors");
            if (vectors.isArray() && vectors.size() > 0) {
                JsonNode embedding = vectors.get(0);
                float[] result = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    result[i] = (float) embedding.get(i).asDouble();
                }
                return result;
            }
            JsonNode data = root.path("data");
            if (data.isArray() && data.size() > 0) {
                JsonNode embedding = data.get(0).path("embedding");
                float[] result = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    result[i] = (float) embedding.get(i).asDouble();
                }
                return result;
            }
        } catch (java.net.SocketTimeoutException e) {
            log.warn("[FALLBACK] embed_fallback=true reason=timeout ({}ms) textLen={}",
                    config.getResponseTimeoutMs(), text.length());
        } catch (Exception e) {
            log.warn("[FALLBACK] embed_fallback=true reason={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
        }

        return generateFallbackVector(text);
    }

    /**
     * 调用 MiniMax rerank API 对候选文档按与 query 的相关性重排。
     *
     * <p>请求体：
     * <pre>
     *   { "model": "rerank-01", "query": "...", "documents": [...], "top_n": N }
     * </pre>
     *
     * <p>响应体（典型）：
     * <pre>
     *   { "results": [{ "index": 0, "relevance_score": 0.92 }, ...] }
     * </pre>
     *
     * <p>调用方约定：异常时本方法返回 {@code null}，由 {@link com.customswise.rag.service.RerankService}
     * 走降级路径；任何业务异常都不外抛。
     *
     * @param query     用户原始问题
     * @param documents 候选 chunk 文本列表
     * @param topN      期望返回的最大条数（不超过 documents.size()）
     * @return 按 score 降序排列的 RerankScore 列表；失败返回 null
     */
    public List<RerankScore> rerank(String query, List<String> documents, int topN) {
        long t0 = System.currentTimeMillis();
        try {
            String url = config.getBaseUrl() + "/v1/rerank";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getRerankModel());
            requestBody.put("query", query);
            requestBody.put("documents", documents);
            requestBody.put("top_n", Math.min(topN, documents.size()));

            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Bearer " + config.getApiKey());
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8));

            var response = httpClient.execute(post);
            int statusCode = response.getCode();
            String jsonResponse = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

            if (statusCode != 200) {
                log.warn("MiniMax rerank API error status={} body={}", statusCode, jsonResponse);
                return null;
            }

            log.debug("MiniMax rerank response: {}", jsonResponse);
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.warn("MiniMax rerank response missing results array");
                return null;
            }

            List<RerankScore> scores = new ArrayList<>(results.size());
            for (JsonNode item : results) {
                int idx = item.path("index").asInt(-1);
                float score = (float) item.path("relevance_score").asDouble(0.0);
                if (idx >= 0 && idx < documents.size()) {
                    scores.add(new RerankScore(idx, score));
                }
            }
            scores.sort((a, b) -> Float.compare(b.score(), a.score()));
            log.info("RERANK model={} candidates={} returned={} elapsedMs={}",
                    config.getRerankModel(), documents.size(), scores.size(),
                    System.currentTimeMillis() - t0);
            return scores;
        } catch (java.net.SocketTimeoutException e) {
            log.warn("MiniMax rerank timeout ({}ms) candidates={}",
                    config.getResponseTimeoutMs(), documents.size());
            return null;
        } catch (Exception e) {
            log.warn("MiniMax rerank failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成备用向量（当API不可用时使用，基于文本hash）
     */
    private float[] generateFallbackVector(String text) {
        float[] result = new float[1536];
        int hash = text.hashCode();
        Random random = new Random(hash);
        for (int i = 0; i < 1536; i++) {
            result[i] = (random.nextFloat() * 2) - 1;
        }
        double sum = 0;
        for (float v : result) {
            sum += v * v;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < result.length; i++) {
            result[i] = (float) (result[i] / norm);
        }
        log.warn("Using fallback vector for text: {}", text.substring(0, Math.min(50, text.length())));
        return result;
    }
}
