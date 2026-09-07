package com.enjoy.agent.support.safety;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.support.skill.SupportSkillManifest;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 按顺序执行安全 Hook 的唯一入口。
 *
 * <p>关键安全检查采用 fail-closed：Hook 抛异常、返回 BLOCK 或未来返回未经处理的
 * REQUIRE_CONFIRMATION 时，主流程都不会继续调用 Skill/模型。</p>
 */
@Service
public class SupportSafetyHookChain {

    private static final Logger log = LoggerFactory.getLogger(SupportSafetyHookChain.class);
    private final List<SupportSafetyHook> hooks;

    public SupportSafetyHookChain(List<SupportSafetyHook> hooks) {
        this.hooks = hooks.stream()
                .sorted(Comparator.comparingInt(SupportSafetyHook::order))
                .toList();
    }

    public String inspect(
            SupportHookPhase phase,
            SupportHookContext context,
            SupportSkillManifest skill,
            String payload
    ) {
        String currentPayload = payload;
        for (SupportSafetyHook hook : hooks) {
            if (!hook.phases().contains(phase)) {
                continue;
            }
            long started = System.nanoTime();
            SupportHookDecision decision;
            try {
                decision = hook.evaluate(new SupportHookRequest(phase, context, skill, currentPayload));
            } catch (ApiException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.error("Support safety hook failed closed, phase={}, hook={}",
                        phase, hook.getClass().getSimpleName(), ex);
                throw new ApiException(
                        "SUPPORT_SAFETY_HOOK_FAILED",
                        "安全检查暂时不可用，请稍后重试",
                        HttpStatus.SERVICE_UNAVAILABLE
                );
            } finally {
                if (log.isDebugEnabled()) {
                    long elapsedMicros = (System.nanoTime() - started) / 1_000;
                    log.debug("Support safety hook completed, phase={}, hook={}, durationMicros={}",
                            phase, hook.getClass().getSimpleName(), elapsedMicros);
                }
            }

            if (decision == null) {
                throw new ApiException(
                        "SUPPORT_SAFETY_HOOK_INVALID_RESULT",
                        "安全检查暂时不可用，请稍后重试",
                        HttpStatus.SERVICE_UNAVAILABLE
                );
            }
            if (decision.action() == SupportHookAction.BLOCK) {
                throw new ApiException(decision.reasonCode(), decision.publicMessage(), decision.status());
            }
            if (decision.action() == SupportHookAction.REQUIRE_CONFIRMATION) {
                throw new ApiException(
                        "SUPPORT_CONFIRMATION_REQUIRED",
                        "该操作必须通过客服确认卡片明确确认",
                        HttpStatus.CONFLICT
                );
            }
            if (decision.action() == SupportHookAction.REDACT) {
                currentPayload = decision.payload();
            }
        }
        return currentPayload;
    }
}
