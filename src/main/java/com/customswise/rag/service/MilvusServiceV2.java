package com.customswise.rag.service;

import io.milvus.client.MilvusClient;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.QueryParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Milvus 补充封装：在 v1 {@link MilvusService} 之上提供按表达式操作的便捷方法
 * （countByFilter / deleteByDocumentId）。
 *
 * <p>设计动机：
 * <ol>
 *   <li>milvus-sdk-java 2.4.5 的 v2 API 不支持 addCollectionField 在线加字段，
 *       所以"在线 schema 演进"在该 SDK 版本下不可行——只能在服务端升级 SDK 后再做。</li>
 *   <li>当前根治数据丢失的方案：{@link MilvusService#init()} 不再 drop+recreate，
 *       任何缺失向量由 {@link MigrationService} 从 file_path 重建。</li>
 *   <li>本类仅为 MigrationService 提供按表达式统计 / 拉取 ID 的便利方法，
 *       不替代 v1 的 CRUD，调用方应继续用 MilvusService 做插入/搜索/删除。</li>
 * </ol>
 */
@Slf4j
@Service
public class MilvusServiceV2 {

    /** Milvus v1 客户端 Bean（由 MilvusConfig 配置）。 */
    private final MilvusClient milvusClient;

    /** 委托 MilvusService 的可用性状态 */
    private final MilvusService milvusService;

    /** Milvus 集合名（从 application.yml 的 milvus.collection-name 读取）。 */
    @Value("${milvus.collection-name}")
    private String collectionName;

    @Autowired
    public MilvusServiceV2(@Autowired(required = false) MilvusClient milvusClient, MilvusService milvusService) {
        this.milvusClient = milvusClient;
        this.milvusService = milvusService;
    }

    private boolean isAvailable() {
        return milvusService.isAvailable();
    }

    /**
     * 按 Milvus 过滤表达式统计行数。
     *
     * <p>注意：Milvus 2.4 不允许 count(*) 配合 withLimit，会报 error 1100
     *
     * @param filter Milvus 过滤表达式，例如 {@code "document_id == \"45\""}
     * @return 匹配行数；查询失败时返回 {@code -1L}，调用方据此跳过对账
     */
    public long countByFilter(String filter) {
        if (!isAvailable()) {
            return -1L;
        }
        try {
            QueryParam param = QueryParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(filter)
                    .withOutFields(Collections.singletonList("count(*)"))
                    .build();
            R<io.milvus.grpc.QueryResults> resp = milvusClient.query(param);
            if (resp.getStatus() != R.Status.Success.getCode()) {
                log.warn("[MILVUS] countByFilter({}) status={}", filter, resp.getStatus());
                return -1L;
            }
            var fieldsData = resp.getData().getFieldsDataList();
            for (var fd : fieldsData) {
                if ("count(*)" .equals(fd.getFieldName()) && !fd.getScalars().getLongData().getDataList().isEmpty()) {
                    return fd.getScalars().getLongData().getDataList().get(0);
                }
            }
            return 0L;
        } catch (Exception e) {
            log.warn("[MILVUS] countByFilter({}) failed: {}", filter, e.getMessage());
            return -1L;
        }
    }

    /**
     * 按 document_id 表达式删除该文档的全部向量。
     *
     * <p>用于 MigrationService 在重新解析前先清空该 doc 的旧向量，
     * 避免新旧 chunk 互相覆盖导致重复。
     *
     * @param documentId PolicyDocument.id（字符串形式，参与 Milvus 表达式拼接）
     */
    public void deleteByDocumentId(String documentId) {
        if (!isAvailable()) {
            log.warn("[MILVUS] Milvus 不可用，跳过 deleteByDocumentId，documentId={}", documentId);
            return;
        }
        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr("document_id == \"" + documentId + "\"")
                .build();
        milvusClient.delete(param);
        log.info("[MILVUS] deleted by document_id={}", documentId);
    }
}
