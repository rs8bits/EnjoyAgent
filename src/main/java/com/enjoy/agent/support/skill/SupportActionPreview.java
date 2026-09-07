package com.enjoy.agent.support.skill;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 需要确认的 Skill 在真正执行前生成的安全预览。
 *
 * @param normalizedArguments 服务端校验并规范化后的不可变参数；确认时只使用这一份
 * @param title 前端确认卡片标题
 * @param summary 不包含秘密、可直接展示给当前用户的操作说明
 * @param targetUpdatedAt 删除目标在生成预览时的更新时间；用于发现确认期间的并发修改
 */
public record SupportActionPreview(
        Map<String, Object> normalizedArguments,
        String title,
        String summary,
        Instant targetUpdatedAt
) {
    public SupportActionPreview {
        // 模型生成的可选字段偶尔会显式传 null。这里仍然复制并冻结 Map，既避免
        // 调用方在确认期间修改参数，也不会让 Map.copyOf 因 null 值抛出无意义 NPE。
        normalizedArguments = normalizedArguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(normalizedArguments));
    }
}
