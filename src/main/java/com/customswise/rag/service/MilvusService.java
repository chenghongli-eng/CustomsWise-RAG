package com.customswise.rag.service;

import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.*;
import io.milvus.param.dml.*;
import io.milvus.param.collection.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

@Slf4j
@Service
public class MilvusService {

    private final MilvusClient milvusClient;

    @Value("${milvus.collection-name}")
    private String collectionName;

    public MilvusService(MilvusClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    @PostConstruct
    public void init() {
        try {
            createCollectionIfNotExists();
        } catch (Exception e) {
            log.warn("Failed to initialize Milvus collection: {}. Will retry on first insert.", e.getMessage());
        }
    }

    /**
     * 创建Collection（如果不存在）
     */
    public void createCollectionIfNotExists() {
        try {
            milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
            log.info("Milvus collection exists: {}", collectionName);
        } catch (Exception e) {
            log.info("Creating Milvus collection: {}", collectionName);
            try {
                // 使用简化方式创建
                milvusClient.createCollection(CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build());
                log.info("Milvus collection created: {}", collectionName);
            } catch (Exception ex) {
                log.error("Failed to create collection: {}", ex.getMessage());
            }
        }
    }

    /**
     * 插入向量
     */
    public String insertVector(String text, float[] vector, Map<String, String> metadata) {
        try {
            createCollectionIfNotExists();

            List<Float> vectorList = new ArrayList<>();
            for (float v : vector) {
                vectorList.add(v);
            }

            // 简化插入
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vectorList)));

            InsertParam param = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build();

            milvusClient.insert(param);
            return "success";
        } catch (Exception e) {
            log.error("Failed to insert vector: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 搜索向量
     */
    public List<Map<String, Object>> searchVectors(float[] queryVector, int topK) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            List<Float> vectorList = new ArrayList<>();
            for (float v : queryVector) {
                vectorList.add(v);
            }

            SearchParam param = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withVectorFieldName("vector")
                    .withTopK(topK)
                    .withFloatVectors(Collections.singletonList(vectorList))
                    .build();

            milvusClient.search(param);
            log.info("Search completed for topK: {}", topK);

        } catch (Exception e) {
            log.error("Failed to search vectors: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 删除文档的所有向量
     */
    public void deleteByDocumentId(String documentId) {
        try {
            DeleteParam param = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr("document_id == \"" + documentId + "\"")
                    .build();
            milvusClient.delete(param);
        } catch (Exception e) {
            log.error("Failed to delete vectors: {}", e.getMessage());
        }
    }
}
