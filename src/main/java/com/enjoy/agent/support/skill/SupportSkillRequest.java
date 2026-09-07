package com.enjoy.agent.support.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 普通查询 Skill 的统一参数。
 *
 * <p>resourceId 只会从“ID 12”“编号 12”或“#12”这类显式表达中提取，避免把
 * 充值金额等普通数字误认为资产 ID。</p>
 */
public record SupportSkillRequest(
        String userMessage,
        Long resourceId,
        Map<String, Object> arguments
) {
    public SupportSkillRequest {
        // 复制后再只读包装，防止 Skill 修改规划参数；与 Map.copyOf 不同，这种写法
        // 能容纳 JSON 中合法出现的 null，由具体 Skill 决定该字段是“未提供”还是非法。
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /** 保留第一阶段调用方式，便于已有只读 Skill 和测试继续工作。 */
    public SupportSkillRequest(String userMessage, Long resourceId) {
        this(userMessage, resourceId, Map.of());
    }
}
