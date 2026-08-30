package com.enjoy.agent.workflow.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Safe, deliberately Map-only workflow variable resolution. It never evaluates expressions,
 * indexes Java objects, invokes methods, or uses reflection.
 */
public final class WorkflowVariableResolver {

    private static final int MAX_SELECTOR_LENGTH = 256;
    private static final String SEGMENT = "[A-Za-z0-9_-]{1,64}";
    private static final String SELECTOR = SEGMENT + "(?:\\." + SEGMENT + ")*";
    private static final Pattern SELECTOR_PATTERN = Pattern.compile(SELECTOR);
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile(
            "\\{\\{\\s*#\\s*(" + SELECTOR + ")\\s*#\\s*}}"
                    + "|\\{\\{\\s*(" + SELECTOR + ")\\s*}}"
    );

    private static final Map<WorkflowNodeType, Set<String>> OUTPUT_FIELDS = Map.of(
            WorkflowNodeType.START, Set.of("user_input"),
            WorkflowNodeType.LLM, Set.of(
                    "llm_output", "llm_provider", "llm_model",
                    "llm_prompt_tokens", "llm_completion_tokens"),
            WorkflowNodeType.KNOWLEDGE, Set.of("retrieval_context"),
            WorkflowNodeType.TOOL, Set.of("tool_result", "tool_raw_response"),
            WorkflowNodeType.CONDITION, Set.of("condition_result"),
            WorkflowNodeType.LOOP, Set.of("loop_iterations", "loop_break_reason", "body_outputs"),
            WorkflowNodeType.END, Set.of("final_output")
    );
    private static final Set<String> LEGACY_FLAT_FIELDS;

    static {
        Set<String> fields = new LinkedHashSet<>();
        OUTPUT_FIELDS.values().forEach(fields::addAll);
        LEGACY_FLAT_FIELDS = Set.copyOf(fields);
    }

    private WorkflowVariableResolver() {
    }

    public static String resolveTextTemplate(
            String source,
            Map<String, Object> context,
            ObjectMapper objectMapper,
            int maxResolvedLength,
            Supplier<? extends RuntimeException> tooLong
    ) {
        String template = source == null ? "" : source;
        ensureLength(template.length(), maxResolvedLength, tooLong);
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuilder resolved = new StringBuilder(template.length());
        int cursor = 0;
        while (matcher.find()) {
            String literal = template.substring(cursor, matcher.start());
            Resolution value = resolveSelector(selector(matcher), context);
            String replacement = value.found()
                    ? renderTextValue(value.value(), objectMapper)
                    : matcher.group();
            ensureLength((long) resolved.length() + literal.length() + replacement.length(),
                    maxResolvedLength, tooLong);
            resolved.append(literal).append(replacement);
            cursor = matcher.end();
        }
        String tail = template.substring(cursor);
        ensureLength((long) resolved.length() + tail.length(), maxResolvedLength, tooLong);
        return resolved.append(tail).toString();
    }

    /** Exact selectors retain their JSON-like type; interpolated strings are rendered as text. */
    public static Object resolveTypedTemplate(
            String source,
            Map<String, Object> context,
            ObjectMapper objectMapper,
            int maxResolvedLength,
            Supplier<? extends RuntimeException> tooLong
    ) {
        String template = source == null ? "" : source;
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        if (matcher.matches()) {
            Resolution resolved = resolveSelector(selector(matcher), context);
            if (resolved.found()) {
                String rendered = renderTextValue(resolved.value(), objectMapper);
                ensureLength(rendered.length(), maxResolvedLength, tooLong);
                return resolved.value();
            }
        }
        return resolveTextTemplate(template, context, objectMapper, maxResolvedLength, tooLong);
    }

    /** Resolves a raw selector (without braces), using the same allow-list and Map-only rules. */
    public static Resolution resolveSelector(String selector, Map<String, Object> context) {
        if (selector == null || selector.length() > MAX_SELECTOR_LENGTH
                || !SELECTOR_PATTERN.matcher(selector).matches() || context == null) {
            return Resolution.missing();
        }
        String[] segments = selector.split("\\.");
        String root = segments[0];
        if ("node_outputs".equals(root)) {
            // A concrete node and field are required; resolving the whole pool would bypass the
            // node-level contract and could unintentionally expose unrelated runtime state.
            return segments.length < 3 ? Resolution.missing() : traverse(context, segments, 0);
        }
        if (isContextRoot(root)) {
            return traverse(context, segments, 0);
        }
        if (segments.length >= 2) {
            Object outputs = context.get("node_outputs");
            if (outputs instanceof Map<?, ?> outputMap && outputMap.containsKey(root)) {
                return traverse(outputs, segments, 0);
            }
        }
        return Resolution.missing();
    }

