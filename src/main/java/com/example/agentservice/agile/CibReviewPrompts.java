package com.example.agentservice.agile;

import java.util.List;

public final class CibReviewPrompts {

    public static final List<Dimension> DIMENSIONS = List.of(
            new Dimension("BASIC_INFO", "基础信息雷同分析", """
                    先逐份识别文件对应的真实投标人和报价轮次，再核验公司名称、地址、法定代表人、联系人、电话、邮箱、银行账户等基础信息。
                    二次报价文件必须归并到原投标人，不得作为新投标人；同一投标人的首次报价和二次报价相同不属于跨投标人雷同。
                    提取“首次报价/第一次响应报价/报价总价”和“二次报价/最终报价”的原文数值。
                    严格排除采购预算、最高限价、项目估算价、分项小计、保证金、报价得分和其他投标人的金额。
                    每个金额必须同时给出原文位置和包含金额的短摘录；没有明确出现的数值必须为null，不得用首次报价代替最终报价。
                    只有首次报价和最终报价均有明确原文时才计算降幅，按(首次报价-最终报价)/首次报价返回小数；否则reductionRate为null。
                    """, """
                    ,"bidderProfiles":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["bidder","firstQuote","finalQuote","reductionRate","firstQuoteLocation","firstQuoteExcerpt","finalQuoteLocation","finalQuoteExcerpt"],"properties":{"bidder":{"type":"string"},"firstQuote":{"type":["number","null"]},"finalQuote":{"type":["number","null"]},"reductionRate":{"type":["number","null"]},"firstQuoteLocation":{"type":["string","null"]},"firstQuoteExcerpt":{"type":["string","null"]},"finalQuoteLocation":{"type":["string","null"]},"finalQuoteExcerpt":{"type":["string","null"]}}}},
                    "similarityItems":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["field","bidders","values"],"properties":{"field":{"type":"string"},"bidders":{"type":"array","minItems":2,"items":{"type":"string"}},"values":{"type":"array","minItems":2,"items":{"type":"string"}}}}}
                    """, ",\"bidderProfiles\",\"similarityItems\""),
            new Dimension("LAYOUT", "排版结构雷同分析", """
                    只比对可从解析文本确认的目录层级、章节顺序、标题编号、表格列结构、固定段落位置和异常空白。
                    必须先排除招标文件、响应文件模板和统一格式要求；只有两个及以上投标人出现相同的非必要结构组合或相同异常，才记录finding。
                    Qwen-Long无法可靠保留字体、字号、页边距、颜色和图片视觉细节，不能根据这些内容判断排版雷同；无法确认时使用UNKNOWN。
                    """, """
                    ,"similarityItems":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["format","bidders","comparison"],"properties":{"format":{"type":"string"},"bidders":{"type":"array","minItems":2,"items":{"type":"string"}},"comparison":{"type":"string"}}}}
                    """, ",\"similarityItems\""),
            new Dimension("PAGE_NUMBER", "页码错误雷同分析", """
                    仅检查文本中明确出现的页码、页码标签或页码序列，记录缺失、重复、跳号、错号及其明确位置。
                    不得把文件解析条数、章节编号或模型没有看到页码当作物理总页数；解析无法确认时totalPages必须为null，不能据此断言页码正常。
                    只有不同投标人共享同一种非必要页码错误模式时才填写pageErrors。
                    """, """
                    ,"pageSummary":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["bidder","totalPages"],"properties":{"bidder":{"type":"string"},"totalPages":{"type":["integer","null"],"minimum":0}}}},
                    "pageErrors":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["pattern","bidders","locations"],"properties":{"pattern":{"type":"string"},"bidders":{"type":"array","minItems":2,"items":{"type":"string"}},"locations":{"type":"array","minItems":2,"items":{"type":"string"}}}}}
                    """, ",\"pageSummary\",\"pageErrors\""),
            new Dimension("TEXT_SIMILARITY", "文本重复与语义相似分析", """
                    逐对比对投标人的技术方案、服务承诺、实施方法、质量控制等自由编写内容，识别同序大段文本、相同非通用句式和共同异常表达。
                    一条文本雷同finding必须同时提供双方对应位置的原文摘录，摘录至少包含能够证明相似性的连续语句；只相同一个专业术语、数字、法规或招标要求不得作为异常。
                    排除招标文件原文、法规原文、法定格式、统一模板和行业通用表述。无法可靠量化时，重复率和语义相似度必须返回null，不得编造百分比。
                    """, """
                    ,"similarityMetrics":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["bidders","textRepetitionRate","semanticSimilarity"],"properties":{"bidders":{"type":"array","minItems":2,"items":{"type":"string"}},"textRepetitionRate":{"type":["number","null"],"minimum":0,"maximum":100},"semanticSimilarity":{"type":["number","null"],"minimum":0,"maximum":100}}}}
                    """, ",\"similarityMetrics\""),
            new Dimension("TYPO_SIMILARITY", "错别字雷同分析", """
                    先识别各投标文件中的错别字、漏字、多字、异常标点和明显病句，再核验同一个非典型错误是否出现在不同投标人的对应内容中。
                    共同错误必须是同一个错误字符串，且双方证据摘录中都必须逐字出现该错误字符串；一个正确、一个错误，或两个不同标准号之间的数字差异，不得认定为共同错误。
                    similarTypos必须给出错误原文、建议改正文本、涉及投标人；普通用词差异、行业简称和单个常见错字不能作为雷同发现。
                    无法确认文本确实错误时不要计数；无法完成逐文核查时typoCount使用null而不是0。
                    """, """
                    ,"typoMetrics":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["bidder","typoCount"],"properties":{"bidder":{"type":"string"},"typoCount":{"type":["integer","null"],"minimum":0}}}},
                    "similarTypos":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["wrongText","correctText","bidders"],"properties":{"wrongText":{"type":"string"},"correctText":{"type":"string"},"bidders":{"type":"array","minItems":2,"items":{"type":"string"}}}}}
                    """, ",\"typoMetrics\",\"similarTypos\""),
            new Dimension("COLLUSION_RISK", "围标串标综合风险分析", """
                    本专项只负责跨投标人的报价、身份和其他直接关联线索复核，不重复评价文本相似、排版、页码和错别字专项。
                    重点核查相同分项单价与工程量、异常一致的报价结构、联系人或账户等基础信息，以及文件中明确写出的共同来源。
                    每个报价结论必须在双方证据中同时出现比较所需的数值；一方缺少工程量或单价时不得写成完全一致。
                    IP地址、保证金账户、工商股权、关键人员任职和文档元数据未提供时，只能列入recommendations，不得作为事实或提高风险等级。
                    只要发现报价或身份关联线索，就必须为每条线索生成riskFactors；riskFactors的legalBasis必须填写原始依据，无法确认准确条款时填写“需人工复核”，不得留空。单一弱线索不得评为高风险；confidence只能为HIGH、MEDIUM或LOW。
                    """, """
                    ,"riskFactors":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["type","bidders","description","legalBasis","confidence"],"properties":{"type":{"type":"string"},"bidders":{"type":"array","minItems":2,"items":{"type":"string"}},"description":{"type":"string"},"legalBasis":{"type":"string"},"confidence":{"type":"string","enum":["HIGH","MEDIUM","LOW"]}}}}
                    """, ",\"riskFactors\"")
    );

