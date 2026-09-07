package com.enjoy.agent.support.api.response;

import java.util.UUID;

/** SSE intent_routed：公开路由结果，不包含模型思维链。 */
public record SupportIntentRoutedEvent(
        UUID turnId,
        String intent,
        String route,
        String displayName
) {
}
