package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.config.ProcurementDocumentProperties;
import com.example.agentservice.procurement.domain.DocumentGenerationTask;
import com.example.agentservice.procurement.domain.DocumentVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Redis-backed transient storage for procurement draft task snapshots. */
@Repository
public class ProcurementTaskRedisStore {

    private static final String TENDER_PREFIX = "procurement:document-task:tender:";
    private static final String BID_PREFIX = "procurement:document-task:bid:";
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

    public void saveBid(BidTaskState state) {
        save(BID_PREFIX + state.task().taskId(), state);
    }

    public Optional<BidTaskState> findBid(String taskId) {
        return find(BID_PREFIX + taskId, BidTaskState.class);
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
        redisTemplate.keys(pattern).forEach(key -> find(key, DocumentVersionSnapshot.class).ifPresent(versions::add));
        return versions.stream()
                .sorted(Comparator.comparingInt(value -> value.version().versionNumber()))
                .toList();
    }

    private void save(String key, Object state) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(state), taskTtl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化采购文档任务状态", exception);
        }
    }

    private <T> Optional<T> find(String key, Class<T> stateType) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, stateType));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取采购文档任务状态", exception);
        }
    }

    public record TenderTaskState(
            DocumentGenerationTask task,
            TenderDocumentGenerationService.DraftGenerationResult result,
            String review,
            String sourceText,
            String originalDraft,
            TenderDraftConsistencyChecker.ConsistencyResult originalConsistency,
            String originalReview,
            boolean autoRevisionApplied
    ) {
    }

    public record BidTaskState(
            DocumentGenerationTask task,
            String draft
    ) {
    }

    /** Immutable Markdown snapshot together with its version metadata. */
    public record DocumentVersionSnapshot(
            DocumentVersion version,
            String markdown,
            TenderDocumentGenerationService.DraftGenerationResult consistencyResult,
            String review
    ) {
    }
}
