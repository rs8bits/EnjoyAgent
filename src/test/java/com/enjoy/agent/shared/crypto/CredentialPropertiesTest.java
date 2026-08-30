package com.enjoy.agent.shared.crypto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CredentialPropertiesTest {

    @Test
    void rejectsMissingAndKnownDevelopmentKeysByDefault() {
        CredentialProperties missing = new CredentialProperties();
        assertThrows(IllegalStateException.class, missing::validate);

        CredentialProperties insecure = properties("0123456789abcdef0123456789abcdef", false);
        assertThrows(IllegalStateException.class, insecure::validate);
    }

    @Test
    void allowsDevelopmentKeyOnlyWhenExplicitlyEnabled() {
        CredentialProperties properties = properties("0123456789abcdef0123456789abcdef", true);
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void acceptsStrongProductionKey() {
        CredentialProperties properties = properties("production-aes-key-32-bytes-long", false);
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void validatesHistoricalRotationKeys() {
        CredentialProperties properties = properties("production-aes-key-32-bytes-long", false);
        properties.setPreviousAesKeys(List.of("short"));
        assertThrows(IllegalStateException.class, properties::validate);
    }

    private CredentialProperties properties(String key, boolean allowDevelopmentSecret) {
        CredentialProperties properties = new CredentialProperties();
        properties.setAesKey(key);
        properties.setAllowInsecureDevelopmentSecret(allowDevelopmentSecret);
        return properties;
    }
}
