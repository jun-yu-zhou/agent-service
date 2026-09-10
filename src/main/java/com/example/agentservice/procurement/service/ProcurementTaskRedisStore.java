package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.config.ProcurementDocumentProperties;
import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentVersion;
import com.example.agentservice.redis.RedisJsonStore;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 使用 Redis 临时保存采购文档初稿任务快照，key 前缀与 TTL 由本类定义。 */
@Repository
public class ProcurementTaskRedisStore {

    private static final String TENDER_PREFIX = "procurement:document-task:tender:";
    private static final String VERSION_PREFIX = "procurement:document-version:";

    private final RedisJsonStore redis;
    private final Duration taskTtl;

    public ProcurementTaskRedisStore(RedisJsonStore redis, ProcurementDocumentProperties properties) {
        this.redis = redis;
        this.taskTtl = Duration.ofHours(Math.max(1, properties.getTaskTtlHours()));
    }

    public void saveTender(TenderTaskState state) {
        redis.save(TENDER_PREFIX + state.task().taskId(), state, taskTtl);
    }

    public Optional<TenderTaskState> findTender(String taskId) {
        return redis.find(TENDER_PREFIX + taskId, TenderTaskState.class);
    }

    public void saveVersion(DocumentVersionSnapshot version) {
        redis.save(VERSION_PREFIX + version.version().taskId() + ":" + version.version().versionId(), version, taskTtl);
    }

    public Optional<DocumentVersionSnapshot> findVersion(String taskId, String versionId) {
        return redis.find(VERSION_PREFIX + taskId + ":" + versionId, DocumentVersionSnapshot.class);
    }

    public List<DocumentVersionSnapshot> findVersions(String taskId) {
        return redis.keys(VERSION_PREFIX + taskId + ":*").stream()
                .map(key -> redis.find(key, DocumentVersionSnapshot.class))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt(value -> value.version().versionNumber()))
                .toList();
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
