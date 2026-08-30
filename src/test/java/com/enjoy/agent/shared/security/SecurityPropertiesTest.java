package com.enjoy.agent.shared.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {

    @Test
    void rejectsMissingAndKnownDevelopmentSecretsByDefault() {
        SecurityProperties missing = new SecurityProperties();
        assertThrows(IllegalStateException.class, missing::validate);

        SecurityProperties insecure = properties("change-me-to-a-long-random-string", false);
        assertThrows(IllegalStateException.class, insecure::validate);
    }

    @Test
    void allowsDevelopmentSecretOnlyWhenExplicitlyEnabled() {
        SecurityProperties properties = properties("change-me-to-a-long-random-string", true);
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void acceptsStrongProductionSecret() {
        SecurityProperties properties = properties("a-strong-production-secret-with-more-than-32-bytes", false);
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void rejectsMalformedHistoricalSigningKeys() {
        SecurityProperties properties = properties("a-strong-production-secret-with-more-than-32-bytes", false);
        properties.setPreviousJwtKeys(List.of("missing-secret"));
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void rejectsProductionTokenExposureAndInvalidCorsOrigin() {
        SecurityProperties exposed = properties("a-strong-production-secret-with-more-than-32-bytes", false);
        exposed.setExposeAccessTokenInBody(true);
        assertThrows(IllegalStateException.class, exposed::validate);

        SecurityProperties wildcard = properties("a-strong-production-secret-with-more-than-32-bytes", false);
        wildcard.setAllowedBrowserOrigins(List.of("https://*.example.com"));
        assertThrows(IllegalStateException.class, wildcard::validate);
    }

    private SecurityProperties properties(String secret, boolean allowDevelopmentSecret) {
        SecurityProperties properties = new SecurityProperties();
        properties.setJwtSecret(secret);
        properties.setAllowInsecureDevelopmentSecret(allowDevelopmentSecret);
        return properties;
    }
}
