package com.example.agentservice.agile;

import com.example.agentservice.config.AgentServiceConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;

/** Explicitly run paid integration probe; never runs as part of the unit test suite. */
@SuppressWarnings("deprecation")
public class BidControlRagProbe {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("review")) {
            var imm = org.mockito.Mockito.mock(com.example.agentservice.service.ImmService.class);
            org.mockito.Mockito.when(imm.extractDocumentText("fixture")).thenReturn("""
                    政府采购招标文件：办公设备采购，预算100万元。
                    资格条件：供应商必须在本市注册，必须提供近三年本市政府机关办公设备供货业绩三个。
                    评分标准：本市业绩每个5分，最多15分；投标报价30分，技术性能55分。
                    供货期30天，验收后30天内全额付款。技术参数允许同等性能产品。
                    """);
            String report = BidControlReview.review("fixture", imm,
                    new BidControlReviewConfig().bidControlLegalKnowledge());
            if (!report.contains("风险类型") || !report.contains("免责声明"))
                throw new IllegalStateException("Invalid rendered report");
            System.out.println("PROBE_REVIEW_PASS\n" + report);
            return;
        }
        boolean nativeMode = args.length == 0 || args[0].equals("native");
        var model = OpenAIChatModel.builder()
                .apiKey(AgentServiceConfig.dashScopeApiKey())
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .modelName("qwen3.7-plus").stream(true)
                .nativeStructuredOutput(nativeMode).nativeStructuredOutputWithTools(nativeMode)
                .generateOptions(GenerateOptions.builder()
                        .additionalBodyParam("enable_thinking", false).build()).build();
        var mapper = new ObjectMapper();
        var schema = mapper.readTree("""
                {"type":"object","properties":{"basis":{"type":"string","minLength":1}},"required":["basis"],"additionalProperties":false}
                """);
        int[] tools = {0};
        var agent = ReActAgent.builder().name("legal-probe").model(model)
                .knowledge(new BidControlReviewConfig().bidControlLegalKnowledge())
                .ragMode(RAGMode.AGENTIC).maxIters(8)
                .sysPrompt("必须先调用法律知识库检索工具核验依据，再输出指定结构。检索词应简短。最终结果必须使用指定的JSON结构；若有generate_response工具，必须用它提交最终结果。")
                .hook(new Hook() {
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostReasoningEvent post) {
                            for (var tool : post.getReasoningMessage().getContentBlocks(ToolUseBlock.class)) {
                                System.out.println("PROBE_TOOL " + tool.getName());
                                if (!tool.getName().equals("generate_response")) tools[0]++;
                            }
                        }
                        return Mono.just(event);
                    }
                }).build();
        var response = agent.call(Msg.builder().role(MsgRole.USER)
                .textContent("招标要求供应商具有本地项目业绩。请检索政府采购法实施条例第二十条，说明相关法律依据。").build(), schema)
                .block(Duration.ofMinutes(4));
        String json = response == null ? "" : response.getTextContent();
        String error = ToolValidator.validateInput(json, mapper.convertValue(schema, Map.class));
        System.out.println("PROBE_RESULT mode=" + (nativeMode ? "native" : "synthetic")
                + " retrievalCalls=" + tools[0] + " validation=" + error + " output=" + json);
        if (tools[0] == 0 || error != null) throw new IllegalStateException("RAG/structured probe failed");
    }
}
