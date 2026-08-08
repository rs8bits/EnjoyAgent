package com.enjoy.agent.workflow.application;

public interface WorkflowNodeExecutor {

    String nodeType();

    NodeExecutionResult execute(NodeExecutionContext ctx);
}
