package com.customswise.rag.service;

import io.milvus.client.MilvusClient;
import io.milvus.grpc.DataType;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** Milvus 是否可用 */
    private volatile boolean available = false;

    public MilvusService(@Autowired(required = false) MilvusClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    public boolean isAvailable() {
        return available;
    }

    @PostConstruct
    public void init() {
        if (milvusClient == null) {
            log.warn("⚠️ Milvus 不可用（连接失败），项目将以降级模式运行（仅 PG 功能可用，RAG 功能暂停）");
            return;
        }
        try {
            milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
            // 已存在：保留数据，不再 drop+recreate
            // 任何字段缺失/不兼容由 MigrationService 从 file_path 重建向量修复
            if (!hasStatusField()) {
                log.warn("Collection {} 缺 status 字段，将保留旧 schema；status 过滤相关查询可能不准。"
                        + "向量补齐由 MigrationService 启动对账执行", collectionName);
            } else {
                log.info("Collection已存在: {}（schema 完整，保留数据）", collectionName);
            }
            available = true;
        } catch (Exception e) {
            log.info("Collection不存在，开始创建: {}", collectionName);
            createCollection();
        }
    }

    private boolean hasStatusField() {
        try {
            var resp = milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
            if (resp.getStatus() != R.Status.Success.getCode()) {
                return false;
            }
            for (io.milvus.grpc.FieldSchema f : resp.getData().getSchema().getFieldsList()) {
                if ("status".equals(f.getName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("检查status字段失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 创建Collection（仅在没有时调用，不删除已有数据）
     */
    public void createCollection() {
        try {
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withDescription("海关政策文档向量库")
                    .withFieldTypes(Arrays.asList(
                            FieldType.newBuilder()
                                    .withName("id")
                                    .withDataType(DataType.Int64)
                                    .withPrimaryKey(true)
                                    .withAutoID(true)
                                    .build(),
                            FieldType.newBuilder()
                                    .withName("vector")
                                    .withDataType(DataType.FloatVector)
                                    .withDimension(1536)
                                    .build(),
                            FieldType.newBuilder()
                                    .withName("text")
                                    .withDataType(DataType.VarChar)
                                    .withMaxLength(65535)
                                    .build(),
                            FieldType.newBuilder()
                                    .withName("document_id")
                                    .withDataType(DataType.VarChar)
                                    .withMaxLength(100)
                                    .build(),
                            FieldType.newBuilder()
                                    .withName("status")
                                    .withDataType(DataType.VarChar)
                                    .withMaxLength(20)
                                    .build()
                    ))
                    .build();

            milvusClient.createCollection(createParam);
            log.info("✅ 已创建collection: {}", collectionName);

            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("vector")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.L2)
                    .build();
            milvusClient.createIndex(indexParam);
            log.info("✅ 已创建索引");

            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
            log.info("✅ 已加载collection到内存");

        } catch (Exception e) {
            log.error("❌ 初始化collection失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 确保Collection存在
     */
    public void ensureCollectionExists() {
        try {
            milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
            log.info("Collection exists: {}", collectionName);
        } catch (Exception e) {
            log.warn("Collection不存在，将创建: {}", collectionName);
            createCollection();
        }
    }

    /**
     * 插入向量
     */
    public String insertVector(String text, float[] vector, Map<String, String> metadata) {
        if (!isAvailable()) {
            log.warn("Milvus 不可用，跳过向量插入，document_id={}", metadata.getOrDefault("document_id", ""));
            return null;
        }
        try {
            ensureCollectionExists();

            List<Float> vectorList = new ArrayList<>();
            for (float v : vector) {
                vectorList.add(v);
            }

            // 使用Fields方式构建插入参数
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("vector", Collections.singletonList(vectorList)));
            fields.add(new InsertParam.Field("text", Collections.singletonList(text)));
            fields.add(new InsertParam.Field("document_id", Collections.singletonList(metadata.getOrDefault("document_id", ""))));
            fields.add(new InsertParam.Field("status", Collections.singletonList(metadata.getOrDefault("status", "现行"))));

            InsertParam param = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build();

            milvusClient.insert(param);
            log.info("✅ 向量插入成功，document_id: {}, status: {}, text长度: {}",
                    metadata.getOrDefault("document_id", ""), metadata.getOrDefault("status", "现行"), text.length());
            return "success";
        } catch (Exception e) {
            log.error("❌ 插入向量失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 搜索向量，支持服务端 expr 过滤。
     *
     * <p>典型 expr 形式：
     * <ul>
     *   <li>{@code "status == \"现行\""} —— 只召回现行政策</li>
     *   <li>{@code "document_id in [\"1\",\"2\"]"} —— 指定文档集合</li>
     * </ul>
     *
     * <p>expr 为 null 或空白则不过滤。
     *
     * @param queryVector 查询向量（与 Milvus 集合同维度 1536）
     * @param topK 最大返回条数
     * @param expr Milvus 过滤表达式；null/空表示不过滤
     * @return 按 score 升序排列的命中列表（L2 距离；相似度由调用方按 1/(1+l2) 换算）
     */
    public List<Map<String, Object>> searchVectors(float[] queryVector, int topK, String expr) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            ensureCollectionExists();

            List<Float> vectorList = new ArrayList<>();
            for (float v : queryVector) {
                vectorList.add(v);
            }

            SearchParam.Builder builder = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withVectorFieldName("vector")
                    .withTopK(topK)
                    .withFloatVectors(Collections.singletonList(vectorList))
                    .withOutFields(Arrays.asList("text", "document_id", "status"));
            if (expr != null && !expr.isBlank()) {
                builder.withExpr(expr);
            }
            SearchParam param = builder.build();

            var resp = milvusClient.search(param);
            if (resp.getStatus() != R.Status.Success.getCode()) {
                log.error("搜索失败: {}", resp.getMessage());
                return results;
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());

            List<?> textData = wrapper.getFieldWrapper("text").getFieldData();
            List<?> docIdData = wrapper.getFieldWrapper("document_id").getFieldData();
            List<?> statusData = wrapper.getFieldWrapper("status").getFieldData();
            log.info("搜索完成，textData.size={}, docIdData.size={}, statusData.size={}",
                    textData.size(), docIdData.size(), statusData.size());

            // 通过 IDScore 补全 score
            List<SearchResultsWrapper.IDScore> idScores = null;
            try {
                idScores = wrapper.getIDScore(0);
            } catch (Exception e) {
                log.warn("获取score失败: {}", e.getMessage());
            }

            int count = textData.size();
            for (int i = 0; i < count; i++) {
                Map<String, Object> result = new HashMap<>();
                Object textVal = textData.get(i);
                Object docIdVal = docIdData.get(i);
                Object statusVal = statusData.get(i);
                result.put("text", textVal != null ? textVal.toString() : "");
                result.put("document_id", docIdVal != null ? docIdVal.toString() : "");
                result.put("status", statusVal != null ? statusVal.toString() : "现行");
                float score = (idScores != null && i < idScores.size()) ? idScores.get(i).getScore() : 0.0f;
                result.put("score", score);
                results.add(result);
            }

        } catch (Exception e) {
            log.error("❌ 搜索失败: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 删除文档的所有向量
     */
    public void deleteByDocumentId(String documentId) {
        if (!isAvailable()) {
            log.warn("Milvus 不可用，跳过向量删除，document_id={}", documentId);
            return;
        }
        try {
            DeleteParam param = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr("document_id == \"" + documentId + "\"")
                    .build();
            milvusClient.delete(param);
            log.info("已删除document_id: {}的向量", documentId);
        } catch (Exception e) {
            log.error("删除向量失败: {}", e.getMessage());
        }
    }
}
