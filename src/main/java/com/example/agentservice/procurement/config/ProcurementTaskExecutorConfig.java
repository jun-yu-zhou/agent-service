package com.example.agentservice.procurement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared bounded executor for all procurement document generation tasks. */
@Configuration
public class ProcurementTaskExecutorConfig {

    @Bean(name = "procurementDocumentExecutor", destroyMethod = "shutdown")
    public ExecutorService procurementDocumentExecutor(ProcurementDocumentProperties properties) {
        return Executors.newFixedThreadPool(Math.max(1, properties.getMaxConcurrency()));
    }
}
