package com.enjoy.agent.mcp.application;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** HMAC key material dedicated exclusively to HIGH-risk MCP approvals. */
@ConfigurationProperties(prefix = "enjoy.mcp.approval")
public class McpApprovalProperties {

    static final String INSECURE_DEVELOPMENT_SECRET =
            "development-only-mcp-approval-signing-secret";

    private String signingSecret;
    private List<String> previousSigningSecrets = new ArrayList<>();
    private boolean allowInsecureDevelopmentSecret;

    @PostConstruct
    public void validate() {
        validateSecret(signingSecret, "signing-secret");
        for (String previous : previousSigningSecrets) {
            validateSecret(previous, "previous-signing-secrets");
        }
    }

    private void validateSecret(String secret, String property) {
        int bytes = secret == null ? 0 : secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 32) {
            throw new IllegalStateException("enjoy.mcp.approval." + property
                    + " entries must be at least 32 bytes long");
        }
        if (!allowInsecureDevelopmentSecret && INSECURE_DEVELOPMENT_SECRET.equals(secret)) {
            throw new IllegalStateException("enjoy.mcp.approval." + property
                    + " must not use the development default");
        }
    }

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }

    public List<String> getPreviousSigningSecrets() { return List.copyOf(previousSigningSecrets); }
    public void setPreviousSigningSecrets(List<String> values) {
        this.previousSigningSecrets = values == null ? new ArrayList<>()
                : values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    public boolean isAllowInsecureDevelopmentSecret() { return allowInsecureDevelopmentSecret; }
    public void setAllowInsecureDevelopmentSecret(boolean value) {
        this.allowInsecureDevelopmentSecret = value;
    }
}
