package com.enjoy.agent.mcp.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class McpApprovalPropertiesTest {

    @Test
    void productionRejectsMissingShortAndDevelopmentSecrets() {
        McpApprovalProperties missing = new McpApprovalProperties();
        assertThatThrownBy(missing::validate).isInstanceOf(IllegalStateException.class);

        McpApprovalProperties shortSecret = new McpApprovalProperties();
        shortSecret.setSigningSecret("too-short");
        assertThatThrownBy(shortSecret::validate).isInstanceOf(IllegalStateException.class);

        McpApprovalProperties development = new McpApprovalProperties();
        development.setSigningSecret(McpApprovalProperties.INSECURE_DEVELOPMENT_SECRET);
        assertThatThrownBy(development::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use the development default");
    }
}
