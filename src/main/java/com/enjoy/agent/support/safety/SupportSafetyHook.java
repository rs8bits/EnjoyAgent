package com.enjoy.agent.support.safety;

import java.util.Set;

/** 可插拔的客服安全检查。order 越小越先执行。 */
public interface SupportSafetyHook {
    int order();

    Set<SupportHookPhase> phases();

    SupportHookDecision evaluate(SupportHookRequest request);
}
