package com.example.agentservice.agile;

import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.config.ModelConfig;

import com.example.agentservice.formatter.QwenDocDashScopeChatFormatter;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 招标文件控标风险审查：qwen-doc阅读文件，qwen3.7-plus整理最终报告。
 */
public class BidControlReview {

    private static final String DASH_SCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final ModelConfig MODEL_CONFIG = ModelConfig.standalone();
    private static final String TEST_URL = "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80%E7%AB%9E%E4%BA%89%E6%80%A7%E7%A3%8B%E5%95%86%E6%96%87%E4%BB%B69.17%E5%AE%9A%E7%A8%BF.doc";

    private static final String DOC_REVIEW_PROMPT = """
            你是招标文件控标风险审查专家。请完整阅读用户提供的招标文件，只审查招标文件本身是否存在可能限制竞争、定向设置或不合理设置的条款。

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

            只输出审查事实草稿，不输出最终Markdown表格。
            每一项风险必须包含：风险类型、风险程度、原文位置、原文摘录、风险事实、初步分析、可能适用的法律文件或规范名称、建议、置信度。
            初步分析中应明确区分“文件明确事实”和“审查推断”，并说明该条款具体可能如何影响竞争。
            没有直接证据的维度不要编造，标记为“未发现明确风险”。
            金额、日期、比例、分值、页码和条款编号必须以文件原文为准。
            你不负责联网核验法律条文；无法确认具体条款时只提供候选法律文件名称，不得编造条款号或法律原文。
            审查目标是减少误报，同时识别真正具有竞争限制效果的异常条款，而不是尽可能多地发现风险。
            """;

    private static final String REPORT_PROMPT = """
            你是招标文件控标风险审查报告汇总专家。输入是qwen-doc对一份招标文件的审查草稿。招标文件事实、金额、页码和条款只能来自审查草稿；法律依据必须通过联网检索政府、立法机关或司法机关的权威网站核验后使用。

            最终输出必须严格为：一张Markdown表格，加表格后唯一的一句免责声明。不得输出标题、前言、分析过程、JSON、代码块或其他表格。

            表头必须逐字保持如下顺序，不得增删或改名：
            | 风险类型 | 风险程度 | 风险内容 | 风险分析 | 修改建议 | 置信度 |

            规则：
            1. “风险程度”和“置信度”只能填写“高”“中”“低”，禁止填写未识别、未知、HIGH、MEDIUM、LOW、百分比或其他内容。
            2. 风险程度按证据和影响综合判断：有明确原文且可能实质限制竞争可为高；有疑点但需要进一步核验为中；轻微或证据不足为低。
            3. 每个明确风险单独一行；相同风险合并，保留最直接的原文位置和摘录。
            4. 风险内容写明招标文件中的具体设置和原文位置。风险分析必须同时包含三部分：风险逻辑；法律文件名称、具体条款及条文要点摘要；该法律要求与本项招标设置的适用关系。缺少法律依据摘要的风险行不得输出。
            5. 修改建议必须可执行，例如删除指向性条件、改为性能参数、增加等效证明、细化评分标准、延长准备期限或补充公开说明。
            6. 没有明确风险时仍输出一行：风险类型填“未发现明确风险”，风险程度填“低”，风险内容、风险分析和修改建议如实说明未发现明确异常，置信度填“低”。
            7. 法律依据优先检索并核验全国人大、国务院、财政部、国家发展改革委、国家市场监督管理总局及其他政府官网。不得把搜索摘要、媒体文章或非官方解读当作法律原文；不得编造法律文件、条款号或条文内容。若只能确认法律原则而无法核验具体条款，应在风险分析中写“具体条款待人工核验”，并将置信度降为“低”。
            8. 风险分析采用“风险逻辑：……；法律依据：《法律文件》第X条，条文要点：……；适用分析：……”的紧凑格式。
            9. 表格单元格内不得出现未转义的竖线；原文摘录过长时压缩但不得改变原意。

            表格之后只输出这一句：
            免责声明：本报告仅基于提供的招标文件作风险提示，不构成对招标文件违法违规或控标行为的最终认定，最终结论应结合采购需求、市场调查、编制说明及相关澄清材料综合判断。
            """;

    public static void main(String[] args) {
        System.err.println("开始执行招标文件控标审查：qwen-doc...");
        String docReview = reviewDocument(TEST_URL);
        System.err.println("qwen-doc审查完成，开始由qwen3.7-plus联网核验法律依据并汇总...");
        String report = summarize(docReview);
        System.out.println(report);
        System.err.println("qwen3.7-plus汇总完成。");
    }

    private static String reviewDocument(String documentUrl) {
        DashScopeChatModel model = MODEL_CONFIG.qwenDocTurboBidControlModel();

        ReActAgent agent = ReActAgent.builder().name("bid-control-doc-review").sysPrompt(DOC_REVIEW_PROMPT).model(model).build();
        Msg request = Msg.builder().role(MsgRole.USER).textContent("请完整阅读这份招标文件并执行控标风险审查。").metadata(Map.of(QwenDocDashScopeChatFormatter.DOC_URLS_METADATA_KEY, List.of(documentUrl), QwenDocDashScopeChatFormatter.FILE_PARSING_STRATEGY_METADATA_KEY, "auto")).build();
        Msg response = agent.call(request).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("qwen-doc未返回审查内容");
        }
        return response.getTextContent();
    }

    private static String summarize(String docReview) {
        OpenAIChatModel model = MODEL_CONFIG.qwen37PlusBidControlReportModel();

        ReActAgent agent = ReActAgent.builder().name("bid-control-report").sysPrompt(REPORT_PROMPT).model(model).build();
        Msg request = Msg.builder().role(MsgRole.USER).textContent("以下是qwen-doc完成的审查草稿，请按系统要求生成最终报告：\n\n" + docReview).build();
        Msg response = agent.call(request).block();
        if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("qwen3.7-plus未返回汇总报告");
        }
        return response.getTextContent().trim();
    }
}
