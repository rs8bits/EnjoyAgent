package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ConditionNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ConditionNodeExecutor.class);
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    private final ObjectMapper objectMapper;

    public ConditionNodeExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String nodeType() {
        return "CONDITION";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        ConditionConfig config = parseConfig(ctx.node().getConfigJson());
        String expression = resolveTemplates(config.expression, ctx.context());
        boolean result = evaluate(expression, config);

        log.info("Condition evaluated: expression='{}', result={}", expression, result);
        return new NodeExecutionResult(result ? "true" : "false", Map.of(
                "condition_result", result
        ));
    }

    private String resolveTemplates(String expression, Map<String, Object> context) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(expression);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = context.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean evaluate(String expression, ConditionConfig config) {
        String operator = config.operator != null ? config.operator : "contains";
        String value = config.value != null ? config.value : "";

        return switch (operator) {
            case "contains" -> expression.contains(value);
            case "equals", "==" -> expression.equals(value);
            case "not_contains" -> !expression.contains(value);
            case "not_equals", "!=" -> !expression.equals(value);
            case "not_empty" -> expression != null && !expression.isBlank();
            case "empty" -> expression == null || expression.isBlank();
            default -> !expression.isEmpty();
        };
    }

    private ConditionConfig parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, ConditionConfig.class);
        } catch (JsonProcessingException e) {
            throw new ApiException("WORKFLOW_NODE_CONFIG_INVALID",
                    "Invalid condition node config JSON", HttpStatus.BAD_REQUEST);
        }
    }

    private record ConditionConfig(
            String expression,
            String operator,
            String value
    ) {
    }
}
