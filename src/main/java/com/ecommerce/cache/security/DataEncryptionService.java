package com.ecommerce.cache.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感数据加密服务
 *
 * 算法: AES-256-GCM (Authenticated Encryption with Associated Data)
 * - 认证加密: 同时提供机密性和完整性保证
 * - 每次加密使用唯一 IV (96 bit)
 * - 输出格式: Base64(IV + Ciphertext + AuthTag)
 *
 * 用途:
 * - Redis 缓存数据透明加密
 * - 敏感字段（价格、用户信息）落盘加密
 * - API 响应中的敏感数据加密传输
 */
@Service
public class DataEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(DataEncryptionService.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;       // 96 bits
    private static final int GCM_TAG_LENGTH = 128;     // 128 bits auth tag

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;
    private final boolean enabled;

    public DataEncryptionService(
            @Value("${security.encryption.key:}") String configKey,
            @Value("${security.encryption.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.secureRandom = new SecureRandom();

        if (configKey != null && !configKey.isEmpty()) {
            // 从配置加载密钥（Base64 编码的 256-bit 密钥）
            byte[] keyBytes = Base64.getDecoder().decode(configKey);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("Encryption key must be 256 bits (32 bytes), got: " + keyBytes.length);
            }
            this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
            log.info("Data encryption initialized with configured key, enabled={}", enabled);
        } else {
            // 自动生成密钥（仅适合单实例，集群需配置共享密钥）
            this.secretKey = generateKey();
            log.warn("Data encryption using auto-generated key (NOT suitable for cluster). " +
                     "Set security.encryption.key for production. enabled={}", enabled);
        }
    }

    /**
     * 加密字符串数据
     * @return Base64 编码的密文 (IV + Ciphertext + AuthTag)
     */
    public String encrypt(String plaintext) {
        if (!enabled || plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 合并 IV + ciphertext
            ByteBuffer buffer = ByteBuffer.allocate(GCM_IV_LENGTH + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new SecurityException("Data encryption failed", e);
        }
    }

    /**
     * 解密 Base64 编码的密文
     * @return 明文字符串
     */
    public String decrypt(String encryptedBase64) {
        if (!enabled || encryptedBase64 == null || encryptedBase64.isEmpty()) {
            return encryptedBase64;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedBase64);

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new SecurityException("Data decryption failed", e);
        }
    }

    /**
     * 加密字节数据
     */
    public byte[] encryptBytes(byte[] data) {
        if (!enabled || data == null || data.length == 0) {
            return data;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            byte[] ciphertext = cipher.doFinal(data);

            ByteBuffer buffer = ByteBuffer.allocate(GCM_IV_LENGTH + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);
            return buffer.array();
        } catch (Exception e) {
            log.error("Byte encryption failed", e);
            throw new SecurityException("Data encryption failed", e);
        }
    }

    /**
     * 解密字节数据
     */
    public byte[] decryptBytes(byte[] encryptedData) {
        if (!enabled || encryptedData == null || encryptedData.length == 0) {
            return encryptedData;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(encryptedData);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            log.error("Byte decryption failed", e);
            throw new SecurityException("Data decryption failed", e);
        }
    }

    /**
     * 判断给定的 Base64 字符串是否是加密数据
     * （启发式检测：解码后长度 > 12 字节且非 JSON 开头）
     */
    public boolean isEncrypted(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(data);
            return decoded.length > GCM_IV_LENGTH && !data.startsWith("{") && !data.startsWith("[");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 生成 AES-256 密钥
     */
    private static SecretKey generateKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
            keyGen.init(256);
            return keyGen.generateKey();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate AES key", e);
        }
    }

    /**
     * 生成 Base64 编码的密钥字符串（用于配置文件）
     */
    public static String generateKeyBase64() {
        SecretKey key = generateKey();
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
