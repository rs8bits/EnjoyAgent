package com.enjoy.agent.shared.crypto;

import com.enjoy.agent.shared.exception.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 基于 AES-GCM 的对称加密组件。
 */
@Component
public class AesCryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec secretKeySpec;

    public AesCryptoService(CredentialProperties credentialProperties) {
        this.secretKeySpec = new SecretKeySpec(
                credentialProperties.getAesKey().getBytes(StandardCharsets.UTF_8),
                "AES"
        );
    }

    /**
     * 加密明文，并把 IV 和密文一起编码成 Base64 字符串。
     */
    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException ex) {
            throw new ApiException("CREDENTIAL_ENCRYPTION_FAILED", "Failed to encrypt credential secret", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 解密密文，供后续真正调用模型时使用。
     */
    public String decrypt(String cipherText) {
        try {
            byte[] payload = Base64.getDecoder().decode(cipherText);
            ByteBuffer buffer = ByteBuffer.wrap(payload);

            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);

            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new ApiException("CREDENTIAL_DECRYPTION_FAILED", "Failed to decrypt credential secret", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
