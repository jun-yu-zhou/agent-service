package com.example.agentservice.agile;

import com.example.agentservice.AgentServiceApplication;
import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.entity.BidControlRiskRow;
import com.example.agentservice.entity.BidControlRiskTable;
import com.example.agentservice.service.ImmService;
import com.example.agentservice.utils.MarkdownTables;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单 Agent 控标审查：IMM 全文提取、Agentic RAG 核验、generate_response 结构化输出、
 * 流式过程输出 + 代码拼装固定表格。
 *
 * <p>流式与结构化能共存的关键：{@code agent.stream(msg, options, schema)} 与
 * {@code agent.call(msg, schema)} 走的是同一条 doStructuredCall 管道（详见
 * {@link ModelConfig} 中 qwen37PlusBidControlReportModel 关闭 nativeStructuredOutput
 * 的注释），流式只是把这条管道包成事件流——过程中实时发 REASONING/TOOL_RESULT
 * 事件，最后一条 AGENT_RESULT 携带与 call() 返回值等价的最终 Msg。
 */
public class BidControlReview {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ModelConfig MODEL_CONFIG = ModelConfig.standalone();

    /** 本审查专用的百炼法律文件知识库标识（bean 见 BidControlReviewConfig，不做全局配置）。 */
    public static final String BAILIAN_WORKSPACE_ID = "llm-2c213fyvomyxzuc9";
    public static final String BAILIAN_KNOWLEDGE_BASE_ID = "94kvimrfoy";

    /** 免责声明固定跟在表格后输出，属于报告的一部分，不是审查结果。 */
    private static final String DISCLAIMER = "免责声明：本报告仅基于提供的招标文件作风险提示，不构成对招标文件违法违规或控标行为的最终认定，最终结论应结合采购需求、市场调查、编制说明及相关澄清材料综合判断。";

    private static final String REVIEW_PROMPT = """
            审查招标文件中可能限制竞争、指向特定供应商或超出履约必要程度的条款。
            检查：参数指向、资质门槛、业绩定向、排他授权、主观评分、履约周期、报价阶梯、地域或所有制限制、合同责任、加分项、付款条件、时间节点、信息透明度。

            以原文和全文交叉核对为依据，保留准确摘录与位置，不猜测缺失事实。同一问题合并，不遗漏不同问题。
            严格要求不当然构成控标；区分法定准入与额外限制、客观评分与主观评分，核对付款安排及合同双方责任。预算等于最高限价不单独构成风险。
            按疑点用简短关键词调用法律知识库核验，引用法律名称、条款及条文要点并说明适用关系，不编造依据。依据不足写“具体条款待人工核验”，置信度为低；依赖地方政策或市场事实的疑点写“需进一步核验”。

            最后调用 generate_response 提交结果：riskType 为简短类型；riskContent 写具体设置及原文位置；riskAnalysis 写风险逻辑、法律依据和适用分析；suggestion 给出可执行修改。
            riskLevel 和 confidence 均填高、中、低：证据明确且实质限制竞争为高，有疑点待核验为中，轻微或证据不足为低。避免夸大风险。
            未发现明确风险时保留一行，riskType 为“未发现明确风险”，风险程度与置信度为低，其余字段如实说明。
            """;

