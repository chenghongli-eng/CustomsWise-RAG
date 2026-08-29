package com.customswise.rag.dto;

import lombok.Data;
import java.util.List;

@Data
public class QaResponse {
    private String answer;
    private List<Reference> references;

    /** 返回错误信息（answer 填充 message，references 为空） */
    public static QaResponse error(String message) {
        QaResponse r = new QaResponse();
        r.setAnswer("【系统错误】" + message);
        r.setReferences(List.of());
        return r;
    }

    @Data
    public static class Reference {
        private Long documentId;
        private String title;
        private String documentNumber;
        private String status;
        private String content;
    }
}
