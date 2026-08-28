package com.customswise.rag.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
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

    @Value("${minimax.rerank-model:rerank-01}")
    private String rerankModel;

    /** 连接超时（毫秒）。 */
    @Value("${minimax.http.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    /** 读取超时（毫秒）。默认 5s——embed/rerank 推理快；chat 由调用方按需覆盖到 30s。 */
    @Value("${minimax.http.response-timeout-ms:5000}")
    private int responseTimeoutMs;

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

    public String getRerankModel() {
        return rerankModel;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getResponseTimeoutMs() {
        return responseTimeoutMs;
    }

    /**
     * 共享的 MiniMax HTTP 客户端 Bean。
     *
     * <p>默认超时取自配置（connect=3s / response=5s），所有方法复用同一连接池；
     * chat 方法在每次调用时按需把 response-timeout 覆盖到 30s（LLM 生成 token 慢）。
     */
    @Bean("minimaxHttpClient")
    public CloseableHttpClient minimaxHttpClient() {
        RequestConfig defaultConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(responseTimeoutMs))
                .build();
        return HttpClients.custom()
                .setDefaultRequestConfig(defaultConfig)
                .disableAutomaticRetries()
                .build();
    }
}
