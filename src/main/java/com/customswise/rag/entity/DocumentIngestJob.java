package com.customswise.rag.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档摄取任务。
 * 一份 PolicyDocument 对应一个 DocumentIngestJob，记录该 PDF 的
 * 异步解析+向量化+入库流水线的执行状态。
 * 生命周期：
 * - 创建：DocumentService.stageUpload() 建 PolicyDocument 后立即建 job（status=PENDING）
 * - 执行：IngestionService.processAsync() 消费（PENDING → RUNNING → SUCCESS / FAILED）
 * - 失败重试：IngestionService.scheduleRetry() 按指数退避重置 attempts 与 nextAttemptAt
 * - 跨进程恢复：IngestionScheduler 每 30 秒扫描 PENDING 且 nextAttemptAt<=now 的 job
 * 表结构对应 src/main/resources/schema.sql 中的 document_ingest_job 表。
 */
@Data
@Entity
@Table(name = "document_ingest_job")
public class DocumentIngestJob {

    /**
     * 任务状态机：
     * - PENDING：等待执行（包括重试中的待执行）
     * - RUNNING：正在执行
     * - SUCCESS：执行成功（终态）
     * - FAILED：达到 max_attempts 后放弃（终态）
     */
    public enum Status { PENDING, RUNNING, SUCCESS, FAILED }

    /** 任务 UUID，全局唯一，PRIMARY KEY。 */
    @Id
    @Column(name = "job_id", length = 64)
    private String jobId;

    /** 关联 policy_document.id，外键 ON DELETE CASCADE。 */
    @Column(name = "document_id")
    private Long documentId;

    /** 任务状态，参见 {@link Status}。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Status status = Status.PENDING;

    /** 最大重试次数，默认 3（不含首次执行）。 */
    @Column(name = "max_attempts")
    private Integer maxAttempts = 3;

    /** 已执行次数（含首次），每次失败 +1。 */
    @Column(name = "attempts")
    private Integer attempts = 0;

    /** 最近一次失败的异常信息（前 3 行堆栈），便于排查。 */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** 下次允许调度的时间。失败重试时按 2^attempts 分钟指数退避。 */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    /** 任务创建时间（控制器调用 stageUpload 时）。 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 任务结束时间（进入 SUCCESS 或 FAILED 终态时设置）。 */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