    public static final String REPORT_PROMPT = """
            你是围标串标审查报告汇总专家。用户会提供六个专项Agent返回的JSON结果。

            汇总规则：
            1. 只能使用专项JSON中的事实，不读取原始文件，不新增证据、金额、投标人、页码、相似度或法律条款。
            2. 合并重复发现，但保留涉及投标人、位置和原文证据；专项失败或数据不足时明确说明“证据不足”或“未发现明确异常”。
            3. 风险等级和置信度只能输出“高”“中”“低”：HIGH=高、MEDIUM=中、LOW=低。风险等级和置信度禁止输出“未识别”；专项失败或证据不足时，按现有证据填写“低”，并在说明中注明证据不足。
            4. 高风险必须有多个独立维度相互印证；不同专项结论冲突时采取较保守等级并说明需人工复核。
            5. 二次报价文件归并到原投标人，不作为独立投标人。
            6. 只输出Markdown，不输出JSON、代码块、分析过程或开场白。
            7. 严格输出以下8张表。每张表至少一行，无有效数据时填写“未发现明确异常”；风险等级和置信度仍只能填写“高”“中”“低”。
            8. 第三张表把投标人占位列替换为真实投标人名称，按实际投标人数量增减，顺序与第一张表一致。
            9. 报价只允许使用BASIC_INFO中有原文位置和摘录支持的金额；采购预算、最高限价、分项金额不得当作投标总价。
            10. 异常表的置信度优先使用finding.confidence，其次使用riskFactors.confidence，翻译为高/中/低；缺失、UNKNOWN或明确无法判断时填写“低”，并在具体说明中写明证据不足。没有异常时写“未发现明确异常”，不要整表填“未识别”。
            11. 第六张“法律条款依据摘要”是强制数据表，不得留空。先遍历所有专项JSON的riskFactors，再生成该表：每个riskFactor至少对应一行；法律文件和条款必须直接使用legalBasis中的原文，不得自行编造具体法条。若legalBasis为“需人工复核”，法律文件填“需人工复核”，条款填“未明确”，内容摘要使用riskFactor.description，适用情形使用riskFactor.type及涉及投标人。
            12. 如果所有专项JSON都没有riskFactors，第六张表仍保留一行：法律文件“未发现明确法律依据”、条款“未明确”、内容摘要“专项结果未提供可直接引用的法律依据”、适用情形“需结合人工复核”。其他表同样不得因为字段为空而省略表头或整张表。

            ## 一、投标人基本情况
            | 序号 | 投标人名称 | 首次报价（元） | 二次/最终报价（元） | 降幅 |

            ## 二、异常审查结果
            | 序号 | 投标人 | 风险等级 | 风险类型 | 证据 | 法律依据 | 置信度 |

            ## 三、报价异常专项分析
            | 分项项目 | 投标人1（元） | 投标人2（元） | 报价分布特征 |

            ## 四、文件雷同专项分析
            | 雷同特征 | 涉及投标人 | 具体描述 | 置信度 |

            ## 五、分项风险汇总表
            | 风险类型 | 投标人 | 风险等级 | 具体说明 |

            ## 六、法律条款依据摘要
            | 法律文件 | 条款 | 内容摘要 | 适用情形 |

            ## 七、建议处理措施
            | 序号 | 处理建议 | 优先级别 |

            ## 八、整体风险评级
            | 评级维度 | 评级 | 说明 |

            八张表之后，只追加一段以“报告说明：”开头的文字，说明结论仅为风险提示，最终认定需结合电子文档元数据、保证金账户、IP地址、工商关联和投标人澄清材料。
            """;

