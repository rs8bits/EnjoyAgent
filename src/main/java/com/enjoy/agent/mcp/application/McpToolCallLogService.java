package com.enjoy.agent.mcp.application;

import com.enjoy.agent.chat.application.PreparedChatTurn;
import com.enjoy.agent.mcp.domain.entity.McpToolCallLog;
import com.enjoy.agent.mcp.domain.enums.McpToolCallStatus;
import com.enjoy.agent.mcp.infrastructure.persistence.McpToolCallLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP Tool 调用日志应用服务。
 */
@Service
public class McpToolCallLogService {

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(authorization|password|passwd|secret|token|api[-_]?key|credential|cookie).*"
    );
    private static final Pattern BEARER_VALUE = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+\\-/]+=*");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)((?:password|secret|token|api[-_]?key)\\s*[=:]\\s*)[^\\s,;&]+"
    );

    private final McpToolCallLogRepository mcpToolCallLogRepository;
    private final ObjectMapper objectMapper;

    public McpToolCallLogService(McpToolCallLogRepository mcpToolCallLogRepository, ObjectMapper objectMapper) {
        this.mcpToolCallLogRepository = mcpToolCallLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recordSuccess(
            PreparedChatTurn preparedChatTurn,
            PreparedMcpTool tool,
            String toolCallId,
            String requestPayload,
            McpToolCallResult result,
            Long latencyMs
    ) {
        McpToolCallLog log = createBaseLog(preparedChatTurn, tool, toolCallId);
        log.setStatus(McpToolCallStatus.SUCCESS);
        log.setLatencyMs(latencyMs);
        log.setRequestPayload(redactPayload(requestPayload, 4000));
        log.setResponsePayload(redactPayload(result.rawResponsePayload(), 4000));
        mcpToolCallLogRepository.save(log);
    }

    @Transactional
    public void recordFailure(
            PreparedChatTurn preparedChatTurn,
            PreparedMcpTool tool,
            String toolCallId,
            String requestPayload,
            McpToolCallFailure failure
    ) {
        recordFailure(preparedChatTurn, tool, toolCallId, requestPayload, null, failure);
    }

    @Transactional
    public void recordFailure(
            PreparedChatTurn preparedChatTurn,
            PreparedMcpTool tool,
            String toolCallId,
            String requestPayload,
            String responsePayload,
            McpToolCallFailure failure
    ) {
        McpToolCallLog log = createBaseLog(preparedChatTurn, tool, toolCallId);
        log.setStatus(McpToolCallStatus.FAILED);
        log.setLatencyMs(failure.getLatencyMs());
        log.setRequestPayload(redactPayload(requestPayload, 4000));
        log.setResponsePayload(redactPayload(responsePayload, 4000));
        log.setErrorCode(failure.getCode());
        log.setErrorMessage(truncate(redactText(failure.getMessage()), 1000));
        mcpToolCallLogRepository.save(log);
    }

    private McpToolCallLog createBaseLog(PreparedChatTurn preparedChatTurn, PreparedMcpTool tool, String toolCallId) {
        McpToolCallLog log = new McpToolCallLog();
        log.setTenantId(preparedChatTurn.tenantId());
        log.setUserId(preparedChatTurn.userId());
        log.setAgentId(preparedChatTurn.agentId());
        log.setSessionId(preparedChatTurn.sessionId());
        log.setUserMessageId(preparedChatTurn.userMessageId());
        log.setServerId(tool.server().id());
        log.setToolId(tool.id());
        log.setToolCallId(toolCallId);
        log.setToolName(tool.name());
        return log;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String redactPayload(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            redactJson(root);
            return truncate(objectMapper.writeValueAsString(root), maxLength);
        } catch (Exception ignored) {
            return truncate(redactText(value), maxLength);
        }
    }

    private void redactJson(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SENSITIVE_KEY.matcher(field.getKey()).matches()) {
                    objectNode.put(field.getKey(), "[REDACTED]");
                } else if (field.getValue().isTextual()) {
                    objectNode.put(field.getKey(), redactText(field.getValue().asText()));
                } else {
                    redactJson(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            for (int index = 0; index < arrayNode.size(); index++) {
                JsonNode item = arrayNode.get(index);
                if (item.isTextual()) {
                    arrayNode.set(index, objectMapper.getNodeFactory().textNode(redactText(item.asText())));
                } else {
                    redactJson(item);
                }
            }
        }
    }

    private String redactText(String value) {
        if (value == null) {
            return null;
        }
        String withoutBearer = BEARER_VALUE.matcher(value).replaceAll("$1[REDACTED]");
        return KEY_VALUE_SECRET.matcher(withoutBearer).replaceAll("$1[REDACTED]");
    }
}
