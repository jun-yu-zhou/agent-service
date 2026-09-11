package com.example.agentservice.procurement.service;

import com.example.agentservice.procurement.domain.TenderReviewSnapshot;
import com.example.agentservice.procurement.domain.TenderReviewStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** 管理定稿版本审核报告的异步执行和状态变化。 */
@Service
public class TenderReviewTaskService {

    private final ProcurementTaskRedisStore taskStore;
    private final TenderDocumentReviewService reviewService;
    private final ExecutorService executor;

    public TenderReviewTaskService(
            ProcurementTaskRedisStore taskStore,
            TenderDocumentReviewService reviewService,
            @Qualifier("procurementDocumentExecutor") ExecutorService executor) {
        this.taskStore = taskStore;
        this.reviewService = reviewService;
        this.executor = executor;
    }

    /** 为已定稿版本创建审核任务；重复调用不会启动第二个任务。 */
    public Optional<TenderReviewSnapshot> start(String taskId, String versionId) {
        synchronized (lock(taskId, versionId)) {
            var version = taskStore.findVersion(taskId, versionId);
            if (version.isEmpty()) return Optional.empty();
            if (!version.get().version().finalized()) {
                throw new IllegalStateException("仅已确认定稿版本可以生成审核报告");
            }
            var existing = taskStore.findReview(taskId, versionId);
            if (existing.isPresent()) return existing;

            Instant now = Instant.now();
            TenderReviewSnapshot pending = new TenderReviewSnapshot(
                    taskId, versionId, TenderReviewStatus.PENDING, "等待审核",
                    null, null, now, now);
            taskStore.saveReview(pending);
            submit(pending, version.get());
            return taskStore.findReview(taskId, versionId).or(() -> Optional.of(pending));
        }
    }

    public Optional<TenderReviewSnapshot> find(String taskId, String versionId) {
        return taskStore.findReview(taskId, versionId);
    }

    /** 失败任务保留原创建时间，并重新进入审核队列。 */
    public Optional<TenderReviewSnapshot> retry(String taskId, String versionId) {
        synchronized (lock(taskId, versionId)) {
            var review = taskStore.findReview(taskId, versionId);
            var version = taskStore.findVersion(taskId, versionId);
            if (review.isEmpty() || version.isEmpty()) return Optional.empty();
            if (review.get().status() != TenderReviewStatus.FAILED) {
                throw new IllegalStateException("仅审核失败的报告可以重试");
            }
            TenderReviewSnapshot pending = update(
                    review.get(), TenderReviewStatus.PENDING, "等待重新审核", null, null);
            taskStore.saveReview(pending);
            submit(pending, version.get());
            return taskStore.findReview(taskId, versionId).or(() -> Optional.of(pending));
        }
    }

    private void submit(
            TenderReviewSnapshot pending,
            ProcurementTaskRedisStore.DocumentVersionSnapshot version) {
        var source = taskStore.findReviewSource(pending.taskId());
        if (source.isEmpty()) {
            taskStore.saveReview(update(pending, TenderReviewStatus.FAILED,
                    "审核失败", null, "未找到生成时使用的模板和项目数据"));
            return;
        }
        try {
            executor.execute(() -> review(pending, source.get(), version.markdown()));
        } catch (RuntimeException exception) {
            taskStore.saveReview(update(pending, TenderReviewStatus.FAILED,
                    "审核失败", null, exception.getMessage()));
        }
    }

    private void review(
            TenderReviewSnapshot pending,
            ProcurementTaskRedisStore.TenderReviewSource source,
            String finalizedMarkdown) {
        try {
            taskStore.saveReview(update(pending, TenderReviewStatus.REVIEWING,
                    "正在生成审核报告", null, null));
            String report = reviewService.review(
                    source.templateHtml(), source.projectData(), finalizedMarkdown);
            taskStore.saveReview(update(pending, TenderReviewStatus.COMPLETED,
                    "审核报告已生成", report, null));
        } catch (Exception exception) {
            taskStore.saveReview(update(pending, TenderReviewStatus.FAILED,
                    "审核失败", null, exception.getMessage()));
        }
    }

    private TenderReviewSnapshot update(
            TenderReviewSnapshot review,
            TenderReviewStatus status,
            String stage,
            String report,
            String error) {
        return new TenderReviewSnapshot(
                review.taskId(), review.versionId(), status, stage, report,
                error, review.createdAt(), Instant.now());
    }

    private Object lock(String taskId, String versionId) {
        return ("procurement:tender-review:" + taskId + ":" + versionId).intern();
    }
}
