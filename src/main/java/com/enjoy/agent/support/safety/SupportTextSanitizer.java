package com.enjoy.agent.support.safety;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 客服域的文本脱敏器。
 *
 * <p>它是纵深防御，不代替 Skill 的字段白名单。Skill 从源头不读取秘密；脱敏器
 * 再处理意外混入日志、错误文案或用户可控描述中的常见凭证格式。</p>
 */
@Component
public class SupportTextSanitizer {

    private static final String REDACTED = "[敏感信息已隐藏]";
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+\\-/]+=*"
    );
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile(
            "(?i)\\b(?:sk|ak)-[A-Za-z0-9_-]{8,}\\b"
    );
    private static final Pattern JWT = Pattern.compile(
            "\\b[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"
    );
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(\\\"?(?:api[_ -]?key|password|secret|authorization|access[_ -]?token)\\\"?\\s*[:=]\\s*\\\"?)[^\\s,}\\\"]{6,}"
    );

    public boolean containsLikelySecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return BEARER.matcher(value).find()
                || OPENAI_STYLE_KEY.matcher(value).find()
                || JWT.matcher(value).find()
                || NAMED_SECRET.matcher(value).find();
    }

    public String redactSecrets(String value) {
        if (value == null) {
            return null;
        }
        String redacted = BEARER.matcher(value).replaceAll(REDACTED);
        redacted = OPENAI_STYLE_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = JWT.matcher(redacted).replaceAll(REDACTED);
        return NAMED_SECRET.matcher(redacted).replaceAll("$1" + REDACTED);
    }

    /**
     * 采用 code point 截断，避免恰好切在 emoji 等代理项中间生成非法 UTF-16。
     */
    public String truncate(String value, int maxCodePoints) {
        if (value == null || maxCodePoints < 0) {
            return value;
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, end) + "…[已截断]";
    }
}
