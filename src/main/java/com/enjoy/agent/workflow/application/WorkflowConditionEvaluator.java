package com.enjoy.agent.workflow.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Small, deliberately non-Turing-complete condition language for workflow nodes. */
@Component
public class WorkflowConditionEvaluator {

    private static final int MAX_EXPRESSION_LENGTH = 2_048;
    private static final Pattern INLINE_COMPARISON = Pattern.compile("^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.*?)$");

    private final ObjectMapper objectMapper;

    public WorkflowConditionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String resolveTemplates(String expression, Map<String, Object> context) {
        return WorkflowVariableResolver.resolveTextTemplate(
                expression, context, objectMapper, MAX_EXPRESSION_LENGTH,
                () -> invalid("Resolved workflow condition is too long"));
    }

    public boolean evaluate(String expression, String operator, String expectedValue) {
        String actual = expression == null ? "" : expression;
        String expected = expectedValue == null ? "" : expectedValue;
        String normalized = operator == null || operator.isBlank() ? "truthy" : operator.trim().toLowerCase();
        return switch (normalized) {
            case "truthy" -> truthy(actual);
            case "falsy" -> !truthy(actual);
            case "contains" -> actual.contains(expected);
            case "not_contains" -> !actual.contains(expected);
            case "equals", "==" -> actual.equals(expected);
            case "not_equals", "!=" -> !actual.equals(expected);
            case "not_empty" -> !actual.isBlank();
            case "empty" -> actual.isBlank();
            case ">", ">=", "<", "<=" -> compareNumbers(actual, expected, normalized);
            default -> throw invalid("Unsupported workflow condition operator: " + normalized);
        };
    }

    public boolean evaluateInline(String expression) {
        String value = requireExpression(expression).trim();
        Matcher comparison = INLINE_COMPARISON.matcher(value);
        if (!comparison.matches()) {
            return evaluate(value, "truthy", null);
        }
        return evaluate(comparison.group(1).trim(), comparison.group(2), comparison.group(3).trim());
    }

    private String requireExpression(String expression) {
        if (expression == null || expression.length() > MAX_EXPRESSION_LENGTH) {
            throw invalid("Workflow condition must be at most " + MAX_EXPRESSION_LENGTH + " characters");
        }
        return expression;
    }

    private boolean truthy(String value) {
        String normalized = value == null ? "" : value.trim();
        return !normalized.isEmpty()
                && !"false".equalsIgnoreCase(normalized)
                && !"null".equalsIgnoreCase(normalized)
                && !"0".equals(normalized);
    }

    private boolean compareNumbers(String actual, String expected, String operator) {
        try {
            int comparison = new BigDecimal(actual.trim()).compareTo(new BigDecimal(expected.trim()));
            return switch (operator) {
                case ">" -> comparison > 0;
                case ">=" -> comparison >= 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                default -> false;
            };
        } catch (NumberFormatException ex) {
            throw invalid("Numeric workflow comparison requires numeric operands");
        }
    }

    private ApiException invalid(String message) {
        return new ApiException("WORKFLOW_CONDITION_INVALID", message, HttpStatus.BAD_REQUEST);
    }
}
