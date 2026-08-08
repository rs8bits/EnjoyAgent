package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EndNodeExecutor implements WorkflowNodeExecutor {

    static final String FINAL_OUTPUT_KEY = "final_output";

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
        Object llmOutput = ctx.context().get("llm_output");
        if (llmOutput != null) {
            return llmOutput.toString();
        }
        for (String key : ctx.context().keySet()) {
            if (key.endsWith("_output")) {
                return ctx.context().get(key).toString();
            }
        }
        return "";
    }
}
