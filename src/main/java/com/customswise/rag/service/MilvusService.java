package com.customswise.rag.service;

import io.milvus.client.MilvusClient;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.grpc.*;
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

    private static final int VECTOR_DIM = 1536;

    public MilvusService(MilvusClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    @PostConstruct
    public void init() {
        createCollectionIfNotExists();
    }

    /**
     * 创建Collection（如果不存在）
     */
    public void createCollectionIfNotExists() {
        try {
            milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
            log.info("Milvus collection already exists: {}", collectionName);
        } catch (Exception e) {
            try {
                CreateCollectionParam param = CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withDimension(VECTOR_DIM)
                        .build();
                milvusClient.createCollection(param);
                log.info("Milvus collection created: {}", collectionName);
            } catch (Exception ex) {
                log.error("Failed to create Milvus collection", ex);
            }
        }
    }

    /**
     * 插入向量
     */
    public String insertVector(String text, float[] vector, Map<String, String> metadata) {
        try {
            List<Float> vectorList = new ArrayList<>();
            for (float v : vector) {
                vectorList.add(v);
            }

            InsertParam param = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFloatVector("vector", Collections.singletonList(vectorList))
                    .addFieldValue("text", text)
                    .addFieldValue("document_id", metadata.getOrDefault("document_id", ""))
                    .addFieldValue("chunk_index", Integer.parseInt(metadata.getOrDefault("chunk_index", "0")))
                    .addFieldValue("status", metadata.getOrDefault("status", "现行"))
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

            SearchResult result = milvusClient.search(param);

            // 解析结果
            for (int i = 0; i < result.getRowRecords().size(); i++) {
                Map<String, Object> item = new HashMap<>();
                item.put("score", result.getRowRecords().get(i).getScore());
                // 其他字段解析...
                results.add(item);
            }
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
