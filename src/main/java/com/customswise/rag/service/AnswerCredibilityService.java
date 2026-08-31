package com.customswise.rag.service;

import com.customswise.rag.dto.QaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 答案可信度评估服务：检测 LLM 是否可能产生幻觉（hallucination）。
 *
 * <p>评估方式：
 * <ol>
 *   <li>topSimilarity：rerank 后第一名 chunk 的相似度得分，越高越可信</li>
 *   <li>citationsPresent：LLM 的答案中是否包含了参考文档的编号（documentNumber），
 *       通过简单的字符串包含判断</li>
 * </ol>
 *
 * <p>置信度公式：
 * score = topSimilarityWeight * topSim + citationWeight * citedScore
 * citedScore = 1.0 if LLM cited at least one document number, else 0.0
 */
@Slf4j
@Service
public class AnswerCredibilityService {

    @Value("${credibility.top-similarity-weight:0.7}")
    private float topSimWeight;

    @Value("${credibility.citation-weight:0.3}")
    private float citationWeight;

    @Value("${credibility.low-confidence-threshold:0.4}")
    private float lowThreshold;

    public record CredibilityResult(float score, String level, boolean citationsPresent) {}

    /**
     * 评估答案可信度。
     *
     * @param topSimilarity rerank 后第一名 chunk 的相似度（0.0~1.0）
     * @param references    检索返回的参考文档列表
     * @param answer        LLM 生成的答案原文
     * @return CredibilityResult：评分、等级、是否引用了文档编号
     */
    public CredibilityResult evaluate(float topSimilarity, List<QaResponse.Reference> references, String answer) {
        // 检查答案是否引用了至少一个文档编号
        boolean cited = references != null && references.stream()
                .anyMatch(ref -> ref.getDocumentNumber() != null
                        && !ref.getDocumentNumber().isBlank()
                        && answer.contains(ref.getDocumentNumber()));

        float citationScore = cited ? 1.0f : 0.0f;
        float score = topSimWeight * topSimilarity + citationWeight * citationScore;
        String level = score >= 0.7f ? "high" : score >= lowThreshold ? "medium" : "low";

        log.info("[CREDIBILITY] topSim={} cited={} score={} level={}",
                String.format("%.3f", topSimilarity), cited, String.format("%.3f", score), level);
        return new CredibilityResult(score, level, cited);
    }

    /**
     * 根据置信度等级返回应在答案后追加的免责声明。
     *
     * @param level confidence level
     * @return 追加文本；high 等级返回空串
     */
    public String disclaimer(String level) {
        return switch (level) {
            case "medium" -> "\n\n⚠️ 以上回答基于检索到的政策资料，但相似度有限，仅供参考，不构成法律建议。";
            case "low" -> "\n\n⚠️ 未检索到高度相关的政策资料，以上内容仅供参考。为获得准确信息，建议换个关键词重试，或联系海关专业人员确认。";
            default -> "";
        };
    }
}
