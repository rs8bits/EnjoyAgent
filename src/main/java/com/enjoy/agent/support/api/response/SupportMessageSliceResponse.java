package com.enjoy.agent.support.api.response;

import java.util.List;

/**
 * 基于 sequence 的向前游标分页结果。
 *
 * <p>返回的 items 已按从旧到新排列，便于前端直接插入时间线；
 * nextBeforeSequence 为 null 表示没有更早消息。</p>
 */
public record SupportMessageSliceResponse(
        List<SupportMessageResponse> items,
        boolean hasMore,
        Long nextBeforeSequence
) {
}
