package com.customswise.rag.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private Integer port;

    @Bean
    public MilvusClientV2 milvusClient() {
        try {
            return new MilvusClientV2(ConnectConfig.builder()
                    .uri("http://" + host + ":" + port)
                    .connectTimeoutMs(5_000L)
                    .build());
        } catch (Exception e) {
            log.warn("⚠️ Milvus 连接失败（{}:{}），项目将以降级模式启动: {}", host, port, e.getMessage());
            return null;
        }
    }
}