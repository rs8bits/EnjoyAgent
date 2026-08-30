package com.enjoy.agent.mcp.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class McpControllerAuthorizationTest {

    @Test
    void everyMcpConfigurationMutationRequiresTenantOwner() {
        assertOwner(McpServerController.class, "createServer");
        assertOwner(McpServerController.class, "updateServer");
        assertOwner(McpServerController.class, "deleteServer");
        assertOwner(McpServerController.class, "replaceServerTools");
        assertOwner(McpServerController.class, "syncServerTools");
        assertOwner(McpOAuthController.class, "startAuthorization");
        assertOwner(McpOAuthController.class, "connectClientCredentials");
        assertOwner(McpOAuthController.class, "disconnect");
        assertOwner(AgentToolBindingController.class, "replaceBindings");
    }

    @Test
    void perInvocationHighRiskApprovalRemainsAvailableToMembers() {
        Method method = findMethod(McpToolController.class, "approveHighRiskTool");
        assertThat(method.getAnnotation(PreAuthorize.class)).isNull();
    }

    private void assertOwner(Class<?> controller, String methodName) {
        PreAuthorize annotation = findMethod(controller, methodName).getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as(controller.getSimpleName() + "." + methodName)
                .isNotNull();
        assertThat(annotation.value()).isEqualTo("hasRole('OWNER')");
    }

    private Method findMethod(Class<?> controller, String methodName) {
        return java.util.Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
    }
}
