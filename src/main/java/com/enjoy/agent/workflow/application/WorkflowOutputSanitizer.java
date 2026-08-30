package com.enjoy.agent.workflow.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.regex.Pattern;

/** Removes secrets and private prompt state before workflow data crosses a durable/SSE boundary. */
public final class WorkflowOutputSanitizer {

    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> OMITTED_KEYS = Set.of(
            "tool_raw_response", "history_messages", "system_prompt",
            "session_memory_summary", "mcp_tool_approval_tokens"
    );
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)(authorization|credential|secret|password|api[_-]?key|access[_-]?token|refresh[_-]?token|approval[_-]?token)");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\\\"(?:authorization|credential|secret|password|api[_-]?key|access[_-]?token|refresh[_-]?token|approval[_-]?token)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");

    public Map<String, Object> sanitizeMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = entry.getKey();
            String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
            if (OMITTED_KEYS.contains(normalized)) {
                continue;
            }
            safe.put(key, SENSITIVE_KEY.matcher(normalized).find()
                    ? REDACTED : sanitizeValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(safe);
    }

    public String sanitizeText(String value) {
        if (value == null) {
            return null;
        }
        String safe = BEARER.matcher(value).replaceAll("Bearer " + REDACTED);
        return JSON_SECRET.matcher(safe).replaceAll("$1" + REDACTED + "$2");
    }

    public DagExecutor.NodeEvent sanitizeEvent(DagExecutor.NodeEvent event) {
        Map<String, Object> data = event.data() == null ? null : sanitizeMap(event.data());
        if ("node_failed".equals(event.type())) {
            data = Map.of("error", "Node execution failed");
        }
        return new DagExecutor.NodeEvent(
                event.type(), event.nodeId(), event.nodeKey(), event.nodeType(), event.nodeName(), data);
    }

    private Object sanitizeValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            return sanitizeText(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, nested) -> normalized.put(String.valueOf(key), nested));
            return sanitizeMap(normalized);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> safe = new ArrayList<>();
            iterable.forEach(item -> safe.add(sanitizeValue(item)));
            return Collections.unmodifiableList(safe);
        }
        // Unknown framework/entity objects are not safe to serialize into public events.
        return "[OMITTED]";
    }
}
