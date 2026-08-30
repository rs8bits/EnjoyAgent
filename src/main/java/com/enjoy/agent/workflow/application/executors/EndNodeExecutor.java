package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowVariableResolver;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class EndNodeExecutor implements WorkflowNodeExecutor {

    static final String FINAL_OUTPUT_KEY = "final_output";
    private static final int MAX_FINAL_OUTPUT_LENGTH = 100_000;

    private final ObjectMapper objectMapper;

    public EndNodeExecutor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String nodeType() {
        return "END";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        String finalOutput = resolveFinalOutput(ctx);
        return new NodeExecutionResult(null, Map.of(FINAL_OUTPUT_KEY, finalOutput));
    }

    private String resolveFinalOutput(NodeExecutionContext ctx) {
        EndNodeConfig config = parseConfig(ctx.node().getConfigJson());
        if (config.outputTemplate() != null && !config.outputTemplate().isBlank()) {
            Object resolved = WorkflowVariableResolver.resolveTypedTemplate(
                    config.outputTemplate(), ctx.context(), objectMapper, MAX_FINAL_OUTPUT_LENGTH,
                    this::outputTooLong);
            return WorkflowVariableResolver.renderTextValue(resolved, objectMapper);
        }
        if (config.outputSelector() != null && !config.outputSelector().isBlank()) {
            String selector = config.outputSelector().trim();
            if (selector.startsWith("{{") && selector.endsWith("}}")) {
                Object resolved = WorkflowVariableResolver.resolveTypedTemplate(
                        selector, ctx.context(), objectMapper, MAX_FINAL_OUTPUT_LENGTH,
                        this::outputTooLong);
                return WorkflowVariableResolver.renderTextValue(resolved, objectMapper);
            }
            WorkflowVariableResolver.Resolution resolved =
                    WorkflowVariableResolver.resolveSelector(selector, ctx.context());
            if (resolved.found()) {
                String output = WorkflowVariableResolver.renderTextValue(resolved.value(), objectMapper);
                ensureOutputLength(output);
                return output;
            }
            return "{{" + selector + "}}";
        }
        if (ctx.context().containsKey("node_input") && ctx.context().get("node_input") != null) {
            String output = WorkflowVariableResolver.renderTextValue(ctx.context().get("node_input"), objectMapper);
            ensureOutputLength(output);
            return output;
        }
        Object llmOutput = ctx.context().get("llm_output");
        if (llmOutput != null) {
            String output = WorkflowVariableResolver.renderTextValue(llmOutput, objectMapper);
            ensureOutputLength(output);
            return output;
        }
        for (String key : ctx.context().keySet()) {
            if (key.endsWith("_output")) {
                String output = WorkflowVariableResolver.renderTextValue(ctx.context().get(key), objectMapper);
                ensureOutputLength(output);
                return output;
            }
        }
        return "";
    }

    private EndNodeConfig parseConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson == null ? "{}" : configJson, EndNodeConfig.class);
        } catch (JsonProcessingException ex) {
            throw new ApiException(
                    "WORKFLOW_NODE_CONFIG_INVALID", "Invalid END node config JSON", HttpStatus.BAD_REQUEST);
        }
    }

    private void ensureOutputLength(String output) {
        if (output.length() > MAX_FINAL_OUTPUT_LENGTH) {
            throw outputTooLong();
        }
    }

    private ApiException outputTooLong() {
        return new ApiException(
                "WORKFLOW_FINAL_OUTPUT_TOO_LONG",
                "Resolved workflow final output must be at most " + MAX_FINAL_OUTPUT_LENGTH + " characters",
                HttpStatus.BAD_REQUEST);
    }

    private record EndNodeConfig(String outputTemplate, String outputSelector) {
    }
}
