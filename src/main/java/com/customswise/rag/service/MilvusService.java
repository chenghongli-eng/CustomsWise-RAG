package com.customswise.rag.service;

import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Milvus v2 API 封装。
 *
 * <p>collection schema:
 * <ul>
 *   <li>id (Int64, auto_id, primary_key)</li>
 *   <li>vector (FloatVector, dim=1536, IVF_FLAT + L2)</li>
 *   <li>text (VarChar, max_length=65535, enable_analyzer=true)</li>
 *   <li>document_id (VarChar, max_length=100)</li>
 *   <li>status (VarChar, max_length=20)</li>
 *   <li>sparse (SparseFloatVector, 由 text_bm25 Function 自动产出)</li>
 * </ul>
 *
 * <p>Function {@code text_bm25}：BM25，输入 text 字段，输出 sparse 字段。插入时服务端自动算 BM25 sparse vec；
 * hybridSearch 时用 RRFRanker 融合 dense (vector) 与 sparse (sparse) 两路召回。
 */
@Slf4j
@Service
public class MilvusService {

    /** Function 输出的 sparse vector 字段名（与 createCollection 的 outputFieldNames 一致） */
    public static final String SPARSE_FIELD = "sparse";

    /** Function 名 */
    public static final String BM25_FUNCTION_NAME = "text_bm25";

    private final MilvusClientV2 milvusClient;

    @Value("${milvus.collection-name}")
    private String collectionName;

    /**
     * schema drift（缺 status 字段或 BM25 Function）时是否自动 drop+recreate collection。
     * 默认 false：只警告不重建，避免重启误删存量向量。
     * 一次性升级到含 BM25 Function 的新 schema 时设为 true，启动后由 MigrationService 重灌。
     */
    @Value("${migration.auto-recreate-collection:false}")
    private boolean autoRecreateOnSchemaDrift;

    /** Milvus 是否可用 */
    private volatile boolean available = false;

