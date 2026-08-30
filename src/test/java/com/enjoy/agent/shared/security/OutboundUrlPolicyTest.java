package com.enjoy.agent.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

class OutboundUrlPolicyTest {

    @Test
    void rejectsPrivateAndCredentialBearingDestinations() {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new OutboundUrlProperties());

        assertThatThrownBy(() -> policy.requireAllowedHttpUrl(
                "http://127.0.0.1:8080/mcp", "URL_INVALID"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.requireAllowedHttpUrl(
                "https://user:pass@8.8.8.8/mcp", "URL_INVALID"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void allowsExplicitLocalDevelopmentHostWithoutGlobMatching() {
        OutboundUrlProperties properties = new OutboundUrlProperties();
        properties.setAllowPlainHttp(true);
        properties.setAllowedHosts(java.util.List.of("127.0.0.1"));
        OutboundUrlPolicy policy = new OutboundUrlPolicy(properties);

        assertThat(policy.requireAllowedHttpUrl("http://127.0.0.1:8080/mcp", "URL_INVALID").toString())
                .isEqualTo("http://127.0.0.1:8080/mcp");
        assertThatThrownBy(() -> policy.requireAllowedHttpUrl(
                "http://127.0.0.2:8080/mcp", "URL_INVALID"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void requiresHttpsByDefault() {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new OutboundUrlProperties());

        assertThatThrownBy(() -> policy.requireAllowedHttpUrl(
                "http://8.8.8.8/model", "URL_INVALID"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("HTTPS");
        assertThat(policy.requireAllowedHttpUrl(
                "https://8.8.8.8/model", "URL_INVALID").getScheme())
                .isEqualTo("https");
    }
}
