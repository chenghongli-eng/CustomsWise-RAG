package com.customswise.rag.dto;

import lombok.Data;
import java.time.LocalDate;

@Data
public class DocumentUploadRequest {
    private String title;
    private String documentNumber;
    private LocalDate publishDate;
    private LocalDate effectiveDate;
    private LocalDate expireDate;
    private String status = "现行";
    private String applicableBusiness;
    private String summary;
}
