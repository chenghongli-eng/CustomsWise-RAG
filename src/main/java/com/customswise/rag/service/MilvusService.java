package com.customswise.rag.service;

import io.milvus.client.MilvusClient;
import io.milvus.param.collection.CollectionExistsParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.grpc.SearchResult;
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
            boolean exists = milvusClient.hasCollection(
                    CollectionExistsParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build()
            );

            if (!exists) {
                CreateCollectionParam param = CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withDimension(VECTOR_DIM)
                        .build();
                milvusClient.createCollection(param);
                log.info("Milvus collection created: {}", collectionName);
            }
        } catch (Exception e) {
            log.error("Failed to create Milvus collection", e);
        }
    }

    /**
     * 插入向量到Milvus
     */
    public String insertVector(String text, float[] vector, Map<String, String> metadata) {
        try {
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("text", Collections.singletonList(text)));
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
            fields.add(new InsertParam.Field("document_id", Collections.singletonList(metadata.getOrDefault("document_id", ""))));
            fields.add(new InsertParam.Field("chunk_index", Collections.singletonList(metadata.getOrDefault("chunk_index", "0"))));
            fields.add(new InsertParam.Field("status", Collections.singletonList(metadata.getOrDefault("status", "现行"))));

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build();

            var result = milvusClient.insert(insertParam);
            return String.valueOf(result.getSuccIndex().get(0));
        } catch (Exception e) {
            log.error("Failed to insert vector", e);
            return null;
        }
    }

    /**
     * 搜索向量
     */
    public List<Map<String, Object>> searchVectors(float[] queryVector, int topK, String filterStatus) {
        try {
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withVectorFieldName("vector")
                    .withTopK(topK)
                    .withVectors(Collections.singletonList(queryVector))
                    .build();

            SearchResult result = milvusClient.search(searchParam);

            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < result.getRowRecords().size(); i++) {
                Map<String, Object> item = new HashMap<>();
                item.put("score", result.getRowRecords().get(i).getScore());
                item.put("text", result.getFieldsData().get("text").getFieldData().getScalars().getTextData().getData(i));
                item.put("document_id", result.getFieldsData().get("document_id").getFieldData().getScalars().getTextData().getData(i));
                item.put("status", result.getFieldsData().get("status").getFieldData().getScalars().getTextData().getData(i));
                results.add(item);
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to search vectors", e);
            return Collections.emptyList();
        }
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
            log.error("Failed to delete vectors for document: {}", documentId, e);
        }
    }
}
