package com.customswise.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * A/B 实验分组服务。
 *
 * <p>基于 sessionId 的确定性哈希取模做 sticky 分组：
 * 同一 sessionId 在同一 experimentId 下始终分到同一组（control 或 treatment），
 * 不需要服务端存储用户-分组映射。
 *
 * <p>enabled=false 时所有请求归为 "control" 组（不启用实验）。
 */
@Slf4j
@Service
public class ExperimentService {

    public static final String GROUP_CONTROL = "control";
    public static final String GROUP_TREATMENT = "treatment";

    @Value("${experiments.enabled:false}")
    private boolean enabled;

    /**
     * 根据 sessionId 获取实验分组。
     *
     * @param sessionId   用户会话 ID（来自 QaRequest.sessionId）
     * @param experimentId 实验标识（如 "similarity-threshold-test"）
     * @return control 或 treatment
     */
    public String getGroup(String sessionId, String experimentId) {
        if (!enabled || sessionId == null || sessionId.isBlank()) {
            return GROUP_CONTROL;
        }
        int bucket = Math.abs((sessionId + experimentId).hashCode() % 100);
        String group = bucket < 50 ? GROUP_CONTROL : GROUP_TREATMENT;
        log.debug("[EXP] sessionId={} experiment={} bucket={} group={}",
                sessionId, experimentId, bucket, group);
        return group;
    }

    /**
     * 实验是否启用。
     */
    public boolean isEnabled() {
        return enabled;
    }
}
