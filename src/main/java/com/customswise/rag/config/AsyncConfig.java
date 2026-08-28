package com.customswise.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步与调度配置。
 *
 * <p>职责：
 * <ol>
 *   <li>开启 {@link EnableAsync}：让 {@code @Async("ingestExecutor")} 生效</li>
 *   <li>开启 {@link EnableScheduling}：让 {@code @Scheduled} 生效（IngestionScheduler）</li>
 *   <li>注册 {@code ingestExecutor} Bean：摄取流水线专用线程池</li>
 * </ol>
 *
 * <p>队列满处理：使用 {@link ThreadPoolExecutor.CallerRunsPolicy}——
 * 不丢弃任务，由调用者线程（HTTP 请求线程）兜底执行，保证 at-least-once 语义。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /** 核心线程数：常态保持多少个摄取 worker。 */
    @Value("${ingest.executor.core-pool:4}")
    private int corePool;

    /** 最大线程数：突发负载允许扩容到的上限。 */
    @Value("${ingest.executor.max-pool:16}")
    private int maxPool;

    /** 任务队列容量：超过此值且已 maxPool 时，触发拒绝策略。 */
    @Value("${ingest.executor.queue-capacity:200}")
    private int queueCapacity;

    /**
     * 摄取流水线专用线程池。
     *
     * <p>命名规则：{@code ingest-N}，便于在堆栈/线程转储中识别。
     * <p>拒绝策略：{@link ThreadPoolExecutor.CallerRunsPolicy}——队列满时由 HTTP 线程
     * 兜底跑任务，避免任务丢失（代价是 HTTP 响应变慢，远好过丢任务）。
     *
     * @return 已初始化的 ThreadPoolTaskExecutor 实例
     */
    @Bean("ingestExecutor")
    public ThreadPoolTaskExecutor ingestExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(corePool);
        e.setMaxPoolSize(maxPool);
        e.setQueueCapacity(queueCapacity);
        e.setThreadNamePrefix("ingest-");
        // 队列满时让调用者（HTTP 线程）跑——保证不丢任务
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        e.initialize();
        return e;
    }
}