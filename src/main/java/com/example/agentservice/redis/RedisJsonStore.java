package com.example.agentservice.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 通用 Redis JSON 存取组件。
 *
 * <p>只负责 JSON 序列化读写、TTL 写入和 Redis 瞬时故障重试，不感知任何业务 key：
 * key 前缀、TTL 与值类型都由调用方决定，各业务包按自己的命名空间复用本组件。</p>
 */
@Component
public class RedisJsonStore {

    private static final Logger log = LoggerFactory.getLogger(RedisJsonStore.class);
    private static final long[] RETRY_DELAYS_MS = {500L, 1_000L, 2_000L};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisJsonStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 以 JSON 写入并设置过期时间。 */
    public void save(String key, Object value, Duration ttl) {
        if (ttl == null) {
            throw new IllegalArgumentException("Redis 过期时间不能为空");
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            execute("保存 Redis 值", () -> {
                redisTemplate.opsForValue().set(key, json, ttl);
                return null;
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 Redis 值", exception);
        }
    }

    /** 读取并反序列化，键不存在时返回空。 */
    public <T> Optional<T> find(String key, Class<T> type) {
        String value = execute("读取 Redis 值", () -> redisTemplate.opsForValue().get(key));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, type));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取 Redis 值", exception);
        }
    }

    /** 按模式列举键；仅适合命名空间较小的场景。 */
    public List<String> keys(String pattern) {
        Set<String> keys = execute("查询 Redis 键", () -> redisTemplate.keys(pattern));
        return keys == null ? List.of() : List.copyOf(keys);
    }

    /** Redis 短暂断线时 Lettuce 会自动重连，这里只重试可安全重复执行的任务读写。 */
    public <T> T execute(String operation, Supplier<T> command) {
        for (int attempt = 0;; attempt++) {
            try {
                return command.get();
            } catch (DataAccessException exception) {
                if (attempt == RETRY_DELAYS_MS.length) {
                    throw exception;
                }
                long delay = RETRY_DELAYS_MS[attempt];
                log.warn("{}遇到 Redis 瞬时异常，{}ms 后进行第{}次重试", operation, delay, attempt + 1);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }
}
