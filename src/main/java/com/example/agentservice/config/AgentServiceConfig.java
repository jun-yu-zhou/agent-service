package com.example.agentservice.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.util.Properties;

/**
 * Loads local development settings without putting secrets in source control.
 * API credentials are read from application-local.yml only.
 */
public final class AgentServiceConfig {

    private static final Properties LOCAL_PROPERTIES = loadLocalProperties();

    private AgentServiceConfig() {
    }

    public static String dashScopeApiKey() {
        String localValue = LOCAL_PROPERTIES.getProperty("app.dashscope.api-key");
        if (localValue != null && !localValue.isBlank() && !localValue.startsWith("${")) {
            return localValue;
        }
        throw new IllegalStateException("缺少配置 app.dashscope.api-key，请在 application-local.yml 中设置");
    }

    public static String ossEndpoint() {
        return value("app.oss.endpoint", "ALIYUN_OSS_ENDPOINT", "https://oss-cn-beijing.aliyuncs.com");
    }

    public static String ossRegion() {
        return value("app.oss.region", "ALIYUN_OSS_REGION", "cn-beijing");
    }

    public static String ossBucket() {
        return required("app.oss.bucket", "ALIYUN_OSS_BUCKET");
    }

    public static String ossAccessKeyId() {
        return required("app.oss.access-key-id", "ALIYUN_OSS_ACCESS_KEY_ID");
    }

    public static String ossAccessKeySecret() {
        return required("app.oss.access-key-secret", "ALIYUN_OSS_ACCESS_KEY_SECRET");
    }

    public static String immEndpoint() {
        return value("app.imm.endpoint", "ALIYUN_IMM_ENDPOINT", "imm.cn-beijing.aliyuncs.com");
    }

    public static String immProjectName() {
        return value("app.imm.project-name", "ALIYUN_IMM_PROJECT", "pdfReview");
    }

    public static String ruleFileUrl() {
        return required("app.documents.rule-file-url", "RULE_FILE_URL");
    }

    public static String requirementFileUrl() {
        return required("app.documents.requirement-file-url", "REQUIREMENT_FILE_URL");
    }

    private static String required(String propertyName, String environmentName) {
        String result = value(propertyName, environmentName, "");
        if (result.isBlank()) {
            throw new IllegalStateException("缺少配置 " + propertyName
                    + "，请在 application-local.yml 或环境变量 " + environmentName + " 中设置");
        }
        return result;
    }

    private static String value(String propertyName, String environmentName, String defaultValue) {
        String systemValue = System.getProperty(environmentName);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue;
        }
        String localValue = LOCAL_PROPERTIES.getProperty(propertyName);
        if (localValue != null && !localValue.isBlank()
                && !localValue.startsWith("${")) {
            return localValue;
        }
        return defaultValue;
    }

    private static Properties loadLocalProperties() {
        Resource resource = new ClassPathResource("application-local.yml");
        if (!resource.exists()) {
            resource = new FileSystemResource("src/main/resources/application-local.yml");
        }
        if (!resource.exists()) {
            return new Properties();
        }
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource);
        factory.afterPropertiesSet();
        Properties properties = factory.getObject();
        return properties == null ? new Properties() : properties;
    }
}
