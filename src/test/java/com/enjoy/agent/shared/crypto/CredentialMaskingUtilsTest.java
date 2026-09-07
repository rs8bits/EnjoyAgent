package com.enjoy.agent.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CredentialMaskingUtilsTest {

    @Test
    void shortSecret_shouldNeverRevealAnyOriginalCharacter() {
        assertThat(CredentialMaskingUtils.mask("abcd")).isEqualTo("****");
        assertThat(CredentialMaskingUtils.mask("12345678")).isEqualTo("****");
    }

    @Test
    void longSecret_shouldKeepOnlySmallPrefixAndSuffixForRecognition() {
        String secret = "sk-1234567890abcdef";

        String masked = CredentialMaskingUtils.mask(secret);

        assertThat(masked).isEqualTo("sk-1****cdef");
        assertThat(masked).doesNotContain("234567890ab");
    }
}
