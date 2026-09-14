package com.example.agentservice.procurement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 招标文件生成流程共用的外部配置。 */
@Component
@ConfigurationProperties(prefix = "app.procurement-document")
public class ProcurementDocumentProperties {

    private String templateLocation = "classpath:/templates/procurement/";
    private String generationModel = "qwen3.7-plus";
    private int maxConcurrency = 4;

    public String getTemplateLocation() {
        return templateLocation;
    }

    public void setTemplateLocation(String templateLocation) {
        this.templateLocation = templateLocation;
    }

    public String getGenerationModel() {
        return generationModel;
    }

    public void setGenerationModel(String generationModel) {
        this.generationModel = generationModel;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

}
