package com.customswise.rag.service;

import com.customswise.rag.entity.DocumentIngestJob;
import com.customswise.rag.repository.DocumentIngestJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 独立的调度器：扫描 PENDING job 并触发 {@link IngestionService#processNow}。
 *
 * <p>为什么拆成独立类：
 * Spring 的 @Async 通过代理实现，如果在同一类内自调用（如 ingestionService.processAsync()）
 * 会绕过代理导致异步失效。把"扫描+提交"放到独立 Bean，调用跨 bean 走代理，async 才生效。
 *
 * <p>扫描策略：{@link Scheduled#fixedDelay()} = 30 秒，
 * 查 PENDING 且 nextAttemptAt &lt;= now 的前 50 条 job（按 nextAttemptAt 升序），
 * 每条提交到 {@code ingestExecutor} 异步执行。
 *
 * <p>重复触发防护：取到 job 后立即把 nextAttemptAt 推到 now+10min，避免在执行窗口内被再次扫描。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionScheduler {

    /** DocumentIngestJob 仓储。 */
    private final DocumentIngestJobRepository jobRepository;
    /** IngestionService（跨 bean 调用走 Spring 代理，@Async 生效）。 */
    private final IngestionService ingestionService;

    /**
     * 每 30 秒扫描一次 PENDING 且到期的 job，提交到 ingestExecutor。
     *
     * <p>调度间隔使用 {@code fixedDelay} 而非 {@code fixedRate}：避免上一次扫描
     * 还没结束又被新的 tick 拉起来；单次扫描最多处理 50 条，超出下次扫描再处理。
     */
    @Scheduled(fixedDelay = 30_000L)
    public void resumePendingJobs() {
        List<DocumentIngestJob> due = jobRepository
                .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        DocumentIngestJob.Status.PENDING, LocalDateTime.now());
        if (due.isEmpty()) return;
        log.info("[JOB-SCHEDULER] resuming {} pending jobs", due.size());
        for (DocumentIngestJob job : due) {
            // 重新设置 nextAttemptAt 防重复：10 分钟内不会被再次拉到
            job.setNextAttemptAt(LocalDateTime.now().plusMinutes(10));
            jobRepository.save(job);
            // processNow → processAsync(@Async)，跨 bean 走代理，async 生效
            ingestionService.processNow(job.getJobId());
        }
    }
}
