package com.customswise.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MiniMaxConfig {

    @Value("${minimax.api-key}")
    private String apiKey;

    @Value("${minimax.base-url}")
    private String baseUrl;

    @Value("${minimax.model}")
    private String model;

    @Value("${minimax.embedding-model}")
    private String embeddingModel;

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }
}
