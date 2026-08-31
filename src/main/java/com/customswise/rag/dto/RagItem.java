package com.customswise.rag.dto;

import java.util.Map;

/**
 * RAG 召回阶段的统一数据载体。
 *
 * <p>Milvus 返回 {@code List<Map<String,Object>>}，重排链路各步（RAGService 内层 1 阈值/去重、
 * 层 3 rerank）需要结构化访问并最终转回 Map 喂给 context 构建。引入本 record 把
 * 字段固化下来，便于编译期检查与重排计算。
 *
 * <ul>
 *   <li>{@code text}：chunk 原文（来自 Milvus text 字段）</li>
 *   <li>{@code documentId}：来自 Milvus document_id 字段，String 形式避免 Long.parseLong 重复</li>
 *   <li>{@code status}：现行 / 已废止（来自 Milvus status 字段；服务端过滤后默认现行）</li>
 *   <li>{@code similarity}：归一化到 [0, 1]，higher = more relevant</li>
 *   <li>{@code anchor} / {@code chunkIndex}：来自 Milvus 元数据，可选</li>
 * </ul>
 */
public record RagItem(
        String text,
        String documentId,
        String status,
        float similarity,
        String anchor,
        String chunkIndex
) {
    /**
     * Milvus hybrid (RRF, k=60) score 的理论上限：dense + sparse 两路同时 rank=1。
     * 用于把 RRF 分数线性归一化到 [0, 1]，保留"higher = more relevant"语义，
     * 让 threshold filter / 排序 / 可信度评估保持原有口径。
     */
    static final float MAX_HYBRID_SCORE = 2f / 61f;

    /**
     * 从 Milvus 返回的 Map 构造 RagItem，自动做 RRF 分数归一化（[0, ~0.0328] → [0, 1]）。
     *
     * <p>注：旧 RAGService 链路是 dense-only L2 distance，用 1/(1+l2) 转 similarity（lower-is-better）；
     * 切到 hybridSearch 后 score 是 RRF 分数（higher-is-better），不能再用 1/(1+x) 否则排序会反。
     * 这里用线性归一化保持向后兼容：下游 threshold / sort / credibility 公式不变。
     *
     * @param raw Milvus 返回的键值对，至少含 text/document_id/status/score
     * @return 结构化的 RagItem
     */
    public static RagItem fromMap(Map<String, Object> raw) {
        Object scoreObj = raw.get("score");
        float score = scoreObj instanceof Number ? ((Number) scoreObj).floatValue() : 0f;
        float similarity = Math.min(1f, score / MAX_HYBRID_SCORE);
        return new RagItem(
                (String) raw.getOrDefault("text", ""),
                String.valueOf(raw.getOrDefault("document_id", "")),
                (String) raw.getOrDefault("status", "现行"),
                similarity,
                (String) raw.getOrDefault("anchor", ""),
                String.valueOf(raw.getOrDefault("chunk_index", ""))
        );
    }

    /**
     * 转回 Milvus 原始 Map 形态，供 {@code RAGService} 既有 context 构建复用。
     *
     * <p>只放下游需要的字段；score 反推为 RRF 原值以保持等价。
     */
    public Map<String, Object> toMap() {
        return Map.of(
                "text", text == null ? "" : text,
                "document_id", documentId == null ? "" : documentId,
                "status", status == null ? "现行" : status,
                "score", similarity * MAX_HYBRID_SCORE,
                "similarity", similarity,
                "anchor", anchor == null ? "" : anchor,
                "chunk_index", chunkIndex == null ? "" : chunkIndex
        );
    }
}