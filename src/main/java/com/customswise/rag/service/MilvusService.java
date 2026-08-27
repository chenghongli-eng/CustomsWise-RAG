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
        try {
            createCollectionIfNotExists();
        } catch (Exception e) {
            log.warn("Failed to initialize Milvus collection: {}", e.getMessage());
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
                // 构建schema
                FieldType vectorField = FieldType.newBuilder()
                        .withName("vector")
                        .withDataType(DataType.FloatVector)
                        .withDimension(VECTOR_DIM)
                        .build();

                FieldType textField = FieldType.newBuilder()
                        .withName("text")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(65535)
                        .build();

                FieldType docIdField = FieldType.newBuilder()
                        .withName("document_id")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(100)
                        .build();

                CreateCollectionParam param = CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withDescription("CustomsWise RAG document vectors")
                        .withFieldTypes(Arrays.asList(vectorField, textField, docIdField))
                        .build();

                milvusClient.createCollection(param);
                log.info("Milvus collection created successfully: {}", collectionName);
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

            List<InsertParam.Field> fields = Arrays.asList(
                    new InsertParam.Field("vector", Collections.singletonList(vectorList)),
                    new InsertParam.Field("text", Collections.singletonList(text)),
                    new InsertParam.Field("document_id", Collections.singletonList(metadata.getOrDefault("document_id", "")))
            );

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
