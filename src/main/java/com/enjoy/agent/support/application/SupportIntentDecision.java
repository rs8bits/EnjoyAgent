package com.enjoy.agent.support.application;

import com.enjoy.agent.support.domain.enums.SupportRouteType;
import java.util.List;

/** 确定性意图路由结果，不包含任何模型思维链。 */
public record SupportIntentDecision(
        String intentCode,
        String displayName,
        SupportRouteType routeType,
        String directAnswer,
        List<String> skillNames
) {
}
