package com.example.agentservice.agile;


import cn.hutool.json.JSONUtil;
import com.example.agentservice.config.AgentServiceConfig;
import com.example.agentservice.entity.Doc;
import com.example.agentservice.entity.RequirementReviewResult;
import com.example.agentservice.formatter.QwenDocDashScopeChatFormatter;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
public class DocAgent {


    private static final String RULE_EXTRACTION_PROMPT = """
            # 角色
            你是采购需求论证规则文件的严格校验与结构化提取专家。
            你的任务是先校验用户上传的 Excel 是否为完整、自洽、可用的采购需求论证指标评定规则；只有全部校验通过后，才允许提取指标。

            # 总体原则
            - 校验优先于提取，必须先完整检查全部数据行，再决定是否输出指标。
            - 不得容错、修正、补齐、推测或忽略任何实质性错误。
            - 不得把“能够识别部分指标”等同于文件可用。
            - 所有数字按原始小数精确比较，禁止取整、截断或四舍五入后再判断。
            - 尾部仅有格式但所有业务单元格均为空的行可以忽略。

            # 第一步：文件可用性校验
            必须依次完成以下五类校验。任意一项失败，整个文件立即判定为不可用。

            ## 1. 表格结构校验
            1. 必须存在主指标、子指标、量化要求或基本需求内容、满分、评分规则等可识别列。
            2. 每个非空数据行必须能唯一归属于一个主指标和一个子指标。
            3. 合并单元格表示的主指标分组必须连续、边界明确，不得出现跨组、错位或无法归属的数据行。
            4. 主指标和子指标的原始顺序必须能够唯一确定。

            ## 2. 字段完整性校验
            1. 每个主指标必须有名称和明确的百分比权重。
            2. 每个子指标必须有名称、明确的百分比权重、量化要求或基本需求内容、满分和评分规则。
            3. 名称中声明有权重时，权重必须是可解析的数字，不允许缺失百分号或只写名称不写权重。
            4. 内容不得明显残缺或截断。例如：以“参考量化指标：”结尾却没有指标项、编号列表缺项、句子或括号明显未结束。
            5. 评分规则引用若干条件时，量化要求中必须存在对应且完整的条件。

            ## 3. 百分比权重校验
            1. 所有主指标百分比之和必须严格等于 100。
            2. 每个主指标下所有子指标百分比之和必须严格等于该主指标百分比。
            3. 子指标百分比不得大于所属主指标百分比。
            4. 同一指标名称、权重列及其他单元格中出现的权重必须一致。
            5. 例如 8.75、4.8 等小数必须原样保留，不得转换成 8 或 4。

            ## 4. 满分一致性校验
            1. 每个子指标的满分必须为有效非负数字。
            2. 子指标名称中的分值或百分比若表示该项满分，必须与满分列严格一致。
            3. 评分规则中可获得的最高分必须与满分列严格一致。
            4. 每个主指标下子指标满分之和必须与该主指标声明的总分或权重严格一致。
            5. 不允许出现同一子指标在名称、满分列和评分规则中的最高分互相冲突。

            ## 5. 内容与评分规则一致性校验
            1. 量化要求和评分规则必须明确对应当前子指标，不得错行或串行。
            2. 评分档位必须可理解且不存在互相矛盾的最高分、条件数量或一票否决规则。
            3. 不得依靠行业经验或外部知识补全缺失内容后再判定为可用。

            # 不可用时的唯一输出
            只要上述任意校验失败，立即停止提取，只返回下面这一行 JSON：
            {"order":{}}
            不得输出原因、说明、Markdown 代码块或其他字段，也不得返回部分指标。

            # 第二步：结构化提取
            只有五类校验全部通过后才执行：
            1. 严格按 Excel 原始顺序提取主指标及其子指标。
            2. 所有名称、量化要求和评分规则必须来自原始单元格，不得改写或补充。
            3. proportion 和 maxScore 使用 JSON number，保留原始小数。
            4. serialNumber 从 1 开始，分别按主指标和全部子指标的原始出现顺序递增。
            5. KeyMetric.rules 对应原表中明确属于主指标整体的评分规则；没有则输出空字符串。
            6. KeyMetric.score 仅在原表存在明确实际得分时填写，否则输出 null。
            7. SubIndicator.roles 对应“通用基本需求内容”或“量化要求”原文。
            8. SubIndicator.rules 对应当前子指标的“评分规则”原文。

            # 输出 JSON Schema
            输出必须是满足以下 JSON Schema（Draft 2020-12）的单个 JSON 对象，不得使用 Markdown 代码块：
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "order": {
                  "type": "object",
                  "patternProperties": {
                    "^[1-9][0-9]*$": { "$ref": "#/$defs/keyMetric" }
                  },
                  "additionalProperties": false
                }
              },
              "required": ["order"],
              "additionalProperties": false,
              "$defs": {
                "keyMetric": {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string" },
                    "proportion": { "type": "number" },
                    "maxScore": { "type": "number" },
                    "rules": { "type": "string" },
                    "score": { "type": ["number", "null"] },
                    "serialNumber": { "type": "integer", "minimum": 1 },
                    "subIndicators": {
                      "type": "array",
                      "items": { "$ref": "#/$defs/subIndicator" },
                      "minItems": 1
                    }
                  },
                  "required": ["name", "proportion", "maxScore", "rules", "score", "serialNumber", "subIndicators"],
                  "additionalProperties": false
                },
                "subIndicator": {
                  "type": "object",
                  "properties": {
                    "name": { "type": "string" },
                    "proportion": { "type": "number" },
                    "maxScore": { "type": "number" },
                    "roles": { "type": "string" },
                    "rules": { "type": "string" },
                    "serialNumber": { "type": "integer", "minimum": 1 }
                  },
                  "required": ["name", "proportion", "maxScore", "roles", "rules", "serialNumber"],
                  "additionalProperties": false
                }
              }
            }
            """;