    private static final String FINDING_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["bidders","indicator","evidence","assessment","confidence"],"properties":{
            "bidders":{"type":"array","minItems":2,"items":{"type":"string"}},
            "indicator":{"type":"string"},
            "evidence":{"type":"array","minItems":2,"items":{"type":"object","additionalProperties":false,"required":["bidder","document","location","excerpt"],"properties":{
            "bidder":{"type":"string"},"document":{"type":"string"},"location":{"type":"string"},"excerpt":{"type":"string","maxLength":160}}}},
            "assessment":{"type":"string","maxLength":400},
            "confidence":{"type":"string","enum":["HIGH","MEDIUM","LOW"]}}}
            """;

    private CibReviewPrompts() {
    }

    public static String promptFor(Dimension dimension) {
        String schema = """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,
                "required":["dimension","summary","riskLevel","findings","recommendations"%s],"properties":{
                "dimension":{"type":"string","const":"%s"},
                "summary":{"type":"string","maxLength":500},
                "riskLevel":{"type":"string","enum":["HIGH","MEDIUM","LOW","UNKNOWN"]},
                "findings":{"type":"array","maxItems":8,"items":%s},
                "recommendations":{"type":"array","maxItems":8,"items":{"type":"string","maxLength":300}}%s}}
                """.formatted(dimension.requiredFields(), dimension.code(), FINDING_SCHEMA, dimension.properties())
                .replaceAll("\\s+", "");

        return """
            你是政府采购项目%s专家。本次只负责这一维度，不执行其他专项，也不生成最终报告。

            审查任务：
            %s

            工作方法：
            - 先建立“文件 -> 投标人 -> 报价轮次”的内部对应关系，再进行跨投标人比较；同一投标人的主文件、二次报价和补充文件视为一个投标人。
            - 对每个候选异常建立证据账本：双方投标人、文件名称、页码/章节/表名、双方原文摘录。只有证据账本完整才允许输出finding。
            - 区分“明确未发现”和“无法确认”：完整检查后无异常才用LOW；页面、视觉、原文或位置无法确认时用UNKNOWN并在summary中说明。
            - 输出前复核每个finding：bidders至少两个不同投标人，evidence至少两条且每个投标人至少一条，assessment不得超出excerpt支持的事实。

            证据要求：
            - 只依据已上传文件中的明确内容，不使用外部知识补充事实。
            - 共同异常必须涉及至少两个真实投标人。同一投标人的主文件和二次报价文件不构成跨投标人雷同。
            - 每条finding至少提供双方证据，写明投标人、文件类型、页码/章节/表名和原文短句；无法定位的内容不得作为finding。
            - 招标文件原文、法定格式、统一模板、法规原文和行业通用表述不得单独作为异常。
            - 没有明确异常时findings返回空数组，riskLevel使用LOW或UNKNOWN，不得为了填充结果而编造证据。
            - 不要根据summary中的概括替代证据，不要将“可能、疑似、看起来”改写成已经确认的事实。

                输出要求：
                只输出一个符合以下JSON Schema的对象，不输出Markdown、代码围栏、解释或其他文字。

                %s
                """.formatted(dimension.name(), dimension.focus(), schema);
    }

    public record Dimension(
            String code,
            String name,
            String focus,
            String properties,
            String requiredFields) {
    }
}
