package com.example.agentservice.agile;

import com.example.document2entity.formatter.QwenDocDashScopeChatFormatter;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.EndpointType;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DocReview {

    private static final String ai_key = "DASHSCOPE_API_KEY";

    private static final String SysPrompt = """
            # 你是一名投标文件围标串标审查专家，审查比对多份投标文件信息判断是否存在围标串标行为
            # 审查维度：
             1. 投标人基本情况：识别所有投标人名称、首次报价、二次或最终报价、报价降幅。
             2. 异常审查结果：逐项列出疑似风险主体、风险等级、风险类型、证据、法律依据、置信度。
             3. 报价异常专项分析：抽取分项报价，对不同投标人的单价、总价、降幅、报价趋同或规律性差异进行比对。
             4. 文件雷同专项分析：比对资格承诺函、声明函、表格结构、排版格式、非典型错别字、标点、编号、固定句式等雷同特征。
             5. 分项风险汇总：按风险类型和投标人汇总高、中、低风险事项。
             6. 法律条款依据摘要：结合《招标投标法实施条例》《政府采购法实施条例》等条款说明适用情形。
             7. 建议处理措施：给出可执行核查建议和优先级。
             8. 整体风险评级：给出综合风险、最低价异常风险、文件真实性风险等评级。
            # 输出：
             必须只输出 Markdown，不要输出 JSON，不要输出代码块。
             必须严格输出 8 张表，表的数量、标题、表头必须与下面完全一致。
             所有“置信度”字段只能填写“高”“中”或“低”之一，不得填写百分比、小数、英文等级或其他表达。
             表格中无法确认的数据填写“未识别”，不得编造页码、金额、企业名称或法律事实。

             ## 一、投标人基本情况
             | 序号 | 投标人名称 | 首次报价（元） | 二次/最终报价（元） | 降幅 |

             ## 二、异常审查结果
             | 序号 | 投标人 | 风险等级 | 风险类型 | 证据 | 法律依据 | 置信度 |

             ## 三、报价异常专项分析
             | 分项项目 | 投标人1（元） | 投标人2（元） | 投标人3（元） | 投标人4（元） | 报价分布特征 |

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

             输出表格后，用“报告说明：”开头追加一段简短说明，说明结论是风险提示，最终认定需结合电子文档元数据、保证金账户、IP 地址、澄清说明等材料。
            """;

    private static final String UserPrompt = "请分析以下投标文件，判断是否存在围标串标行为";

    private static final List<String> pdfList = List.of("https://javawebemp.oss-cn-beijing.aliyuncs.com/%E5%8B%98%E6%B5%8B%E9%99%A2%E6%8A%95%E6%A0%87%E6%96%87%E4%BB%B6-%E5%AE%9C%E6%98%8C%E5%B8%82%E5%8B%98%E5%AF%9F%E6%B5%8B%E7%BB%98%E7%A0%94%E7%A9%B6%E9%99%A2%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B84754786566717863762.pdf",
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E4%B8%89%E5%B3%A1%E5%A4%A7%E5%AD%A6%E6%96%B0%E5%BB%BA%E5%AD%A6%E7%94%9F%E5%85%AC%E5%AF%93%E9%A1%B9%E7%9B%AE%E5%A4%9A%E6%B5%8B%E5%90%88%E4%B8%80%E7%AB%9E%E4%BA%89%E6%80%A7%E7%A3%8B%E5%95%86%E5%93%8D%E5%BA%94%E6%96%87%E4%BB%B6%EF%BC%88%E6%8A%95%E6%A0%87%E6%96%87%E4%BB%B6%EF%BC%89--%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B8-%E6%B9%96%E5%8C%97%E6%8D%B7%E5%B8%86%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B85201011215828763487.pdf");

    public static void main(String[] args) {
        JdkHttpTransport httpTransport = JdkHttpTransport.builder()
                .config(HttpTransportConfig.builder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .readTimeout(Duration.ofMinutes(20))
                        .writeTimeout(Duration.ofMinutes(2))
                        .build())
                .build();

        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey(ai_key)
                .modelName("qwen-doc-turbo")
                .endpointType(EndpointType.TEXT)
                .formatter(new QwenDocDashScopeChatFormatter())
                .httpTransport(httpTransport)
                .stream(true)
                .defaultOptions(GenerateOptions.builder()
                        .executionConfig(ExecutionConfig.builder()
                                .timeout(Duration.ofMinutes(20))
                                .maxAttempts(1)
                                .build())
                        .build())
                .build();

        ReActAgent agent = ReActAgent.builder()
                .name("doc-review")
                .sysPrompt(SysPrompt)
                .model(model)
                .build();

        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .textContent(UserPrompt)
                .metadata(Map.of(
                        QwenDocDashScopeChatFormatter.DOC_URLS_METADATA_KEY, pdfList,
                        QwenDocDashScopeChatFormatter.FILE_PARSING_STRATEGY_METADATA_KEY, "auto"
                ))
                .build();

        System.out.println("已向 qwen-doc-turbo 提交 " + pdfList.size()
                + " 份 PDF，正在解析和审查，请耐心等待...");
        Msg response = agent.call(request).block();
        if (response == null || response.getTextContent().isBlank()) {
            throw new IllegalStateException("Qwen-Doc review returned no content");
        }
        System.out.println(response.getTextContent());
    }

}
