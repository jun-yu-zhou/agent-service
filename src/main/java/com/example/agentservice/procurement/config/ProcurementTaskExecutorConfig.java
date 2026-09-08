package com.example.agentservice.procurement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 所有采购文档生成任务共用的有界线程池。 */
@Configuration
public class ProcurementTaskExecutorConfig {

    @Bean(name = "procurementDocumentExecutor", destroyMethod = "shutdown")
    public ExecutorService procurementDocumentExecutor(ProcurementDocumentProperties properties) {
        return Executors.newFixedThreadPool(Math.max(1, properties.getMaxConcurrency()));
    }
}
