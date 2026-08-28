package com.customswise.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 上传文档接口（POST /api/documents/upload）的响应体。
 *
 * 字段语义：
 * - documentId：新创建（status=PENDING）或已存在（status=DUPLICATE）的 PolicyDocument 主键
 * - jobId：异步任务的 UUID，用于轮询 /api/documents/jobs/{jobId}；DUPLICATE 时为 null
 * - status：PENDING / DUPLICATE（其他值由调用方解释）
 * - message：人类可读的提示信息
 */
@Data
@AllArgsConstructor
public class JobAck {
    /** PolicyDocument 主键。 */
    private Long documentId;
    /** 异步任务 UUID；DUPLICATE 时为 null。 */
    private String jobId;
    /** PENDING / DUPLICATE 等。 */
    private String status;
    /** 人类可读提示。 */
    private String message;
}
