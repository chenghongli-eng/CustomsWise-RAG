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
