package com.enjoy.agent.support.safety;

import java.util.Set;
import org.springframework.stereotype.Component;

/** 工具输出、模型可见数据、最终回复和归档内容共用的 DLP 脱敏 Hook。 */
@Component
public class SupportDlpSafetyHook implements SupportSafetyHook {

    private static final Set<SupportHookPhase> PHASES = Set.of(
            SupportHookPhase.BEFORE_CONTEXT,
            SupportHookPhase.AFTER_SKILL_RAW,
            SupportHookPhase.BEFORE_MODEL_VISIBLE,
            SupportHookPhase.AFTER_MODEL,
            SupportHookPhase.BEFORE_ARCHIVE
    );

    private final SupportTextSanitizer sanitizer;

    public SupportDlpSafetyHook(SupportTextSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Set<SupportHookPhase> phases() {
        return PHASES;
    }

    @Override
    public SupportHookDecision evaluate(SupportHookRequest request) {
        String original = request.payload();
        String redacted = sanitizer.redactSecrets(original);
        if (original != null && !original.equals(redacted)) {
            return SupportHookDecision.redact(redacted, "SUPPORT_DLP_REDACTED");
        }
        return SupportHookDecision.allow(original);
    }
}