    public static List<NodeReference> extractNodeReferences(String source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<NodeReference> references = new ArrayList<>();
        Matcher matcher = TEMPLATE_PATTERN.matcher(source);
        while (matcher.find()) {
            String selector = selector(matcher);
            String[] segments = selector.split("\\.");
            if (segments.length >= 3 && "node_outputs".equals(segments[0])) {
                references.add(new NodeReference(
                        segments[1], List.of(segments).subList(2, segments.length), matcher.group(), true));
            } else if (segments.length >= 2 && !isContextRoot(segments[0])) {
                references.add(new NodeReference(
                        segments[0], List.of(segments).subList(1, segments.length), matcher.group(), true));
            }
        }
        return List.copyOf(references);
    }

    public static boolean usesContextRoot(String source, String root) {
        if (source == null || source.isEmpty() || root == null) {
            return false;
        }
        Matcher matcher = TEMPLATE_PATTERN.matcher(source);
        while (matcher.find()) {
            String selector = selector(matcher);
            if (selector.equals(root) || selector.startsWith(root + ".")) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> outputFields(WorkflowNodeType nodeType) {
        return OUTPUT_FIELDS.getOrDefault(nodeType, Set.of());
    }

    public static boolean isReservedNodeKey(String nodeKey) {
        return "node_outputs".equals(nodeKey) || isContextRoot(nodeKey);
    }

    public static boolean isValidSelector(String selector) {
        return selector != null && selector.length() <= MAX_SELECTOR_LENGTH
                && SELECTOR_PATTERN.matcher(selector).matches();
    }

    public static Map<String, Object> immutableOutput(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        output.forEach((key, value) -> snapshot.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    public static Map<String, Map<String, Object>> immutableNodeOutputs(
            Map<String, ? extends Map<String, Object>> nodeOutputs
    ) {
        if (nodeOutputs == null || nodeOutputs.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
        nodeOutputs.forEach((key, value) -> snapshot.put(key, immutableOutput(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    public static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> snapshot = new LinkedHashMap<>();
            map.forEach((key, nested) -> snapshot.put(key, immutableValue(nested)));
            return Collections.unmodifiableMap(snapshot);
        }
        if (value instanceof List<?> list) {
            List<Object> snapshot = new ArrayList<>(list.size());
            list.forEach(item -> snapshot.add(immutableValue(item)));
            return Collections.unmodifiableList(snapshot);
        }
        return value;
    }

    public static Object primaryOutput(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return output == null ? Map.of() : output;
        }
        for (String key : List.of(
                "llm_output", "tool_result", "retrieval_context", "final_output", "user_input")) {
            if (output.containsKey(key)) {
                return output.get(key);
            }
        }
        if (output.size() == 1) {
            return output.values().iterator().next();
        }
        return output;
    }

    public static String renderTextValue(Object value, ObjectMapper objectMapper) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(
                    "WORKFLOW_VARIABLE_VALUE_INVALID",
                    "Workflow variable value could not be serialized",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private static Resolution traverse(Object root, String[] segments, int offset) {
        Object current = root;
        for (int i = offset; i < segments.length; i++) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segments[i])) {
                return Resolution.missing();
            }
            current = map.get(segments[i]);
        }
        return new Resolution(true, current);
    }

    private static boolean isContextRoot(String root) {
        return "user_input".equals(root)
                || "node_input".equals(root)
                || "node_inputs".equals(root)
                || LEGACY_FLAT_FIELDS.contains(root);
    }

    private static String selector(Matcher matcher) {
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private static void ensureLength(
            long length,
            int maxResolvedLength,
            Supplier<? extends RuntimeException> tooLong
    ) {
        if (length > maxResolvedLength) {
            throw tooLong.get();
        }
    }

    public record Resolution(boolean found, Object value) {
        private static Resolution missing() {
            return new Resolution(false, null);
        }
    }

    public record NodeReference(
            String nodeKey,
            List<String> fieldPath,
            String template,
            boolean explicit
    ) {
        public NodeReference {
            fieldPath = List.copyOf(fieldPath);
        }
    }
}
