package com.enjoy.agent.workflow.application.executors;

import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.application.NodeExecutionResult;
import com.enjoy.agent.workflow.application.WorkflowNodeExecutor;
import java.util.Map;
import java.util.Collections;
import org.springframework.stereotype.Component;

@Component
public class StartNodeExecutor implements WorkflowNodeExecutor {

    @Override
    public String nodeType() {
        return "START";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext ctx) {
        // START is a first-class producer. The global user_input remains for compatibility while
        // the same immutable value is also addressable as startKey.user_input.
        return new NodeExecutionResult(
                null, Collections.singletonMap("user_input", ctx.context().get("user_input")));
    }
}
