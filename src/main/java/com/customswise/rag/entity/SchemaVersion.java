package com.customswise.rag.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 组件 schema 版本记录。
 * 用于追踪外部依赖（Milvus / OCR / PDF 解析等）的 schema 演进状态。
 * MigrationService 启动对账时会读取 component="milvus" 的 version，
 * 避免重复 patch；运维手动变更 schema 后可通过更新该表记录新版本。
 * 表结构对应 src/main/resources/schema.sql 中的 schema_version 表。
 */
@Data
@Entity
@Table(name = "schema_version")
public class SchemaVersion {

    /** 组件标识，如 "milvus" / "pdf_extractor"，PRIMARY KEY。 */
    @Id
    @Column(length = 50)
    private String component;

    /** 当前已升级到的 schema 版本号。 */
    @Column(nullable = false)
    private Integer version;

    /** 最近一次版本变更时间。 */
    @Column(name = "upgraded_at", nullable = false)
    private LocalDateTime upgradedAt;
}
