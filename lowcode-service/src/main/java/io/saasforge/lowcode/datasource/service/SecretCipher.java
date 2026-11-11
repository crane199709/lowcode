package io.saasforge.lowcode.datasource.service;

import io.saasforge.lowcode.datasource.config.DataSourceSecurityProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** 用 AES-GCM 加密数据源密码：每次随机 IV，密文为 Base64(IV + 密文与认证标签)，被篡改时解密失败而不是得到错误明文。 */
@Component
class SecretCipher {
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    SecretCipher(DataSourceSecurityProperties properties) {
        this.key = new SecretKeySpec(Base64.getDecoder().decode(properties.encryptionKey().trim()), "AES");
    }

    String encrypt(String plain) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder()
                    .encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                            .put(iv)
                            .put(encrypted)
                            .array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt data source secret", e);
        }
    }

    String decrypt(String stored) {
        byte[] bytes = Base64.getDecoder().decode(stored);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to decrypt data source secret", e);
        }
    }
}
