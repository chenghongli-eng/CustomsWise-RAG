package com.customswise.rag.dto;

/**
 * Rerank API 单条返回：候选文档的下标 + rerank 分数。
 *
 * <p>{@code index} 是入参 {@code documents[]} 中的位置（0-based），不是全局排序后的位置。
 * 调用方按 {@code score} 降序排序后用 {@code index} 取原 list 中对应元素。
 *
 * @param index documents 数组中的下标
 * @param score rerank 相关性分数（数值越大越相关，具体范围依 rerank 模型而异）
 */
public record RerankScore(int index, float score) {
}