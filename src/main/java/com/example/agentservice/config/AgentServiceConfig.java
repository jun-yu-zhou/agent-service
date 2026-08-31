package com.example.agentservice.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.util.Properties;

/**
 * Loads local development settings without putting secrets in source control.
 * API credentials are read from JVM properties, environment variables, or application-local.yml.
 */
public final class AgentServiceConfig {

    private static final Properties LOCAL_PROPERTIES = loadLocalProperties();

    private AgentServiceConfig() {
    }

    public static String dashScopeApiKey() {
        String result = value("app.dashscope.api-key", "DASHSCOPE_API_KEY", "");
        if (result.isBlank()) {
            throw new IllegalStateException("缺少配置 app.dashscope.api-key，请在 application-local.yml 或环境变量 DASHSCOPE_API_KEY 中设置");
        }
        return result;
    }

    public static String dashScopeBaseUrl() {
        return value("app.dashscope.base-url", "DASHSCOPE_BASE_URL",
                "https://dashscope.aliyuncs.com");
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

    public static boolean bailianKnowledgeEnabled() {
        return Boolean.parseBoolean(value(
                "app.bailian.knowledge-enabled", "ALIYUN_BAILIAN_KNOWLEDGE_ENABLED", "false"));
    }

    public static String bailianWorkspaceId() {
        return required("app.bailian.workspace-id", "ALIYUN_BAILIAN_WORKSPACE_ID");
    }

    public static String bailianKnowledgeBaseId() {
        return required("app.bailian.knowledge-base-id", "ALIYUN_BAILIAN_KNOWLEDGE_BASE_ID");
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
        String localValue = resolvePlaceholder(LOCAL_PROPERTIES.getProperty(propertyName));
        if (!localValue.isBlank()) {
            return localValue;
        }
        return defaultValue;
    }

    private static String resolvePlaceholder(String value) {
        if (value == null || value.isBlank() || !value.startsWith("${") || !value.endsWith("}")) {
            return value == null ? "" : value;
        }
        String expression = value.substring(2, value.length() - 1);
        int separator = expression.indexOf(':');
        String name = separator < 0 ? expression : expression.substring(0, separator);
        String fallback = separator < 0 ? "" : expression.substring(separator + 1);
        String systemValue = System.getProperty(name);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        String environmentValue = System.getenv(name);
        return environmentValue == null || environmentValue.isBlank() ? fallback : environmentValue;
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
