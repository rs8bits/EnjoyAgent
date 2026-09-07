package com.enjoy.agent.support.skill;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.support.safety.SupportTextSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Skill 共用的安全 JSON 编码、ID 提取和短文本工具。 */
@Component
public class SupportSkillPayloadWriter {

    private static final Pattern EXPLICIT_ID = Pattern.compile(
            "(?i)(?:\\bID\\b|编号|#)\\s*[:：#]?\\s*(\\d{1,18})"
    );

    private final ObjectMapper objectMapper;
    private final SupportTextSanitizer sanitizer;

    public SupportSkillPayloadWriter(ObjectMapper objectMapper, SupportTextSanitizer sanitizer) {
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
    }

    public String write(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new ApiException(
                    "SUPPORT_SKILL_SERIALIZATION_FAILED",
                    "客服查询结果暂时无法处理",
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
        }
    }

    public Long extractExplicitId(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = EXPLICIT_ID.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public String safeText(String value, int maxCodePoints) {
        return sanitizer.truncate(sanitizer.redactSecrets(value), maxCodePoints);
    }

    /**
     * 保持“模型可见结果”始终是合法 JSON。直接 substring 会截断在字段中间，既破坏
     * Schema，也可能让模型误解数据；超限时改为一个合法的预览信封。
     */
    public String boundJson(String json, int maxCodePoints) {
        if (json == null || json.codePointCount(0, json.length()) <= maxCodePoints) {
            return json;
        }
        int previewLimit = Math.max(64, maxCodePoints / 2);
        String bounded = write(Map.of(
                "truncated", true,
                "notice", "结果超过客服上下文上限，仅提供安全预览",
                "preview", sanitizer.truncate(json, previewLimit)
        ));
        if (bounded.codePointCount(0, bounded.length()) <= maxCodePoints) {
            return bounded;
        }
        return write(Map.of("truncated", true, "notice", "结果过长，请缩小查询范围"));
    }

}