    public static void main(String[] args) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(AgentServiceApplication.class)
                .web(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            ImmService immService = context.getBean(ImmService.class);
            BailianKnowledge legalKnowledge = context.getBean(BailianKnowledge.class);
            System.err.println("开始执行招标文件控标审查：IMM全文提取 -> qwen3.7-plus结构化输出...");
            String report = review(args.length > 0 ? args[0] : com.example.agentservice.config.AgentServiceConfig.bidControlDocumentUrl(), immService, legalKnowledge);
            System.out.println(report);
            System.err.println("控标审查完成。");
        } catch (Exception exception) {
            throw new IllegalStateException("招标文件控标审查流程执行失败", exception);
        }
    }

    /**
     * 对一份招标文件执行控标风险审查，返回固定 6 列的 Markdown 表格加免责声明。
     *
     * <p>输入是 OSS 文件的 http/https URL（后缀须在 IMM 白名单 doc/docx/ppt/pptx/xls/xlsx/pdf/txt 内）；
     * IMM 提取失败、模型未返回结构化结果或字段校验失败时抛 {@link IllegalStateException}，
     * 不做静默降级——审查报告宁可失败也不能输出残缺结论。
     *
     * <p>流式与结构化走同一条管道：{@code agent.stream(request, options, schema)} 与
     * {@code agent.call(request, schema)} 共用 doStructuredCall（generate_response 合成工具
     * 收口），因此流式过程输出不会破坏结构化契约。过程事件（思考增量、检索结果）实时打印到
     * stderr；最终结果取最后一条 AGENT_RESULT 事件的 Msg，经 {@code getStructuredData}
     * 反序列化——该 API 读 Msg 的 _structured_output metadata，native 与 fallback 两条
     * 收口路径通吃，比"校验 textContent 再 readValue"可靠（fallback 的 textContent 是
     * 还原文本，不保证为纯 JSON）。
     *
     * @param documentUrl 招标文件在 OSS 上的 http/https 地址
     * @param immService  IMM 文档正文提取服务（Spring 容器中的 bean）
     * @param knowledge   百炼法律文件知识库（bidControlLegalKnowledge bean，Agentic RAG 检索）
     * @return Markdown 表格文本，表头固定为：风险类型 | 风险程度 | 风险内容 | 风险分析 | 修改建议 | 置信度
     */
    @SuppressWarnings("deprecation")
    public static String review(String documentUrl, ImmService immService, BailianKnowledge knowledge) throws Exception {
        String documentText = immService.extractDocumentText(documentUrl);
        System.err.println("IMM全文提取完成，字符数: " + documentText.length());

        DashScopeChatModel model = MODEL_CONFIG.qwen37PlusBidControlReportModel();
        ReActAgent agent = ReActAgent.builder()
                .name("bid-control-review")
                .sysPrompt(REVIEW_PROMPT)
                .model(model)
                .knowledge(knowledge)
                .ragMode(RAGMode.AGENTIC)
                .maxIters(20)
                .build();
        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .textContent("请审查以下招标文件全文并按系统要求返回结构化结果。\n\n"
                        + "\n\n===== 招标文件全文 =====\n" + documentText)
                .build();
        JsonNode schema = hardenSchema(JsonSchemaUtils.generateSchemaFromClass(BidControlRiskTable.class));

        // ★ 核心行：agent.stream(msg, options, schema)。
        // 它与 agent.call(msg, schema) 共用同一条 doStructuredCall 管道（generate_response
        // 合成工具收口，见 ModelConfig 中 nativeStructuredOutput(false) 的说明），所以"流式
        // 过程输出"和"结构化契约"不是两种模式，而是同一管道的两种消费方式——这是流式
        // 不破坏结构化输出的根本原因。schema 参数必须传给 stream 而不是只传给 call。
        StreamOptions options = StreamOptions.builder()
                // ★ 必须显式订阅过程事件：StreamOptions.shouldStream 只在 eventTypes 包含
                // 对应类型（或 ALL）时才发射，漏写任何一个的话该类事件整个流里都不会出现，
                // "流式"就退化成"阻塞"（ImmConcurrentStreamReview 的假流式正是踩了这个坑）。
                // 三个类型各自的作用：
                //   REASONING   —— 模型的思考/组织答案增量，流式打给用户看"它在想什么"，
                //                  注意模型吐 generate_response 参数时该事件文本为空；
                //   TOOL_RESULT —— 每次知识库检索的结果回包，用来打"[知识库检索完成]"
                //                  提示，让用户知道审查推进到了哪一步；
                //   AGENT_RESULT—— 最终结果事件（全流仅一条、isLast=true），携带与
                //                  agent.call() 返回值等价的最终 Msg，结构化数据从这里取。
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                // ★ incremental(true) = 每个事件只携带"新增的增量文本"，配合 doOnNext 里
                // 直接 print 就是打字机效果；若设为 false，每条事件携带的是"从头到当前
                // 的累积全文"，逐条 print 会把同样的内容反复打印。结果消费不受影响——
                // 最终结构化数据在 AGENT_RESULT 的 metadata 里，与该开关无关。
                .incremental(true)
                .build();
        AtomicReference<Msg> finalMsg = new AtomicReference<>();
        agent.stream(request, options, schema).doOnNext(event -> {
            Msg message = event.getMessage();
            switch (event.getType()) {
                case REASONING -> {
                    // 思考增量实时可见。注意：模型流式吐 generate_response 工具参数的阶段，
                    // 增量在 ToolUseBlock 里、getTextContent() 为空，此处跳过即可——结构化
                    // 阶段本来就不该给用户看原始 JSON。
                    String chunk = message == null ? null : message.getTextContent();
                    if (chunk != null && !chunk.isEmpty()) {
                        System.err.print(chunk);
                    }
                }
                case TOOL_RESULT -> System.err.println("[知识库检索完成]");
                // ★ 全流只有一条 AGENT_RESULT 且 isLast=true，等价于 call() 的返回值；
                // 把它存下来，流结束后统一取结构化结果。
                case AGENT_RESULT -> finalMsg.set(message);
                default -> { }
            }
        }).blockLast();

        Msg finalResult = finalMsg.get();
        if (finalResult == null) {
            throw new IllegalStateException("qwen3.7-plus未返回结构化审查结果");
        }
        // ★ 核心行：getStructuredData 读最终 Msg 的 _structured_output metadata——这是
        // native（response_format）与 fallback（generate_response 工具）两条收口路径
        // 通吃的官方出口。不要改回"校验 textContent 再 readValue"：fallback 的
        // textContent 是还原文本而非纯 JSON，那条路依赖未成文契约，双校验有误杀风险。
        BidControlRiskTable table = finalResult.getStructuredData(BidControlRiskTable.class);
        if (table == null || table.risks().isEmpty()) {
            throw new IllegalStateException("结构化输出为空或反序列化失败");
        }
        validateRows(table.risks());
        System.err.println("\n结构化结果解析完成，风险行数: " + table.risks().size());
        return MarkdownTables.render(table.risks()) + "\n\n" + DISCLAIMER;
    }

    /** 对象级字段非空校验：schema 的 required+minLength 在收口时约束，这里是防残缺报告的最后一道闸。 */
    private static void validateRows(List<BidControlRiskRow> risks) {
        for (int index = 0; index < risks.size(); index++) {
            BidControlRiskRow row = risks.get(index);
            List<String> blanks = new ArrayList<>();
            if (isBlank(row.riskType())) blanks.add("riskType");
            if (isBlank(row.riskLevel())) blanks.add("riskLevel");
            if (isBlank(row.riskContent())) blanks.add("riskContent");
            if (isBlank(row.riskAnalysis())) blanks.add("riskAnalysis");
            if (isBlank(row.suggestion())) blanks.add("suggestion");
            if (isBlank(row.confidence())) blanks.add("confidence");
            if (!blanks.isEmpty()) {
                throw new IllegalStateException("第 " + (index + 1) + " 行字段为空: " + blanks);
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 硬化结构化输出 schema：所有对象字段加入 {@code required}，字符串字段加 {@code minLength: 1}。
     *
     * <p>AgentScope 的 {@code generateSchemaFromClass} 只把 {@code @JsonProperty(required = true)}
     * 转成 required，管不住"字段存在但值是空串"；本方法递归补上长度约束后，空字段在
     * schema 层即不合法，配合 {@code agent.call(msg, JsonNode)} 提交（与 Class 重载走同一条
     * 结构化输出链路），彻底不依赖提示词自觉。
     */
    private static JsonNode hardenSchema(Map<String, Object> schema) {
        JsonNode root = OBJECT_MAPPER.valueToTree(schema);
        if (root instanceof ObjectNode rootNode) {
            hardenObjectNode(rootNode);
        }
        return root;
    }

    private static void hardenObjectNode(ObjectNode node) {
        JsonNode properties = node.get("properties");
        if (!(properties instanceof ObjectNode props)) {
            return;
        }
        List<String> names = new ArrayList<>();
        props.fieldNames().forEachRemaining(names::add);
        node.set("required", OBJECT_MAPPER.valueToTree(names));
        props.fields().forEachRemaining(entry -> {
            JsonNode child = entry.getValue();
            if (child instanceof ObjectNode childObject) {
                if ("string".equals(childObject.path("type").asText())) {
                    childObject.put("minLength", 1);
                }
                hardenObjectNode(childObject);
                if (childObject.get("items") instanceof ObjectNode items) {
                    hardenObjectNode(items);
                }
            }
        });
    }
}
