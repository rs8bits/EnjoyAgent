package com.enjoy.agent.workflow.application.executors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.shared.exception.ApiException;
import com.enjoy.agent.workflow.application.NodeExecutionContext;
import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import com.enjoy.agent.workflow.domain.enums.WorkflowNodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

class ToolNodeExecutorTest {

    @Test
    void execute_shouldRequireAgentContextBeforeLoadingTools() {
        ToolNodeExecutor executor = new ToolNodeExecutor(
                Mockito.mock(com.enjoy.agent.mcp.application.McpRuntimeService.class),
                Mockito.mock(com.enjoy.agent.mcp.application.McpGatewayService.class),
                new ObjectMapper()
        );

        WorkflowNode node = new WorkflowNode();
        node.setNodeType(WorkflowNodeType.TOOL);
        node.setName("工具调用");
        node.setConfigJson("{\"toolName\":\"search\"}");

        NodeExecutionContext context = new NodeExecutionContext(
                7L,
                11L,
                null,
                null,
                node,
                null,
                new HashMap<>()
        );

        assertThatThrownBy(() -> executor.execute(context))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiException = (ApiException) ex;
                    org.assertj.core.api.Assertions.assertThat(apiException.getCode())
                            .isEqualTo("WORKFLOW_TEST_AGENT_REQUIRED");
                    org.assertj.core.api.Assertions.assertThat(apiException.getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }
}
