package com.enjoy.agent.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enjoy.agent.shared.exception.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AesCryptoServiceTest {

    private static final String OLD_KEY = "old-production-key-32-bytes-long";
    private static final String NEW_KEY = "new-production-key-32-bytes-long";

    @Test
    void emitsVersionedCiphertextAndRoundTrips() {
        AesCryptoService service = service(NEW_KEY, List.of());

        String ciphertext = service.encrypt("sk-secret");

        assertThat(ciphertext).startsWith("v1:");
        assertThat(service.decrypt(ciphertext)).isEqualTo("sk-secret");
    }

    @Test
    void decryptsVersionedAndLegacyCiphertextWithHistoricalKey() throws Exception {
        AesCryptoService oldService = service(OLD_KEY, List.of());
        AesCryptoService rotatedService = service(NEW_KEY, List.of(OLD_KEY));

        assertThat(rotatedService.decrypt(oldService.encrypt("versioned-old")))
                .isEqualTo("versioned-old");
        assertThat(rotatedService.decrypt(legacyEncrypt(OLD_KEY, "legacy-old")))
                .isEqualTo("legacy-old");
    }

    @Test
    void rejectsCiphertextWhenNoConfiguredKeyAuthenticatesIt() {
        String ciphertext = service(OLD_KEY, List.of()).encrypt("secret");

        assertThatThrownBy(() -> service(NEW_KEY, List.of()).decrypt(ciphertext))
                .isInstanceOf(ApiException.class)
                .hasMessage("Failed to decrypt credential secret");
    }

    private AesCryptoService service(String activeKey, List<String> previousKeys) {
        CredentialProperties properties = new CredentialProperties();
        properties.setAesKey(activeKey);
        properties.setPreviousAesKeys(previousKeys);
        properties.validate();
        return new AesCryptoService(properties);
    }

    private String legacyEncrypt(String key, String plaintext) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, iv)
        );
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array()
        );
    }
}
