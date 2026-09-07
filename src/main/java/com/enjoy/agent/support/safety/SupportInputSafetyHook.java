package com.enjoy.agent.support.safety;

import com.enjoy.agent.support.application.SupportProperties;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 对用户 Prompt 做同步、确定性的第一道检查。 */
@Component
public class SupportInputSafetyHook implements SupportSafetyHook {

    private static final Pattern PROMPT_OVERRIDE = Pattern.compile(
            "(?is)(忽略|绕过|泄露).{0,20}(系统|开发者|安全).{0,20}(提示|指令|规则)|"
                    + "(?:ignore|bypass|reveal).{0,30}(?:system|developer|safety).{0,20}(?:prompt|instruction)"
    );

    private final SupportProperties properties;
    private final SupportTextSanitizer sanitizer;

    public SupportInputSafetyHook(SupportProperties properties, SupportTextSanitizer sanitizer) {
        this.properties = properties;
        this.sanitizer = sanitizer;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Set<SupportHookPhase> phases() {
        return Set.of(SupportHookPhase.USER_INPUT);
    }

    @Override
    public SupportHookDecision evaluate(SupportHookRequest request) {
        String payload = request.payload();
        if (payload == null || payload.isBlank()) {
            return SupportHookDecision.block(
                    "SUPPORT_MESSAGE_EMPTY", "请输入需要咨询的内容", HttpStatus.BAD_REQUEST);
        }
        if (payload.length() > properties.getMaxUserMessageChars()) {
            return SupportHookDecision.block(
                    "SUPPORT_MESSAGE_TOO_LONG", "消息过长，请精简后再发送", HttpStatus.BAD_REQUEST);
        }
        if (containsUnsafeControlCharacter(payload)) {
            return SupportHookDecision.block(
                    "SUPPORT_MESSAGE_CONTROL_CHARACTER", "消息包含不支持的控制字符", HttpStatus.BAD_REQUEST);
        }
        if (sanitizer.containsLikelySecret(payload)) {
            return SupportHookDecision.block(
                    "SUPPORT_SECRET_IN_PROMPT",
                    "请勿在客服消息中粘贴 API Key、访问令牌或密码",
                    HttpStatus.BAD_REQUEST
            );
        }
        if (PROMPT_OVERRIDE.matcher(payload).find()) {
            return SupportHookDecision.block(
                    "SUPPORT_PROMPT_OVERRIDE_BLOCKED",
                    "该消息包含尝试覆盖系统安全规则的指令，无法处理",
                    HttpStatus.BAD_REQUEST
            );
        }
        return SupportHookDecision.allow(payload.trim());
    }

    private boolean containsUnsafeControlCharacter(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                && codePoint != '\n' && codePoint != '\r' && codePoint != '\t');
    }
}
