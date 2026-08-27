package com.customswise.rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

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

    @Column(name = "references_info", columnDefinition = "jsonb")
    private String referencesInfo;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
