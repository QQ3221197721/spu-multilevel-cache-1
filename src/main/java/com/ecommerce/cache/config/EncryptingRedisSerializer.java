package com.ecommerce.cache.config;

import com.ecommerce.cache.security.DataEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 透明加密 Redis 序列化器
 *
 * 在 Redis 数据写入前自动加密，读取时自动解密。
 * 包装现有的 StringRedisSerializer，对 Value 层透明处理。
 *
 * 使用场景:
 * - 敏感数据（价格、用户信息）存入 Redis 时自动加密
 * - 防止 Redis 数据泄露（即使 Redis 被攻破，数据不可读）
 *
 * 配置方式: 在 RedisTemplate 中替换默认的 Value 序列化器
 */
public class EncryptingRedisSerializer implements RedisSerializer<String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptingRedisSerializer.class);

    private final StringRedisSerializer delegate = new StringRedisSerializer();
    private final DataEncryptionService encryptionService;

    // 加密标记前缀，用于区分加密和非加密数据（向后兼容）
    private static final String ENCRYPTED_PREFIX = "ENC:";

    public EncryptingRedisSerializer(DataEncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    @Override
    public byte[] serialize(String value) throws SerializationException {
        if (value == null) {
            return null;
        }

        if (!encryptionService.isEnabled()) {
            return delegate.serialize(value);
        }

        try {
            // 加密后加上前缀标记
            String encrypted = ENCRYPTED_PREFIX + encryptionService.encrypt(value);
            return delegate.serialize(encrypted);
        } catch (Exception e) {
            log.warn("Failed to encrypt Redis value, falling back to plain text", e);
            return delegate.serialize(value);
        }
    }

    @Override
    public String deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null) {
            return null;
        }

        String value = delegate.deserialize(bytes);
        if (value == null) {
            return null;
        }

        // 检查是否为加密数据
        if (value.startsWith(ENCRYPTED_PREFIX)) {
            if (!encryptionService.isEnabled()) {
                log.warn("Encrypted data found but encryption is disabled, returning raw data");
                return value;
            }
            try {
                String encryptedPayload = value.substring(ENCRYPTED_PREFIX.length());
                return encryptionService.decrypt(encryptedPayload);
            } catch (Exception e) {
                log.error("Failed to decrypt Redis value, returning raw data", e);
                return value;
            }
        }

        // 非加密数据，直接返回（向后兼容旧数据）
        return value;
    }
}
