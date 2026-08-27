package com.customswise.rag.service;

import com.customswise.rag.config.MiniMaxConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class MiniMaxService {

    private final MiniMaxConfig config;
    private final ObjectMapper objectMapper;

    public MiniMaxService(MiniMaxConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 调用MiniMax API生成文本
     */
    public String chat(String prompt) {
        try {
            String url = config.getBaseUrl() + "/v1/text/chatcompletion_v2";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getModel());
            requestBody.put("tokens_to_generate", 1024);

            Map<String, Object> role = new HashMap<>();
            role.put("role", "user");
            role.put("content", prompt);
            requestBody.put("messages", Collections.singletonList(role));

            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Bearer " + config.getApiKey());
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8));

            var client = HttpClients.createDefault();
            try {
                var response = client.execute(post);
                int statusCode = response.getStatusLine().getStatusCode();
                String jsonResponse = EntityUtils.toString(response.getEntity());

                if (statusCode != 200) {
                    log.error("MiniMax API error, status: {}, response: {}", statusCode, jsonResponse);
                    return "调用MiniMax API失败，状态码: " + statusCode;
                }

                JsonNode root = objectMapper.readTree(jsonResponse);
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    return choices.get(0).path("messages").asText();
                }
                return "MiniMax API响应格式异常";
            } finally {
                client.close();
            }
        } catch (Exception e) {
            log.error("MiniMax chat error", e);
            return "调用MiniMax API失败: " + e.getMessage();
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

            var client = HttpClients.createDefault();
            try {
                var response = client.execute(post);
                int statusCode = response.getStatusLine().getStatusCode();
                String jsonResponse = EntityUtils.toString(response.getEntity());
                log.info("MiniMax Embedding response status: {}, body: {}", statusCode, jsonResponse);

                if (statusCode != 200) {
                    log.error("MiniMax Embedding API error, status: {}, response: {}", statusCode, jsonResponse);
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
            } finally {
                client.close();
            }
        } catch (Exception e) {
            log.error("MiniMax embedding error: {}", e.getMessage());
        }

        return generateFallbackVector(text);
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
