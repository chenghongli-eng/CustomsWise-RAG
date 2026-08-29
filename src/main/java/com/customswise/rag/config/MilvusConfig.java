package com.customswise.rag.config;

import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
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
    public MilvusClient milvusClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withConnectTimeout(5, TimeUnit.SECONDS)   // 连接超时 2 秒，快速失败
                .build();
        try {
            return new MilvusServiceClient(connectParam);
        } catch (Exception e) {
            log.warn("⚠️ Milvus 连接失败（{}:{}），项目将以降级模式启动: {}", host, port, e.getMessage());
            return null;   // 连接失败返回 null，后续 MilvusService 需做空检查
        }
    }
}
