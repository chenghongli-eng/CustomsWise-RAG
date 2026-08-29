package com.customswise.rag.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Data
@Entity
@Table(name = "qa_history")
public class QaHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "user_query", columnDefinition = "TEXT", nullable = false)
    private String userQuery;

    @Column(name = "user_conditions", columnDefinition = "TEXT")
    private String userConditions;

    @Column(name = "ai_response", columnDefinition = "TEXT")
    private String aiResponse;

    @Column(name = "references_info", columnDefinition = "TEXT")
    private String referencesInfo;

    @Transient
    private List<Object> references;

    /** 答案可信度评分（0.0~1.0），由 AnswerCredibilityService 计算后写入。 */
    @Column(name = "confidence_score")
    private Float confidenceScore;

    /** 可信度等级：high / medium / low。 */
    @Column(name = "confidence_level", length = 20)
    private String confidenceLevel;

    /** LLM 是否在答案中实际引用了文档编号。 */
    @Column(name = "citations_present")
    private Boolean citationsPresent;

    /** 所属实验分组（A/B 测试用）。 */
    @Column(name = "experiment_id", length = 50)
    private String experimentId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public void setReferences(List<?> refs) {
        this.references = (List<Object>) refs;
        try {
            this.referencesInfo = objectMapper.writeValueAsString(refs);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize references", e);
            this.referencesInfo = "[]";
        }
    }
}
