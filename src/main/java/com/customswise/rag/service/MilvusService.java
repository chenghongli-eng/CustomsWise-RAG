package com.customswise.rag.service;

import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.*;
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
            // 检查collection是否存在
            milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
            log.info("Milvus collection already exists: {}", collectionName);
        } catch (Exception e) {
            log.info("Collection not found, creating: {}", collectionName);
            try {
                // 创建Collection
                CreateCollectionParam param = CreateCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withDimension(VECTOR_DIM)
                        .build();

                milvusClient.createCollection(param);
                log.info("Milvus collection created: {}", collectionName);

                // 创建索引
                io.milvus.param.index.IndexParam indexParam = io.milvus.param.index.IndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName("vector")
                        .withIndexType(io.milvus.grpc.IndexType.IVF_FLAT)
                        .withMetricType(MetricType.L2)
                        .build();

                milvusClient.createIndex(indexParam);
                log.info("Milvus index created for collection: {}", collectionName);

            } catch (Exception ex) {
                log.error("Failed to create Milvus collection: {}", ex.getMessage(), ex);
            }
        }
    }

    /**
     * 插入向量
     */
    public String insertVector(String text, float[] vector, Map<String, String> metadata) {
        try {
            // 确保collection存在
            createCollectionIfNotExists();

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
            log.error("Failed to insert vector: {}", e.getMessage(), e);
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
                    .withOutputFields(Arrays.asList("text", "document_id", "status"))
                    .build();

            SearchResult searchResult = milvusClient.search(param);

            // 解析搜索结果
            if (searchResult != null && searchResult.getResults() != null) {
                int numResults = searchResult.getResults().getRowRecordsCount();
                for (int i = 0; i < numResults; i++) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("score", searchResult.getResults().getScores(i));
                    results.add(item);
                }
            }

        } catch (Exception e) {
            log.error("Failed to search vectors: {}", e.getMessage(), e);
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
            log.error("Failed to delete vectors: {}", e.getMessage(), e);
        }
    }
}
