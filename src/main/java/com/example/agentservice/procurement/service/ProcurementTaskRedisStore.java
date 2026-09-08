package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.config.ProcurementDocumentProperties;
import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** 使用 Redis 临时保存采购文档初稿任务快照。 */
@Repository
public class ProcurementTaskRedisStore {

    private static final Logger log = LoggerFactory.getLogger(ProcurementTaskRedisStore.class);
    private static final long[] RETRY_DELAYS_MS = {500L, 1_000L, 2_000L};
    private static final String TENDER_PREFIX = "procurement:document-task:tender:";
    private static final String VERSION_PREFIX = "procurement:document-version:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration taskTtl;

    public ProcurementTaskRedisStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            ProcurementDocumentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.taskTtl = Duration.ofHours(Math.max(1, properties.getTaskTtlHours()));
    }

    public void saveTender(TenderTaskState state) {
        save(TENDER_PREFIX + state.task().taskId(), state);
    }

    public Optional<TenderTaskState> findTender(String taskId) {
        return find(TENDER_PREFIX + taskId, TenderTaskState.class);
    }

    public void saveVersion(DocumentVersionSnapshot version) {
        save(VERSION_PREFIX + version.version().taskId() + ":" + version.version().versionId(), version);
    }

    public Optional<DocumentVersionSnapshot> findVersion(String taskId, String versionId) {
        return find(VERSION_PREFIX + taskId + ":" + versionId, DocumentVersionSnapshot.class);
    }

    public List<DocumentVersionSnapshot> findVersions(String taskId) {
        String pattern = VERSION_PREFIX + taskId + ":*";
        List<DocumentVersionSnapshot> versions = new ArrayList<>();
        redis("查询文档版本", () -> redisTemplate.keys(pattern))
                .forEach(key -> find(key, DocumentVersionSnapshot.class).ifPresent(versions::add));
        return versions.stream()
                .sorted(Comparator.comparingInt(value -> value.version().versionNumber()))
                .toList();
    }

    private void save(String key, Object state) {
        try {
            String value = objectMapper.writeValueAsString(state);
            redis("保存采购文档任务", () -> {
                redisTemplate.opsForValue().set(key, value, taskTtl);
                return null;
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化采购文档任务状态", exception);
        }
    }

    private <T> Optional<T> find(String key, Class<T> stateType) {
        String value = redis("读取采购文档任务", () -> redisTemplate.opsForValue().get(key));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, stateType));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取采购文档任务状态", exception);
        }
    }

    /** Redis 短暂断线时 Lettuce 会自动重连，这里只重试可安全重复执行的任务读写。 */
    private <T> T redis(String operation, Supplier<T> command) {
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

    public record TenderTaskState(
            DocumentGenerationTask task,
            String draft
    ) {
    }

    /** 不可变的 Markdown 内容快照及其版本元数据。 */
    public record DocumentVersionSnapshot(
            DocumentVersion version,
            String markdown
    ) {
    }
}
