package com.customswise.rag.dto;

import lombok.Data;

@Data
public class QaRequest {
    private String question;
    private String userConditions;
    private String sessionId;
}
