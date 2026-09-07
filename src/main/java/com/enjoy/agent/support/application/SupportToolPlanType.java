package com.enjoy.agent.support.application;

/**
 * 客服模型一次规划的结果类型。
 *
 * <p>这里刻意不设计“继续规划”之类的状态：阶段二每轮只让模型规划一次，
 * 查询工具执行完毕后由外层编排器直接生成最终回答，避免演变成难以理解和限流的
 * 无限 Agent 循环。</p>
 */
public enum SupportToolPlanType {

    /** 当前问题不需要业务工具，模型已经给出可以直接展示的回答。 */
    DIRECT_ANSWER,

    /** 模型选择了一个或多个白名单 Skill，等待外层编排器执行。 */
    TOOL_CALLS
}
