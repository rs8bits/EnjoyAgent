package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StartNodeExecutor implements WorkflowNodeExecutor {

    @Override
    public String nodeType() {
        return "START";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        return new NodeExecutionResult(null, Map.of());
    }
}
