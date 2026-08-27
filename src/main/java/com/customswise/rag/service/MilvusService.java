package com.customswise.rag.service;

import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
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
        log.info("MilvusService initialized with collection: {}", collectionName);
    }

    /**
     * 确保Collection存在
     */
    public void ensureCollectionExists() {
        try {
            milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
            log.info("Collection already exists: {}", collectionName);
        } catch (Exception e) {
            log.info("Collection does not exist, creating: {}", collectionName);
            try {
                milvusClient.createCollection(
                        CreateCollectionParam.newBuilder()
                                .withCollectionName(collectionName)
                                .withDimension(VECTOR_DIM)
                                .withDescription("CustomsWise RAG vectors")
                                .build()
                );
                log.info("Collection created: {}", collectionName);
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
            ensureCollectionExists();

            List<Float> vectorList = new ArrayList<>();
            for (float v : vector) {
                vectorList.add(v);
            }

            // 使用动态字段存储额外信息
            Map<String, Object> data = new HashMap<>();
            data.put("vector", vectorList);
            data.put("text", text);
            data.put("document_id", metadata.getOrDefault("document_id", ""));

            List<Map<String, Object>> fields = Collections.singletonList(data);

            InsertParam param = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withData(fields)
                    .build();

            milvusClient.insert(param);
            log.info("Vector inserted for document_id: {}", metadata.getOrDefault("document_id", ""));
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
            ensureCollectionExists();

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

            SearchResults searchResults = milvusClient.search(param);
            log.info("Search completed, results: {}", searchResults.getResults().getRowRecordsCount());

            // 解析结果
            for (int i = 0; i < searchResults.getResults().getRowRecordsCount(); i++) {
                Map<String, Object> item = new HashMap<>();
                item.put("score", searchResults.getResults().getScores(i));
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
            log.info("Deleted vectors for document_id: {}", documentId);
        } catch (Exception e) {
            log.error("Failed to delete vectors: {}", e.getMessage());
        }
    }
}
