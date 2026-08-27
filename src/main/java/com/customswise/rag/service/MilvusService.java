package com.customswise.rag.service;

import io.milvus.client.MilvusClient;
import io.milvus.param.*;
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

    public MilvusService(MilvusClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    @PostConstruct
    public void init() {
        log.info("MilvusService initialized, collection: {}", collectionName);
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

            InsertParam.Field textField = new InsertParam.Field("text", Collections.singletonList(text));
            InsertParam.Field docIdField = new InsertParam.Field("document_id", Collections.singletonList(metadata.getOrDefault("document_id", "")));
            InsertParam.Field chunkField = new InsertParam.Field("chunk_index", Collections.singletonList(Integer.parseInt(metadata.getOrDefault("chunk_index", "0"))));
            InsertParam.Field statusField = new InsertParam.Field("status", Collections.singletonList(metadata.getOrDefault("status", "现行")));
            InsertParam.Field vectorField = new InsertParam.Field("vector", Collections.singletonList(vectorList));

            InsertParam param = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(Arrays.asList(textField, docIdField, chunkField, statusField, vectorField))
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
