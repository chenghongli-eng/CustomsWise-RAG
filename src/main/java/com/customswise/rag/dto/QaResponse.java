package com.customswise.rag.dto;

import lombok.Data;
import java.util.List;

@Data
public class QaResponse {
    private String answer;
    private List<Reference> references;

    @Data
    public static class Reference {
        private Long documentId;
        private String title;
        private String documentNumber;
        private String status;
        private String content;
    }
}
