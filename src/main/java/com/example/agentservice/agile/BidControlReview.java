package com.example.agentservice.agile;

import com.example.agentservice.AgentServiceApplication;
import com.example.agentservice.config.ModelConfig;
import com.example.agentservice.entity.BidControlRiskTable;
import com.example.agentservice.service.ImmService;
import com.example.agentservice.utils.MarkdownTables;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.util.JsonSchemaUtils;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 招标文件控标风险审查（MVP）：IMM 提取全文，qwen3.7-plus 一步完成审查并结构化输出，
 * 最终渲染成固定 6 列的 Markdown 表格。
 *
 * <p>解决的问题是"控标"——招标文件里那些看起来合理、实际把竞争范围缩到特定供应商的
 * 条款（参数精确指向、业绩定向、授权唯一、主观分过高等）。这类审查需要既通读全文找条款，
 * 又要核实法律依据，单靠提示词让模型"自己画表格"格式还不受控。
 *
 * <p>链路只有一条直线，相比旧版（qwen-doc-turbo 读文档出草稿 → qwen3.7-plus 汇总）
 * 砍掉了 qwen-doc 一步、砍掉了自由文本汇总：
 * <ol>
 *   <li>IMM {@code ExtractDocumentText} 把 OSS 上的 doc/docx/pdf 等 8 种文档同步提取为全文文本
 *       （见 {@code ImmService.extractDocumentText}，秒级返回，无需提交任务轮询）；</li>
 *   <li>全文文本交给 qwen3.7-plus（OpenAI 兼容模式，强制联网搜索核验法律依据），通过
 *       AgentScope 结构化输出 API 返回 {@code risks} 数组——模型到 JSON 之间没有任何
 *       二次模型调用，JSON → {@code List<BidControlRiskRow>} 的反序列化就是一次 Jackson
 *       {@code readValue}，表格由 {@code MarkdownTables} 一个循环拼完；</li>
 *   <li>"字段必填"压在 schema 层而不是提示词：AgentScope 的 schema 生成器会读
 *       {@code @JsonProperty(required = true)} 生成 {@code required}，{@link #hardenSchema}
 *       再对所有字符串字段补 {@code minLength: 1}，然后用 {@code agent.call(msg, JsonNode)}
 *       提交硬化后的 schema——空字段在 schema 层就不合法，模型想偷懒都难；</li>
 *   <li>法律依据检索接入百炼法律文件知识库：手动以聚焦检索词调 {@code Knowledge.retrieve}
 *       （top 5），命中片段拼进用户消息。不用 AgentScope 的 {@code RAGMode.GENERIC} 自动
 *       检索——它拿整份文档全文当检索词会撑爆请求 URI（414）；也不用 {@code AGENTIC}——
 *       检索工具会与结构化输出的强制工具调用抢 tool-choice。</li>
 * </ol>
 *
 * <p>相对的还有旧版"模型直接输出 Markdown 表格"的做法，两者二选一即可：旧版少一次
 * 结构化解析但格式不可控；本项目选结构化输出 + 代码渲染，格式 100% 确定。
 * 若后续想恢复"切图 + 多模态逐页审"的路线，IMM 按页切图能力
 * （{@code ImmService.convertDocumentToImages}）已就绪，可直接叠加。
 */
public class BidControlReview {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ModelConfig MODEL_CONFIG = ModelConfig.standalone();
    // 默认审查扬州工业职业技术学院图书馆风冷热泵机组采购项目（用户上传的定稿 .doc）。
    private static final String TEST_URL =
            "http://oss1.easyjcx.com/co-order/2026/10/30/b57c2649b674480096adc615dadde63e2815252867861337994.doc";

    /** 本审查专用的百炼法律文件知识库标识（bean 见 BidControlReviewConfig，不做全局配置）。 */
    public static final String BAILIAN_WORKSPACE_ID = "llm-2c213fyvomyxzuc9";
    public static final String BAILIAN_KNOWLEDGE_BASE_ID = "94kvimrfoy";

    /** 知识库检索词：聚焦"控标审查会用到的法律依据"，绝不能把整份文档全文当检索词（URI 会超限）。 */
    private static final String LEGAL_QUERY = "招标文件控标风险审查法律依据：政府采购法及实施条例、87号令；"
            + "技术参数指向特定产品、资格业绩要求设置、评分标准与主观分、质保年限加分、"
            + "合同违约责任不对等、投标截止时间与准备期限的法定最低要求";

    /** 免责声明固定跟在表格后输出，属于报告的一部分，不是审查结果。 */
    private static final String DISCLAIMER = "免责声明：本报告仅基于提供的招标文件作风险提示，不构成对招标文件违法违规或控标行为的最终认定，最终结论应结合采购需求、市场调查、编制说明及相关澄清材料综合判断。";

    private static final String REVIEW_PROMPT = """
            你是招标文件控标风险审查专家。用户消息给出的是一份招标文件的全文文本（已经过IMM全文提取），并附有从法律文件知识库检索到的参考片段。请只审查招标文件本身是否存在可能限制竞争、定向设置或不合理设置的条款，并通过结构化输出直接返回审查结果。

            审查原则：
            1. 必须以文件原文为依据，不得补充、猜测文件中不存在的限制条件。
            2. 发现疑似风险后，应结合全文相关条款交叉核对，避免只截取单一条款下结论。
            3. “要求严格”“分值较高”“对供应商不利”不等于控标，应重点判断该设置是否实际缩小竞争范围，且是否明显超出项目履约所必要的程度。
            4. 需要结合地方政策、行业准入规则或市场情况才能判断的，标记“需进一步核验”，不得直接认定为高风险。
            5. 同一根本问题不要重复拆分为多个风险。

            必须逐项检查以下维度：
            1. 参数精确：技术参数、品牌型号、尺寸、性能或验收指标是否精确到明显缺乏必要性的程度，是否形成特定产品指向。
            2. 资质离谱：资质、认证、人员、设备要求是否与项目规模和实际需求不匹配，是否明显抬高参与门槛。应区分法定准入要求与额外限制条件。
            3. 业绩定向：业绩类型、金额、地区、合同数量、项目特征和时间范围是否组合得过窄，是否指向少数供应商。仅写“类似业绩”但定义不清的，应识别为标准模糊，而非直接认定范围过窄。
            4. 授权唯一：是否要求唯一品牌授权、唯一代理、排他性证明或其他可能排斥多家供应商的证明。
            5. 主观分高：主观评分项占比是否过高，评分标准是否模糊、无法客观比较或给特定方案留下操控空间。业绩、证书、人员数量等可客观核验的分值不得直接计入主观分。
            6. 周期太短：投标、供货、施工、服务、质保或响应期限是否明显不合理。不得仅凭天数较短直接认定风险。
            7. 报价阶梯排布：预算、最高限价、报价区间、分值阶梯或异常扣分规则是否可能诱导围绕固定价格报价。预算等于最高限价本身不属于异常。
            8. 地域或所有制限制：是否不合理限制注册地、经营区域、所有制、企业规模或本地业绩。涉及地方名单、备案、入库条件时，应判断是否属于必要准入要求；无法确认的标记“需进一步核验”。
            9. 合同条款不对等：违约责任、索赔、风险承担、变更、验收、质保等是否明显单方加重供应商责任。必须同时核对采购人和供应商双方责任后再判断。
            10. 不合理加分项：与履约能力无直接关系，或对特定企业、品牌、地区、奖项、证书明显有利的加分项。奖项未列具体名称不当然构成风险，应判断其与项目相关性及评审口径是否明确。
            11. 付款方式极端苛刻：付款比例、付款条件、验收条件、质保金或付款周期是否明显不利且缺乏合理依据。必须核对是否存在预付款、进度款或分期付款，避免遗漏前后条款。
            12. 时间节点不合理：公告、答疑、报名、投标、评审、履约等时间安排是否相互矛盾或明显压缩正常准备时间。
            13. 信息不透明：需求、评审、验收、报价、合同或资格条件是否存在关键内容缺失、歧义或无法核验。若已明确引用国家、行业或地方标准，不得直接认定为“标准缺失”，可判断其引用是否清晰、是否易于获取。

            输出结构（必须通过结构化输出返回，不得输出Markdown表格、代码块或其他自由文本）：
            {"risks": [每个风险一个对象]}，每个对象包含且仅包含以下字段：
            - riskType：风险类型，用简短名词概括（如“参数精确”“业绩定向”）。
            - riskLevel：风险程度，只能填“高”“中”“低”。
            - riskContent：风险内容，写明招标文件中的具体设置和原文位置。
            - riskAnalysis：风险分析，采用“风险逻辑：……；法律依据：《法律文件》第X条，条文要点：……；适用分析：……”的紧凑格式。法律依据优先引用知识库检索到的法律文件原文；缺少法律依据摘要的风险行不得输出。
            - suggestion：修改建议，必须可执行，例如删除指向性条件、改为性能参数、增加等效证明、细化评分标准、延长准备期限或补充公开说明。
            - confidence：置信度，只能填“高”“中”“低”。

            结果规则：
            1. 每个风险对象的 6 个字段都必须填写完整具体内容，任何字段不得为空、null 或省略；填不出实质内容的行宁可不输出。
            2. 法律依据优先引用知识库检索片段中的政府权威法律文件原文；不足时联网检索全国人大、国务院、财政部、国家发展改革委、国家市场监督管理总局及其他政府官网核验。不得把搜索摘要、媒体文章或非官方解读当作法律原文；不得编造法律文件、条款号或条文内容。若只能确认法律原则而无法核验具体条款，应在风险分析中写“具体条款待人工核验”，并将置信度降为“低”。
            3. 风险程度按证据和影响综合判断：有明确原文且可能实质限制竞争为高；有疑点但需进一步核验为中；轻微或证据不足为低。
            4. 每个明确风险单独一行；相同风险合并，保留最直接的原文位置和摘录。发现的每个维度问题都要输出对应风险行，不要只报告一个。
            5. 没有明确风险时 risks 仍输出一行：riskType 填“未发现明确风险”，riskLevel 填“低”，riskContent、riskAnalysis、suggestion 如实说明未发现明确异常，confidence 填“低”。
            6. 金额、日期、比例、分值、页码和条款编号必须以文件原文为准；内容过长时压缩但不得改变原意；文本中不要使用竖线字符。
            7. 审查目标是减少误报，同时识别真正具有竞争限制效果的异常条款，而不是尽可能多地发现风险。
            """;

    public static void main(String[] args) {
        SpringApplicationBuilder application = new SpringApplicationBuilder(AgentServiceApplication.class)
                .web(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run()) {
            ImmService immService = context.getBean(ImmService.class);
            BailianKnowledge legalKnowledge = context.getBean(BailianKnowledge.class);
            System.err.println("开始执行招标文件控标审查：IMM全文提取 -> qwen3.7-plus结构化输出...");
            String report = review(args.length > 0 ? args[0] : TEST_URL, immService, legalKnowledge);
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
     * IMM 提取失败、模型未返回内容或结构化 JSON 解析失败时抛 {@link IllegalStateException}，
     * 不做静默降级——审查报告宁可失败也不能输出残缺结论。
     *
     * @param documentUrl 招标文件在 OSS 上的 http/https 地址
     * @param immService  IMM 文档正文提取服务（Spring 容器中的 bean）
     * @param knowledge   百炼法律文件知识库（bidControlLegalKnowledge bean，手动检索注入）
     * @return Markdown 表格文本，表头固定为：风险类型 | 风险程度 | 风险内容 | 风险分析 | 修改建议 | 置信度
     */
    @SuppressWarnings("deprecation")
    public static String review(String documentUrl, ImmService immService, BailianKnowledge knowledge) throws Exception {
        String documentText = immService.extractDocumentText(documentUrl);
        System.err.println("IMM全文提取完成，字符数: " + documentText.length());

        OpenAIChatModel model = MODEL_CONFIG.qwen37PlusBidControlReportModel();
        ReActAgent agent = ReActAgent.builder()
                .name("bid-control-review")
                .sysPrompt(REVIEW_PROMPT)
                .model(model)
                .build();
        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .textContent("请审查以下招标文件全文并按系统要求返回结构化结果。\n\n"
                        + "===== 法律文件知识库检索片段 =====\n" + retrieveLegalContext(knowledge)
                        + "\n\n===== 招标文件全文 =====\n" + documentText)
                .build();
        Msg response = agent.call(request, hardenSchema(
                JsonSchemaUtils.generateSchemaFromClass(BidControlRiskTable.class))).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("qwen3.7-plus未返回结构化审查结果");
        }
        System.err.println("RAW_STRUCTURED_JSON<<< " + response.getTextContent());
        BidControlRiskTable table = OBJECT_MAPPER.readValue(response.getTextContent(), BidControlRiskTable.class);
        System.err.println("结构化结果解析完成，风险行数: " + table.risks().size());
        return MarkdownTables.render(table.risks()) + "\n\n" + DISCLAIMER;
    }

    /**
     * 用聚焦的检索词查询法律文件知识库，把命中片段格式化为可注入提示词的文本。
     *
     * <p>为什么不走 {@code RAGMode.GENERIC} 的自动检索：GenericRAGHook 直接拿用户消息
     * 当检索词，而这里用户消息是整份文档全文（数万字符），百炼 SDK 会把它塞进请求 URI，
     * 网关直接回 414 Request-URI Too Large，检索静默失败。手动检索用一个短而聚焦的
     * 法律依据查询词，命中片段显式拼进用户消息，长度可控、失败可见。
     *
     * @return 逐行编号的知识库片段文本；知识库不可用或无命中时返回占位说明，不中断审查
     */
    @SuppressWarnings("deprecation")
    private static String retrieveLegalContext(BailianKnowledge knowledge) {
        if (knowledge == null) {
            return "（法律知识库不可用，法律依据请联网核验）";
        }
        List<Document> documents = knowledge.retrieve(
                LEGAL_QUERY, RetrieveConfig.builder().limit(5).build()).block();
        if (documents == null || documents.isEmpty()) {
            return "（知识库未检索到相关法律片段，法律依据请联网核验）";
        }
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < documents.size(); index++) {
            String content = documents.get(index).getMetadata().getContentText();
            context.append("[片段").append(index + 1).append("] ")
                    .append(content == null ? "" : content.strip())
                    .append("\n");
        }
        return context.toString();
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
