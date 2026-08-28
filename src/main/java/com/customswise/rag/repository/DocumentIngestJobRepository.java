package com.customswise.rag.repository;

import com.customswise.rag.entity.DocumentIngestJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DocumentIngestJob 仓储。
 *
 * Spring Data JPA 自动提供 findById / save / deleteById 等基础方法。
 * 下方 findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc
 * 由方法名衍生，供 IngestionScheduler 扫描待重试 job 使用。
 */
@Repository
public interface DocumentIngestJobRepository extends JpaRepository<DocumentIngestJob, String> {

    /**
     * 扫描"到达重试时间"的 PENDING job。
     * 用于 IngestionScheduler 每 30 秒扫描恢复跨进程的 pending 任务。
     *
     * @param status 待匹配状态（固定传 PENDING）
     * @param cutoff next_attempt_at 的截止时间（<= 当前时间即代表可执行）
     * @return 按 next_attempt_at 升序排列的最多 50 条记录
     */
    List<DocumentIngestJob> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            DocumentIngestJob.Status status, LocalDateTime cutoff);
}
