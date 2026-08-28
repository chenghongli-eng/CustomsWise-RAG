package com.customswise.rag.service;

import com.customswise.rag.dto.RerankScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Rerank 编排服务：在 Milvus 粗召回之后、LLM 之前对候选 chunk 做语义精排。
 *
 * <p>核心职责：
 * <ol>
 *   <li>封装 MiniMax rerank API 调用（{@link MiniMaxService#rerank}）</li>
 *   <li>任何失败（超时 / 401 / 500 / parse error）走降级路径，返回入参原顺序</li>
 *   <li>支持 {@code enabled=false} 全链路跳过 rerank</li>
 * </ol>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankService {

    /** MiniMax API 客户端（rerank / chat / embed 三合一）。 */
    private final MiniMaxService miniMaxService;

    /** 总开关：false 时直接返回原列表（仍按层 1 去重 + 业务加权排序）。 */
    @Value("${rag.rerank.enabled:true}")
    private boolean enabled;

    /**
     * 对入参候选列表按与 query 的相关性重排。
     *
     * <p>降级策略（按优先级）：
     * <ol>
     *   <li>关闭或入参为空 → 返回原列表</li>
     *   <li>MiniMax rerank 返回 null（API 失败）→ 返回原列表</li>
     *   <li>MiniMax rerank 返回的 index 越界 / 缺失 → 返回原列表</li>
     * </ol>
     *
     * @param query         用户问题
     * @param items         候选列表（按"业务加权"已粗排）
     * @param textExtractor 从候选元素抽取纯文本（送 rerank API 用）
     * @return 重排后的列表；失败时返回入参原引用
     */
    public <T> List<T> rerank(String query, List<T> items, Function<T, String> textExtractor) {
        if (!enabled) {
            return items;
        }
        if (items == null || items.isEmpty()) {
            return items;
        }
        try {
            List<String> texts = new ArrayList<>(items.size());
            for (T it : items) {
                texts.add(textExtractor.apply(it));
            }
            List<RerankScore> scores = miniMaxService.rerank(query, texts, items.size());
            if (scores == null || scores.isEmpty()) {
                log.info("[FALLBACK] rerank_fallback=true reason=api_returned_null candidates={}",
                        items.size());
                return items;
            }

            List<T> reranked = new ArrayList<>(scores.size());
            for (RerankScore s : scores) {
                if (s.index() >= 0 && s.index() < items.size()) {
                    reranked.add(items.get(s.index()));
                }
            }
            // 防御：rerank 结果少于原列表时，补齐末尾（按原顺序追加未出现的元素）
            if (reranked.size() < items.size()) {
                List<T> seen = new ArrayList<>(reranked);
                for (T orig : items) {
                    if (!seen.contains(orig)) {
                        reranked.add(orig);
                    }
                }
            }
            return reranked;
        } catch (Exception e) {
            log.error("[FALLBACK] rerank_fallback=true reason={} msg={}",
                    e.getClass().getSimpleName(), e.getMessage());
            return items;
        }
    }
}