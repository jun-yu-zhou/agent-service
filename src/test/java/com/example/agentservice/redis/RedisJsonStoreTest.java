package com.example.agentservice.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisJsonStoreTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final RedisJsonStore store = new RedisJsonStore(redisTemplate, new ObjectMapper());

    @Test
    void shouldSaveJsonWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        store.save("demo:1", new Payload("正文"), Duration.ofMinutes(30));

        verify(valueOperations).set("demo:1", "{\"text\":\"正文\"}", Duration.ofMinutes(30));
    }

    @Test
    void shouldFindSavedJson() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("demo:1")).thenReturn("{\"text\":\"正文\"}");

        assertEquals(Optional.of(new Payload("正文")), store.find("demo:1", Payload.class));
    }

    @Test
    void shouldReturnEmptyWhenKeyMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertTrue(store.find("demo:missing", Payload.class).isEmpty());
    }

    @Test
    void shouldPassPatternToKeys() {
        when(redisTemplate.keys("demo:*")).thenReturn(new LinkedHashSet<>(List.of("demo:1", "demo:2")));

        assertEquals(List.of("demo:1", "demo:2"), store.keys("demo:*"));
    }

    @Test
    void shouldRetryThenFailOnPersistentFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("断线"));

        assertThrows(RedisConnectionFailureException.class, () -> store.find("demo:1", Payload.class));
        verify(valueOperations, org.mockito.Mockito.times(4)).get("demo:1");
    }

    @Test
    void shouldRejectMissingTtl() {
        assertThrows(IllegalArgumentException.class, () -> store.save("demo:1", new Payload("正文"), null));
    }

    @Test
    void shouldRejectUnserializableValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertThrows(IllegalStateException.class,
                () -> store.save("demo:1", new Object() {
                    public String getText() throws Exception {
                        throw new Exception("无法读取属性");
                    }
                }, Duration.ofMinutes(1)));
    }

    record Payload(String text) {
    }
}
