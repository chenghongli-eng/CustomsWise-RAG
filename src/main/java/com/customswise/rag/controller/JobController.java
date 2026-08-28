package com.customswise.rag.controller;

import com.customswise.rag.dto.ApiResponse;
import com.customswise.rag.dto.JobStatus;
import com.customswise.rag.entity.DocumentIngestJob;
import com.customswise.rag.repository.DocumentIngestJobRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 摄取任务查询接口：{@code GET /api/documents/jobs/{jobId}}。
 *
 * <p>对应 DocumentController.upload 异步返回的 jobId，前端轮询本接口获取状态。
 * 状态机：PENDING → RUNNING → SUCCESS / FAILED。
 */
@RestController
@RequestMapping("/api/documents/jobs")
@RequiredArgsConstructor
@Tag(name = "摄取任务", description = "文档摄取任务状态查询")
public class JobController {

    /** DocumentIngestJob 仓储（主键查询）。 */
    private final DocumentIngestJobRepository jobRepository;

    /**
     * 查询 job 状态。
     *
     * <p>未找到时返回 {@link ApiResponse#error}，HTTP 仍为 200（业务结果统一封装）。
     * 调用方根据返回 code 字段判断。
     *
     * @param jobId DocumentIngestJob.jobId（UUID 字符串）
     * @return ApiResponse&lt;JobStatus&gt;
     */
    @GetMapping("/{jobId}")
    public ApiResponse<JobStatus> status(@PathVariable String jobId) {
        return jobRepository.findById(jobId)
                .map(this::toDto)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error("job not found: " + jobId));
    }

    /**
     * 实体 → DTO 转换：把 enum Status 转成 String，避免 Jackson 序列化时输出枚举字面量不友好。
     */
    private JobStatus toDto(DocumentIngestJob job) {
        JobStatus s = new JobStatus();
        s.setJobId(job.getJobId());
        s.setDocumentId(job.getDocumentId());
        s.setStatus(job.getStatus() == null ? null : job.getStatus().name());
        s.setAttempts(job.getAttempts());
        s.setMaxAttempts(job.getMaxAttempts());
        s.setLastError(job.getLastError());
        s.setCreatedAt(job.getCreatedAt());
        s.setFinishedAt(job.getFinishedAt());
        return s;
    }
}
