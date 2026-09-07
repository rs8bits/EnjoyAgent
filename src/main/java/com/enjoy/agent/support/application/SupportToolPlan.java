package com.enjoy.agent.support.application;

import java.util.List;
import java.util.Objects;

/**
 * 一次客服工具规划的强类型结果。
 *
 * <p>{@link #type()} 决定另外两个业务字段中哪一个有效：</p>
 * <ul>
 *     <li>{@code DIRECT_ANSWER}：{@code directAnswer} 非空，{@code toolCalls} 为空；</li>
 *     <li>{@code TOOL_CALLS}：{@code directAnswer} 为空，{@code toolCalls} 非空。</li>
 * </ul>
 *
 * <p>通过静态工厂创建对象，可以避免集成代码意外构造出“既有直接回答、又有工具
 * 调用”或“两者都没有”的模糊状态。</p>
 */
public record SupportToolPlan(
        SupportToolPlanType type,
        String directAnswer,
        List<SupportPlannedToolCall> toolCalls,
        SupportModelUsage modelUsage
) {

    public SupportToolPlan {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(toolCalls, "toolCalls");
        Objects.requireNonNull(modelUsage, "modelUsage");
        toolCalls = List.copyOf(toolCalls);

        if (type == SupportToolPlanType.DIRECT_ANSWER
                && (directAnswer == null || directAnswer.isBlank() || !toolCalls.isEmpty())) {
            throw new IllegalArgumentException("Direct-answer plan must contain only a direct answer");
        }
        if (type == SupportToolPlanType.TOOL_CALLS
                && (directAnswer != null || toolCalls.isEmpty())) {
            throw new IllegalArgumentException("Tool-call plan must contain only tool calls");
        }
    }

    public static SupportToolPlan directAnswer(String answer, SupportModelUsage modelUsage) {
        return new SupportToolPlan(
                SupportToolPlanType.DIRECT_ANSWER,
                answer,
                List.of(),
                modelUsage
        );
    }

    public static SupportToolPlan toolCalls(
            List<SupportPlannedToolCall> toolCalls,
            SupportModelUsage modelUsage
    ) {
        return new SupportToolPlan(
                SupportToolPlanType.TOOL_CALLS,
                null,
                toolCalls,
                modelUsage
        );
    }
}