    public MilvusService(@Autowired(required = false) MilvusClientV2 milvusClient) {
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
            DescribeCollectionResp resp = describeCollection();
            // 已存在：保留数据；schema drift 时按 yml 决定是否自动 drop+recreate
            boolean hasStatus = hasStatusField(resp);
            boolean hasBm25 = hasBm25Function(resp);
            if (!hasStatus || !hasBm25) {
                if (autoRecreateOnSchemaDrift) {
                    log.warn("Collection {} schema drift detected (status={}, bm25Fn={}), "
                            + "auto-recreating per migration.auto-recreate-collection=true; "
                            + "数据将由 MigrationService 从 PG file_path 重灌",
                            collectionName, hasStatus, hasBm25);
                    available = false;
                    recreateCollection();
                    // recreateCollection() 内部 createCollection() 会 setAvailable(true) + loadCollection
                } else {
                    if (!hasStatus) {
                        log.warn("Collection {} 缺 status 字段，将保留旧 schema；status 过滤相关查询可能不准。"
                                + "向量补齐由 MigrationService 启动对账执行。"
                                + "如需完整新 schema 可临时设 migration.auto-recreate-collection=true",
                                collectionName);
                    } else {
                        log.warn("Collection {} 缺 BM25 Function，sparse 召回会失败；"
                                + "如需 BM25 请临时开 migration.auto-recreate-collection=true 触发重建",
                                collectionName);
                    }
                    // 仍尝试 load：dense 召回至少能用
                    ensureLoaded();
                    available = true;
                }
            } else {
                log.info("Collection已存在: {}（schema 完整，含 BM25 Function，保留数据）", collectionName);
                // 自愈：早期版本 createCollection 时把 dense+sparse 一起塞进 indexParams，
                // 但 Milvus 2.5 SDK 只接受 dense，sparse 被静默丢弃。detect 一下并补建。
                if (!hasSparseIndex()) {
                    log.warn("Collection {} sparse 字段缺索引，自动补建 SPARSE_INVERTED_INDEX",
                            collectionName);
                    buildSparseIndex();
                }
                // schema 完整但 Milvus 重启 / collection 被 unload 后，query/search 会报 "collection not loaded"
                ensureLoaded();
                available = true;
            }
        } catch (Exception e) {
            log.info("Collection不存在，开始创建: {}", collectionName);
            createCollection();
            // 创建后再尝试 describe（createCollection 内已 setAvailable(true)）
        }
    }

    private DescribeCollectionResp describeCollection() {
        return milvusClient.describeCollection(DescribeCollectionReq.builder()
                .collectionName(collectionName)
                .build());
    }

    private boolean hasStatusField(DescribeCollectionResp resp) {
        if (resp == null || resp.getCollectionSchema() == null) {
            return false;
        }
        for (CreateCollectionReq.FieldSchema f : resp.getCollectionSchema().getFieldSchemaList()) {
            if ("status".equals(f.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBm25Function(DescribeCollectionResp resp) {
        if (resp == null || resp.getCollectionSchema() == null) {
            return false;
        }
        for (CreateCollectionReq.Function fn : resp.getCollectionSchema().getFunctionList()) {
            if (BM25_FUNCTION_NAME.equals(fn.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检测 sparse 字段是否已有索引。describeIndex 抛异常（通常 = 字段无索引）返回 false。
     */
    private boolean hasSparseIndex() {
        try {
            var resp = milvusClient.describeIndex(DescribeIndexReq.builder()
                    .collectionName(collectionName)
                    .fieldName(SPARSE_FIELD)
                    .build());
            return resp.getIndexDescriptions() != null && !resp.getIndexDescriptions().isEmpty();
        } catch (Exception e) {
            log.debug("describeIndex(sparse) 异常，按无索引处理: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 给 sparse 字段单独建索引。失败仅 log，不抛——上层 loadCollection 会自然报错。
     */
    private void buildSparseIndex() {
        try {
            IndexParam sparseIndex = IndexParam.builder()
                    .fieldName(SPARSE_FIELD)
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .build();
            milvusClient.createIndex(CreateIndexReq.builder()
                    .collectionName(collectionName)
                    .indexParams(Collections.singletonList(sparseIndex))
                    .build());
            log.info("✅ sparse 索引补建完成");
        } catch (Exception e) {
            log.error("❌ sparse 索引补建失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 创建 Collection（仅在没有时调用，不删除已有数据）。
     * schema 含 dense vector + text(analyzer) + BM25 Function 输出 sparse。
     */
    public void createCollection() {
        try {
            CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                    .name("id")
                    .dataType(DataType.Int64)
                    .isPrimaryKey(true)
                    .autoID(true)
                    .build();
            CreateCollectionReq.FieldSchema vectorField = CreateCollectionReq.FieldSchema.builder()
                    .name("vector")
                    .dataType(DataType.FloatVector)
                    .dimension(1536)
                    .build();
            CreateCollectionReq.FieldSchema textField = CreateCollectionReq.FieldSchema.builder()
                    .name("text")
                    .dataType(DataType.VarChar)
                    .maxLength(65535)
                    .enableAnalyzer(true)
                    // BM25 sparse 召回按 text 字段的 analyzer 分词。政策文档全中文，
                    // standard 只能按空白切分 → 中文整句算一个 token 召回率=0；
                    // chinese analyzer 用 jieba 切词才匹配得上。
                    .analyzerParams(Map.of("type", "chinese"))
                    .build();
            CreateCollectionReq.FieldSchema docIdField = CreateCollectionReq.FieldSchema.builder()
                    .name("document_id")
                    .dataType(DataType.VarChar)
                    .maxLength(100)
                    .build();
            CreateCollectionReq.FieldSchema statusField = CreateCollectionReq.FieldSchema.builder()
                    .name("status")
                    .dataType(DataType.VarChar)
                    .maxLength(20)
                    .build();
            // BM25 Function 的输出字段：必须在 schema 里声明一个 SparseFloatVector，
            // 否则 createCollection 报 "function output field not found: sparse"
            CreateCollectionReq.FieldSchema sparseField = CreateCollectionReq.FieldSchema.builder()
                    .name(SPARSE_FIELD)
                    .dataType(DataType.SparseFloatVector)
                    .build();

            // BM25 Function：text → sparse vector
            CreateCollectionReq.Function bm25Fn = CreateCollectionReq.Function.builder()
                    .name(BM25_FUNCTION_NAME)
                    .functionType(FunctionType.BM25)
                    .inputFieldNames(Collections.singletonList("text"))
                    .outputFieldNames(Collections.singletonList(SPARSE_FIELD))
                    .build();

            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                    .fieldSchemaList(List.of(idField, vectorField, textField, docIdField, statusField, sparseField))
                    .functionList(Collections.singletonList(bm25Fn))
                    .build();

            // dense vector 索引（IVF_FLAT + L2，保持与原 schema 一致）
            IndexParam vectorIndex = IndexParam.builder()
                    .fieldName("vector")
                    .indexType(IndexParam.IndexType.IVF_FLAT)
                    .metricType(IndexParam.MetricType.L2)
                    .extraParams(Map.of("nlist", 64))
                    .build();
            // sparse vector 索引（SPARSE_INVERTED_INDEX + BM25 metric）。
            // BM25 function 的 output field 必须用 BM25 metric type，不能用 IP。
            // 不放进 createCollection 的 indexParams —— 实测 Milvus 2.5 SDK 在 createCollection
            // 阶段只接受 dense 索引，sparse 索引被静默丢弃，loadCollection 时会报
            // "there is no vector index on field: [sparse]"。所以 dense 走 createCollection，
            // sparse 单独 createIndex 建。
            IndexParam sparseIndex = IndexParam.builder()
                    .fieldName(SPARSE_FIELD)
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .build();

            milvusClient.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .description("海关政策文档向量库（dense + BM25 sparse hybrid）")
                    .collectionSchema(schema)
                    .indexParams(Collections.singletonList(vectorIndex))
                    .build());
            log.info("✅ 已创建collection: {}（含 BM25 Function）", collectionName);

            // sparse 索引单独建（createCollection 阶段不接受 sparse IndexParam）
            milvusClient.createIndex(CreateIndexReq.builder()
                    .collectionName(collectionName)
                    .indexParams(Collections.singletonList(sparseIndex))
                    .build());
            log.info("✅ 已为 sparse 字段建立 SPARSE_INVERTED_INDEX 索引");

            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            log.info("✅ 已加载collection到内存");

            available = true;
        } catch (Exception e) {
            log.error("❌ 初始化collection失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 确保Collection存在
     */
    public void ensureCollectionExists() {
        try {
            describeCollection();
        } catch (Exception e) {
            log.warn("Collection不存在，将创建: {}", collectionName);
            createCollection();
        }
    }

    /**
     * 确保 Collection 已加载到内存。Milvus 重启或显式 release 后 collection 会变 not loaded，
     * 而 describeCollection 只查元数据不会触发加载；只有 query/search 才会失败报 "collection not loaded"。
     * 该方法幂等（对已加载的 collection 调用 loadCollection 无副作用），失败只 warn 不抛，
     * 让上层查询自然暴露问题。
     */
    private void ensureLoaded() {
        try {
            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            log.info("✅ 已加载collection到内存");
        } catch (Exception e) {
            log.warn("⚠️ 加载collection失败（不影响启动，查询时会暴露问题）: {}", e.getMessage());
        }
    }

    /**
     * 插入向量（v2 InsertReq：data 是 List<JsonObject>）。
     * Function 是 schema 级配置，insert 时服务端会自动算 sparse vector，无需客户端处理。
     */
    public String insertVector(String text, float[] vector, Map<String, String> metadata) {
        if (!isAvailable()) {
            log.warn("Milvus 不可用，跳过向量插入，document_id={}", metadata.getOrDefault("document_id", ""));
            return null;
        }
        try {
            ensureCollectionExists();

            JsonObject row = new JsonObject();
            row.addProperty("text", text);
            row.addProperty("document_id", metadata.getOrDefault("document_id", ""));
            row.addProperty("status", metadata.getOrDefault("status", "现行"));
            // dense vector 用 JSON 数组
            com.google.gson.JsonArray vecArr = new com.google.gson.JsonArray();
            for (float v : vector) {
                vecArr.add(v);
            }
            row.add("vector", vecArr);

            InsertReq param = InsertReq.builder()
                    .collectionName(collectionName)
                    .data(Collections.singletonList(row))
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
     * hybrid 检索：dense (vector, L2) + sparse (BM25 on text) → RRFRanker 融合。
     *
     * <p>expr 同时作用于两路召回（如 {@code "status == \"现行\""}）。
     *
     * @param queryVector  dense 查询向量（与 collection 同 dim 1536）
     * @param queryText    sparse 召回用的查询原文（服务端用 BM25 tokenize）
     * @param topK         单路 topK + 融合后 topK
     * @param expr         Milvus 过滤表达式；null/空表示不过滤
     * @return 按融合分数降序排列的命中列表（score 为 RRF 分数）
     */
    public List<Map<String, Object>> hybridSearch(float[] queryVector, String queryText, int topK, String expr) {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            ensureCollectionExists();

            boolean hasFilter = expr != null && !expr.isBlank();

            // dense (vector, L2)
            AnnSearchReq.AnnSearchReqBuilder denseBuilder = AnnSearchReq.builder()
                    .vectorFieldName("vector")
                    .topK(topK)
                    .vectors(Collections.singletonList(new FloatVec(queryVector)))
                    .metricType(IndexParam.MetricType.L2)
                    .params("{\"nprobe\": 16}");
            if (hasFilter) {
                denseBuilder.expr(expr);
            }
            AnnSearchReq denseReq = denseBuilder.build();

            // sparse (BM25 via Function on text)
            AnnSearchReq.AnnSearchReqBuilder sparseBuilder = AnnSearchReq.builder()
                    .vectorFieldName(SPARSE_FIELD)
                    .topK(topK)
                    .vectors(Collections.singletonList(new EmbeddedText(queryText)));
            if (hasFilter) {
                sparseBuilder.expr(expr);
            }
            AnnSearchReq sparseReq = sparseBuilder.build();

            HybridSearchReq searchReq = HybridSearchReq.builder()
                    .collectionName(collectionName)
                    .searchRequests(List.of(denseReq, sparseReq))
                    .ranker(new RRFRanker(60))
                    .topK(topK)
                    .outFields(List.of("text", "document_id", "status"))
                    .build();

            SearchResp resp = milvusClient.hybridSearch(searchReq);
            List<List<SearchResp.SearchResult>> hits = resp.getSearchResults();
            if (hits == null || hits.isEmpty()) {
                log.warn("hybridSearch 返回空结果");
                return results;
            }
            for (SearchResp.SearchResult hit : hits.get(0)) {
                Map<String, Object> row = new HashMap<>();
                Map<String, Object> entity = hit.getEntity();
                row.put("text", entity.get("text") != null ? entity.get("text").toString() : "");
                row.put("document_id", entity.get("document_id") != null ? entity.get("document_id").toString() : "");
                row.put("status", entity.get("status") != null ? entity.get("status").toString() : "现行");
                row.put("score", hit.getScore() != null ? hit.getScore() : 0.0f);
                results.add(row);
            }
            log.info("hybridSearch 完成，hits={}", results.size());

        } catch (Exception e) {
            log.error("❌ hybridSearch 失败: {}", e.getMessage(), e);
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
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter("document_id == \"" + documentId + "\"")
                    .build());
            log.info("已删除document_id: {}的向量", documentId);
        } catch (Exception e) {
            log.error("删除向量失败: {}", e.getMessage());
        }
    }

    /**
     * 按 Milvus 过滤表达式统计行数（用 v2 QueryReq 替代老的 R<QueryResults>）。
     *
     * @param filter Milvus 过滤表达式，例如 {@code "document_id == \"45\""}
     * @return 匹配行数；查询失败时返回 {@code -1L}，调用方据此跳过对账
     */
    public long countByFilter(String filter) {
        if (!isAvailable()) {
            return -1L;
        }
        try {
            QueryResp resp = milvusClient.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter(filter)
                    .outputFields(Collections.singletonList("count(*)"))
                    .build());
            if (resp.getQueryResults() == null || resp.getQueryResults().isEmpty()) {
                return 0L;
            }
            Map<String, Object> entity = resp.getQueryResults().get(0).getEntity();
            Object count = entity.get("count(*)");
            return count instanceof Number ? ((Number) count).longValue() : 0L;
        } catch (Exception e) {
            log.warn("[MILVUS] countByFilter({}) failed: {}", filter, e.getMessage());
            return -1L;
        }
    }

    /**
     * 重建 collection：先 drop 再 create。用于 BM25 Function 缺失时的运维操作。
     * 重灌数据由 MigrationService 负责。
     */
    public void recreateCollection() {
        try {
            milvusClient.dropCollection(DropCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            log.warn("已 drop collection {}", collectionName);
            available = false;
            createCollection();
        } catch (Exception e) {
            log.error("recreateCollection 失败: {}", e.getMessage(), e);
        }
    }
}