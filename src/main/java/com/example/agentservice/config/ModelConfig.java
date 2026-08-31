package com.example.agentservice.config;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.example.agentservice.formatter.QwenDocDashScopeChatFormatter;
import com.example.agentservice.formatter.QwenLongChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.EndpointType;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.time.Duration;
import java.util.Map;

/**
 * 集中管理各个审查流程使用的模型。
 *
 * <p>模型包含请求选项、Formatter 和执行状态，不在流程之间复用同一个实例。
 * 所有模型 Bean 都使用 prototype 作用域，调用方每次取 Bean 都会获得一个新模型。</p>
 */
@Configuration(proxyBeanMethods = true)
public class ModelConfig {

    public static final String QWEN37_PLUS_MODEL_NAME = "qwen3.7-plus";
    public static final String QWEN38_FLASH_MODEL_NAME = "qwen3.8-flash";

    private static final String DASH_SCOPE_COMPATIBLE_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DASH_SCOPE_API_BASE_URL =
            "https://dashscope.aliyuncs.com/api/v1";

    /**
     * 为保留的独立 main 流程提供 Spring 配置类代理，使其同样通过 prototype Bean 取模型。
     */
    public static ModelConfig standalone() {
        return StandaloneContextHolder.CONTEXT.getBean(ModelConfig.class);
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public DashScopeChatModel qwenDocTurboModel() {
        return DashScopeChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName("qwen-doc-turbo")
                .endpointType(EndpointType.TEXT)
                .formatter(new QwenDocDashScopeChatFormatter())
                .httpTransport(newHttpTransport())
                .stream(true)
                .defaultOptions(GenerateOptions.builder()
                        .executionConfig(longExecutionConfig())
                        .build())
                .build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public DashScopeChatModel qwenDocTurboReviewModel() {
        return DashScopeChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName("qwen-doc-turbo")
                .endpointType(EndpointType.TEXT)
                .formatter(new QwenDocDashScopeChatFormatter())
                .httpTransport(newHttpTransport())
                .stream(true)
                .defaultOptions(GenerateOptions.builder()
                        .maxTokens(4096)
                        .executionConfig(longExecutionConfig())
                        .build())
                .build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public DashScopeChatModel qwenDocTurboBidControlModel() {
        return DashScopeChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName("qwen-doc-turbo")
                .endpointType(EndpointType.TEXT)
                .formatter(new QwenDocDashScopeChatFormatter())
                .httpTransport(newHttpTransport())
                .stream(true)
                .defaultOptions(GenerateOptions.builder()
                        .maxTokens(8192)
                        .temperature(0.1D)
                        .executionConfig(longExecutionConfig())
                        .build())
                .build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public DashScopeChatModel qwenLongModel() {
        return DashScopeChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName("qwen-long")
                .endpointType(EndpointType.TEXT)
                .formatter(new QwenLongChatFormatter())
                .httpTransport(newHttpTransport())
                .stream(true)
                .defaultOptions(GenerateOptions.builder()
                        .maxTokens(4096)
                        .temperature(0.2D)
                        .executionConfig(longExecutionConfig())
                        .build())
                .build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public DashScopeChatModel qwenPlusSummaryModel() {
        return DashScopeChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName("qwen-plus")
                .defaultOptions(GenerateOptions.builder()
                        .temperature(0.1D)
                        .build())
                .build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public OpenAIChatModel qwen38MaxConcurrencyReportModel() {
        return OpenAIChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName("qwen3.8-max")
                .baseUrl(DASH_SCOPE_COMPATIBLE_BASE_URL)
                .endpointPath("/chat/completions")
                .httpTransport(newHttpTransport())
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(8192)
                        .executionConfig(longExecutionConfig())
                        .build())
                .build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public OpenAIChatModel qwen38MaxLongReportModel() {
        return OpenAIChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName("qwen3.8-max")
                .baseUrl(DASH_SCOPE_COMPATIBLE_BASE_URL)
                .endpointPath("/chat/completions")
                .httpTransport(newHttpTransport())
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(4096)
                        .reasoningEffort("low")
                        .temperature(0.15D)
                        .executionConfig(longExecutionConfig())
                        .build())
                .build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public OpenAIChatModel qwen37PlusReportModel() {
        return OpenAIChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName(QWEN37_PLUS_MODEL_NAME)
                .baseUrl(DASH_SCOPE_COMPATIBLE_BASE_URL)
                .endpointPath("/chat/completions")
                .httpTransport(newHttpTransport())
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(8192)
                        .temperature(0.15D)
                        .executionConfig(longExecutionConfig())
                        .build())
                .build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public OpenAIChatModel qwen38FlashReportModel() {
        return OpenAIChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName(QWEN38_FLASH_MODEL_NAME)
                .baseUrl(DASH_SCOPE_COMPATIBLE_BASE_URL)
                .endpointPath("/chat/completions")
                .httpTransport(newHttpTransport())
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(8192)
                        .temperature(0.15D)
                        .executionConfig(longExecutionConfig())
                        .build())
                .build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public OpenAIChatModel qwen37PlusBidControlReportModel() {
        return OpenAIChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName(QWEN37_PLUS_MODEL_NAME)
                .baseUrl(DASH_SCOPE_COMPATIBLE_BASE_URL)
                .endpointPath("/chat/completions")
                .httpTransport(newHttpTransport())
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(4096)
                        .temperature(0.1D)
                        .additionalBodyParam("enable_thinking", true)
                        .additionalBodyParam("enable_search", true)
                        .additionalBodyParam("search_options",
                                Map.of("forced_search", true, "search_strategy", "max"))
                        .executionConfig(longExecutionConfig())
                        .build())
                .build();
    }

    /** 使用 AgentScope 默认 HTTP 配置执行 qwen3.8-flash 全量图片流式审查。 */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public OpenAIChatModel qwen38FlashStreamingReviewModel() {
        return OpenAIChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName(QWEN38_FLASH_MODEL_NAME)
                .baseUrl(DASH_SCOPE_COMPATIBLE_BASE_URL)
                .endpointPath("/chat/completions")
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(8192)
                        .temperature(0.2D)
                        .additionalBodyParam("enable_thinking", false)
                        .build())
                .build();
    }

    /** 使用 AgentScope 默认 HTTP 配置执行 qwen3.7-plus 全量图片流式审查。 */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public OpenAIChatModel qwen37PlusStreamingReviewModel() {
        return OpenAIChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName(QWEN37_PLUS_MODEL_NAME)
                .baseUrl(DASH_SCOPE_COMPATIBLE_BASE_URL)
                .endpointPath("/chat/completions")
                .stream(true)
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(8192)
                        .temperature(0.2D)
                        .additionalBodyParam("enable_thinking", false)
                        .build())
                .build();
    }

    /** 使用 AgentScope 默认 HTTP 配置，由 qwen3.8-flash 生成最终报告。 */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public DashScopeChatModel qwen38FlashDefaultReportModel() {
        return DashScopeChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName(QWEN38_FLASH_MODEL_NAME)
                .baseUrl(AgentServiceConfig.dashScopeBaseUrl())
                .endpointType(EndpointType.MULTIMODAL)
                .stream(false)
                .enableThinking(false)
                .defaultOptions(GenerateOptions.builder()
                        .maxTokens(8192)
                        .temperature(0.15D)
                        .build())
                .build();
    }

    /** 使用 AgentScope 默认 HTTP 配置，由 qwen3.7-plus 生成最终报告。 */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public DashScopeChatModel qwen37PlusDefaultReportModel() {
        return DashScopeChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .modelName(QWEN37_PLUS_MODEL_NAME)
                .baseUrl(AgentServiceConfig.dashScopeBaseUrl())
                .endpointType(EndpointType.MULTIMODAL)
                .stream(false)
                .enableThinking(false)
                .defaultOptions(GenerateOptions.builder()
                        .maxTokens(8192)
                        .temperature(0.15D)
                        .build())
                .build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public MultiModalConversation qwen37PlusMultimodalModel() {
        return new MultiModalConversation(
                "http",
                DASH_SCOPE_API_BASE_URL,
                ConnectionOptions.builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .writeTimeout(Duration.ofMinutes(5))
                        .readTimeout(Duration.ofMinutes(30))
                        .build());
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public MultiModalConversation qwen38FlashMultimodalModel() {
        return new MultiModalConversation(
                "http",
                DASH_SCOPE_API_BASE_URL,
                ConnectionOptions.builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .writeTimeout(Duration.ofMinutes(5))
                        .readTimeout(Duration.ofMinutes(30))
                        .build());
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public BailianKnowledge bailianLegalKnowledge() throws Exception {
        return BailianKnowledge.builder()
                .config(BailianConfig.builder()
                        .accessKeyId(AgentServiceConfig.ossAccessKeyId())
                        .accessKeySecret(AgentServiceConfig.ossAccessKeySecret())
                        .workspaceId(AgentServiceConfig.bailianWorkspaceId())
                        .indexId(AgentServiceConfig.bailianKnowledgeBaseId())
                        .endpoint("bailian.cn-beijing.aliyuncs.com")
                        .denseSimilarityTopK(8)
                        .sparseSimilarityTopK(8)
                        .enableReranking(true)
                        .build())
                .build();
    }

    private JdkHttpTransport newHttpTransport() {
        return JdkHttpTransport.builder()
                .config(HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofMinutes(20))
                        .writeTimeout(Duration.ofMinutes(2))
                        .build())
                .build();
    }

    private ExecutionConfig longExecutionConfig() {
        return ExecutionConfig.builder()
                .timeout(Duration.ofMinutes(20))
                .maxAttempts(1)
                .build();
    }

    private static final class StandaloneContextHolder {
        private static final AnnotationConfigApplicationContext CONTEXT =
                new AnnotationConfigApplicationContext(ModelConfig.class);

        private StandaloneContextHolder() {
        }
    }
}
