package com.example.agentservice.procurement.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 采购文档后台任务使用的线程池。 */
@Configuration
public class ProcurementTaskExecutorConfig {

    @Bean(name = "procurementDocumentExecutor", destroyMethod = "shutdown")
    public ExecutorService procurementDocumentExecutor(ProcurementDocumentProperties properties) {
        return Executors.newFixedThreadPool(Math.max(1, properties.getMaxConcurrency()));
    }

    /** 审核独立排队，避免被耗时较长的初稿和投标文件生成任务阻塞。 */
    @Bean(name = "tenderReviewExecutor", destroyMethod = "shutdown")
    public ExecutorService tenderReviewExecutor(ProcurementDocumentProperties properties) {
        int concurrency = Math.max(1, properties.getReviewConcurrency());
        return new ThreadPoolExecutor(
                concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getReviewQueueCapacity())),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
