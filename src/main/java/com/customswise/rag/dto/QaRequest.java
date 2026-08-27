package com.customswise.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "问答请求")
public class QaRequest {

    @Schema(description = "问题内容", example = "我是9610模式的跨境电商，做退货需要满足什么条件？")
    private String question;

    @Schema(description = "用户条件（可选）", example = "企业类型：跨境电商；出口模式：9610")
    private String userConditions;

    @Schema(description = "会话ID（可选，用于关联多轮对话）", example = "session-12345")
    private String sessionId;
}
