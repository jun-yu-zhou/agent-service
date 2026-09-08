package com.example.agentservice.procurement.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 采购文档接口的 OpenAPI 元数据配置。 */
@Configuration
public class ProcurementOpenApiConfig {

    @Bean
    public OpenAPI procurementOpenApi() {
        return new OpenAPI().info(new Info()
                .title("AI 辅助生成招投标文件接口")
                .version("v1")
                .description("用于预览、审查和生成招标/投标文件的接口。"));
    }
}