    private final String apiKey;

    public DocAgent(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DashScope API key must not be blank");
        }
        this.apiKey = apiKey;
    }

    public static void main(String[] args) {
        String apiKey = AgentServiceConfig.dashScopeApiKey();
        String ruleFileUrl = args.length > 0 ? args[0] : AgentServiceConfig.ruleFileUrl();
        String requirementFileUrl = args.length > 1 ? args[1] : AgentServiceConfig.requirementFileUrl();

        RequirementReviewResult result = new DocAgent(apiKey)
                .execute(ruleFileUrl, requirementFileUrl);
        System.out.println(JSONUtil.toJsonPrettyStr(result));
    }

    public RequirementReviewResult execute(String ruleFileUrl, String requirementFileUrl) {
        log.info("阶段1/3：开始提取并校验规则文件");
        Doc rules = extractRules(ruleFileUrl);
        if (rules.getOrder() == null || rules.getOrder().isEmpty()) {
            throw new IllegalStateException("规则文件不合规，流程已停止");
        }
        log.info("阶段1/3：规则提取完成，共 {} 个主指标", rules.getOrder().size());

        log.info("阶段2/3：开始并发评审需求文件");
        RequirementReviewResult result = new RequirementReviewService(apiKey)
                .review(rules, requirementFileUrl);
        log.info("阶段2/3：最小指标评审完成，共 {} 个，成功 {} 个，失败 {} 个",
                result.getLeafIndicatorCount(),
                result.getSuccessfulLeafIndicatorCount(),
                result.getFailedLeafIndicatorCount());
        log.info("阶段3/3：汇总报告完成，总得分 {}/{}",
                result.getSummaryReport().getTotalScore(),
                result.getSummaryReport().getTotalMaxScore());
        return result;
    }

    private Doc extractRules(String ruleFileUrl) {
        if (ruleFileUrl == null || ruleFileUrl.isBlank()) {
            throw new IllegalArgumentException("Rule file URL must not be blank");
        }
        ReActAgent ruleAgent = ReActAgent.builder()
                .name("rule-extractor")
                .sysPrompt(RULE_EXTRACTION_PROMPT)
                .model(DashScopeChatModel.builder()
                        .apiKey(apiKey)
                        .modelName("qwen-doc-turbo")
                        .formatter(new QwenDocDashScopeChatFormatter())
                        .defaultOptions(GenerateOptions.builder()
                                .temperature(0.1)
                                .build())
                        .build())
                .build();

        Msg msg = Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(
                        TextBlock.builder()
                                .text("请解析这个 Excel 文件，并严格按系统提示输出 JSON。")
                                .build()
                ))
                .metadata(Map.of(
                        QwenDocDashScopeChatFormatter.DOC_URLS_METADATA_KEY, List.of(ruleFileUrl),
                        QwenDocDashScopeChatFormatter.FILE_PARSING_STRATEGY_METADATA_KEY, "auto"
                ))
                .build();

        Msg response = ruleAgent.call(msg).block();
        if (response == null) {
            throw new IllegalStateException("Qwen-Doc returned no response");
        }
        String responseText = response.getTextContent();
        log.debug("规则提取原始响应：{}", responseText);
        return QwenDocResponseParser.parse(responseText);
    }

}
