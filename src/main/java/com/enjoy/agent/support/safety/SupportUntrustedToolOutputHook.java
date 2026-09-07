package com.enjoy.agent.support.safety;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 把第一方 Skill 返回的 JSON 当作“不可信业务数据”进行结构化检查。
 *
 * <p>Agent 描述、知识库名称、充值备注等字段可以由用户编辑，因此不能因为数据
 * 来自数据库就信任它。该 Hook 会：</p>
 *
 * <ol>
 *   <li>要求结果必须是合法 JSON，避免畸形文本改变模型上下文结构；</li>
 *   <li>递归处理所有字符串，隔离典型提示注入语句；</li>
 *   <li>把 ASCII 尖括号替换为全角字符，防止业务字段伪造上下文边界标签；</li>
 *   <li>删除双向文本控制字符，避免审计页面看到的顺序与真实内容不一致。</li>
 * </ol>
 *
 * <p>这个 Hook 不代替字段白名单和租户过滤，而是它们之后的纵深防御。</p>
 */
@Component
public class SupportUntrustedToolOutputHook implements SupportSafetyHook {

    private static final String ISOLATED_TEXT = "[该业务字段包含疑似提示注入内容，已隔离]";
    private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "(?is)(?:忽略|绕过|覆盖|无视).{0,40}(?:系统|开发者|先前|以上).{0,30}(?:提示|指令|规则)|"
                    + "(?:输出|展示|打印|泄露).{0,30}(?:系统|开发者).{0,20}(?:提示|指令)|"
                    + "(?:ignore|disregard|bypass|override).{0,60}(?:system|developer|previous).{0,30}"
                    + "(?:prompt|instruction|rule)|"
                    + "(?:reveal|print|show).{0,40}(?:system|developer).{0,20}(?:prompt|instruction)"
    );

    private final ObjectMapper objectMapper;

    public SupportUntrustedToolOutputHook(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public int order() {
        // 先隔离结构和提示注入，再由后续 DLP Hook 检查秘密。
        return 50;
    }

    @Override
    public Set<SupportHookPhase> phases() {
        return Set.of(SupportHookPhase.AFTER_SKILL_RAW);
    }

    @Override
    public SupportHookDecision evaluate(SupportHookRequest request) {
        if (request.payload() == null || request.payload().isBlank()) {
            return SupportHookDecision.block(
                    "SUPPORT_SKILL_OUTPUT_EMPTY",
                    "客服查询结果为空，请稍后重试",
                    HttpStatus.BAD_GATEWAY
            );
        }
        try {
            JsonNode root = objectMapper.readTree(request.payload());
            if (root == null) {
                return SupportHookDecision.block(
                        "SUPPORT_SKILL_OUTPUT_INVALID",
                        "客服查询结果格式异常，请稍后重试",
                        HttpStatus.BAD_GATEWAY
                );
            }
            SanitizedNode sanitized = sanitizeNode(root);
            if (!sanitized.changed()) {
                return SupportHookDecision.allow(request.payload());
            }
            return SupportHookDecision.redact(
                    objectMapper.writeValueAsString(sanitized.node()),
                    "SUPPORT_TOOL_OUTPUT_ISOLATED"
            );
        } catch (JsonProcessingException ex) {
            // 第一方 Skill 契约规定 rawPayload 必须为 JSON。格式错误说明实现或依赖
            // 异常，宁可终止本轮，也不能把无法界定边界的文本交给模型。
            return SupportHookDecision.block(
                    "SUPPORT_SKILL_OUTPUT_INVALID",
                    "客服查询结果格式异常，请稍后重试",
                    HttpStatus.BAD_GATEWAY
            );
        }
    }

    private SanitizedNode sanitizeNode(JsonNode node) {
        if (node.isTextual()) {
            String original = node.textValue();
            String sanitized = sanitizeText(original);
            return new SanitizedNode(
                    original.equals(sanitized) ? node : TextNode.valueOf(sanitized),
                    !original.equals(sanitized)
            );
        }
        if (node instanceof ObjectNode objectNode) {
            boolean changed = false;
            // 先复制字段集合再修改 ObjectNode，避免遍历期间替换值导致迭代器状态
            // 与不同 Jackson 版本的实现细节耦合。
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(objectNode.properties());
            for (Map.Entry<String, JsonNode> field : fields) {
                SanitizedNode child = sanitizeNode(field.getValue());
                if (child.changed()) {
                    objectNode.set(field.getKey(), child.node());
                    changed = true;
                }
            }
            return new SanitizedNode(objectNode, changed);
        }
        if (node instanceof ArrayNode arrayNode) {
            boolean changed = false;
            for (int index = 0; index < arrayNode.size(); index++) {
                SanitizedNode child = sanitizeNode(arrayNode.get(index));
                if (child.changed()) {
                    arrayNode.set(index, child.node());
                    changed = true;
                }
            }
            return new SanitizedNode(arrayNode, changed);
        }
        return new SanitizedNode(node, false);
    }

    private String sanitizeText(String value) {
        if (PROMPT_INJECTION.matcher(value).find()) {
            return ISOLATED_TEXT;
        }
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (isBidirectionalControl(codePoint)) {
                return;
            }
            if (codePoint == '<') {
                result.append('＜');
            } else if (codePoint == '>') {
                result.append('＞');
            } else {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    private boolean isBidirectionalControl(int codePoint) {
        return (codePoint >= 0x202A && codePoint <= 0x202E)
                || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private record SanitizedNode(JsonNode node, boolean changed) {
    }
}
