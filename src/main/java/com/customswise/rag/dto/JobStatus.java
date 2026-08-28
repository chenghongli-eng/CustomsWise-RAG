package com.customswise.rag.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务状态查询接口（GET /api/documents/jobs/{jobId}）的响应体。
 *
 * 镜像 DocumentIngestJob 实体，但只暴露查询关心的字段，
 * 并把 enum 转成 String 方便前端直接渲染。
 */
@Data
public class JobStatus {
    /** 任务 UUID。 */
    private String jobId;
    /** 关联 PolicyDocument.id。 */
    private Long documentId;
    /** PENDING / RUNNING / SUCCESS / FAILED。 */
    private String status;
    /** 已执行次数（含首次）。 */
    private Integer attempts;
    /** 最大重试次数。 */
    private Integer maxAttempts;
    /** 最近一次失败的异常信息（前 3 行堆栈）。 */
    private String lastError;
    /** 任务创建时间。 */
    private LocalDateTime createdAt;
    /** 任务结束时间（SUCCESS / FAILED 时设置）。 */
    private LocalDateTime finishedAt;
}
