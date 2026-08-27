package com.customswise.rag.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "policy_document")
public class PolicyDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "document_number", length = 100)
    private String documentNumber;

    @Column(name = "publish_date")
    private LocalDate publishDate;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expire_date")
    private LocalDate expireDate;

    @Column(length = 20)
    private String status = "现行";

    @Column(name = "applicable_business", length = 200)
    private String applicableBusiness;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "file_path", length = 500)
    private String filePath;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "milvus_collection", length = 100)
    private String milvusCollection;

    @Column(name = "milvus_doc_id", length = 100)
    private String milvusDocId;

    @Column(name = "reference_count")
    private Integer referenceCount = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column
    private Boolean deleted = false;
}
