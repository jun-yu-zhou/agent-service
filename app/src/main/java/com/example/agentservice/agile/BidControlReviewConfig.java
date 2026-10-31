package com.example.agentservice.agile;

import com.example.agentservice.config.AgentServiceConfig;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 招标文件控标审查专用的百炼知识库配置。
 *
 * <p>为什么不放进全局的 {@code ModelConfig}：知识库 id（法律文件库）是控标审查
 * 这一个流程的私有依赖，别的工作流各有自己的库；放在全局配置里容易出现"改了一个
 * 流程的库 id，另一个流程跟着错"的隐性耦合。所以 id 以常量形式写在
 * {@link BidControlReview} 里、bean 注册在本配置类中，职责边界一目了然。
 *
 * <p>检索词与检索行为在 {@code BidControlReview#retrieveLegalContext}（手动
 * {@code retrieve}，不挂 {@code RAGMode}，原因见其注释）。
 *
 * <p>关于弃用告警：{@code BailianKnowledge} 自 2.0.0 起 {@code @Deprecated(forRemoval)}，
 * 但 2.0.4 官方文档仍推荐该 API 且无替代实现发布，这里压制弃用警告，
 * 待官方出新 API 后统一迁移。
 */
@Configuration(proxyBeanMethods = false)
public class BidControlReviewConfig {

    @Bean
    @SuppressWarnings("deprecation")
    public BailianKnowledge bidControlLegalKnowledge() {
        return BailianKnowledge.builder()
                .config(BailianConfig.builder()
                        .accessKeyId(AgentServiceConfig.ossAccessKeyId())
                        .accessKeySecret(AgentServiceConfig.ossAccessKeySecret())
                        .workspaceId(BidControlReview.BAILIAN_WORKSPACE_ID)
                        .indexId(BidControlReview.BAILIAN_KNOWLEDGE_BASE_ID)
                        .endpoint("bailian.cn-beijing.aliyuncs.com")
                        .denseSimilarityTopK(8)
                        .sparseSimilarityTopK(8)
                        .enableReranking(true)
                        .build())
                .indexId(BidControlReview.BAILIAN_KNOWLEDGE_BASE_ID)
                .build();
    }
}
