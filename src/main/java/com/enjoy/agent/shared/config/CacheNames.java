package com.enjoy.agent.shared.config;

/**
 * 缓存名称常量。
 */
public final class CacheNames {

    private CacheNames() {
    }

    /** 会话长期摘要 */
    public static final String SESSION_SUMMARY = "session-summary-v3";

    /** Agent 可运行的 MCP 工具列表 */
    public static final String AGENT_TOOLS = "agent-tools-v3";

    /** Agent 配置快照 */
    public static final String AGENT_CONFIG = "agent-config-v4";

    /** JWT 验证后的用户、租户与成员关系快照 */
    public static final String AUTH_CONTEXT = "auth-context-v1";
}
