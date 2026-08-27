package com.customswise.rag.service;

import com.customswise.rag.config.MiniMaxConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class MiniMaxService {

    private final MiniMaxConfig config;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MiniMaxService(MiniMaxConfig config, CloseableHttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
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

            try (CloseableHttpClient client = httpClient) {
                var response = client.execute(post);
                String jsonResponse = EntityUtils.toString(response.getEntity());
                JsonNode root = objectMapper.readTree(jsonResponse);
                return root.path("choices").get(0).path("messages").asText();
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
            String url = config.getBaseUrl() + "/v1/text/embeddings";

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getEmbeddingModel());
            requestBody.put("texts", Collections.singletonList(text));

            HttpPost post = new HttpPost(url);
            post.setHeader("Authorization", "Bearer " + config.getApiKey());
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8));

            try (CloseableHttpClient client = httpClient) {
                var response = client.execute(post);
                String jsonResponse = EntityUtils.toString(response.getEntity());
                JsonNode root = objectMapper.readTree(jsonResponse);
                JsonNode embedding = root.path("data").get(0).path("embedding");
                float[] result = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    result[i] = (float) embedding.get(i).asDouble();
                }
                return result;
            }
        } catch (Exception e) {
            log.error("MiniMax embedding error", e);
            return new float[0];
        }
    }
}
