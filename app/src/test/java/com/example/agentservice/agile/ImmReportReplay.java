package com.example.agentservice.agile;

import com.example.agentservice.entity.CibDimensionResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** 本地回放专项结果，只调用一次报告模型。该文件已加入.gitignore。 */
public class ImmReportReplay {

    private static final String RESULTS_JSON = """
            [
              {
                "dimension":"BASIC_INFO",
                "summary":"经核验，本项目共有两家投标人：宜昌几何测绘科技有限公司和湖北捷帆科技有限公司。两家投标人的公司名称、注册地址、法定代表人、联系人、联系电话、电子邮箱及银行账户等基础信息均不相同，未发现基础信息雷同异常。两家投标人均提供了首次报价和二次报价，降幅计算正常。",
                "riskLevel":"LOW",
                "findings":[],
                "recommendations":["未发现基础信息雷同异常，建议继续对其他维度进行审查。"],
                "bidderProfiles":[
                  {"bidder":"宜昌几何测绘科技有限公司","firstQuote":174965.21,"finalQuote":170730.0,"reductionRate":0.0242,"firstQuoteLocation":"Scan-宜昌几何测绘科技有限公司8960772218806061282.pdf 第9页 报价一览表","firstQuoteExcerpt":"小写：174965.21","finalQuoteLocation":"二轮报价(1)-宜昌几何测绘科技有限公司1732505438540525103.pdf 第1页 报价一览表","finalQuoteExcerpt":"小写：170730.00"},
                  {"bidder":"湖北捷帆科技有限公司","firstQuote":184613.21,"finalQuote":110000.0,"reductionRate":0.4042,"firstQuoteLocation":"湖北捷帆科技有限公司投标文件5201011215828763487.pdf 第14页 报价一览表","firstQuoteExcerpt":"小写：184613.21 元","finalQuoteLocation":"2轮报价--湖北捷帆科技有限公司3512886208567055417.pdf 第1页 报价一览表","finalQuoteExcerpt":"小写：110000.00 元"}
                ],
                "success":true
              },
              {
                "dimension":"COLLUSION_RISK",
                "summary":"两家投标人的分项报价工程量完全一致，但该工程量为招标文件给定的暂估数量，按要求填报不属于异常一致。两家的单价、总价不同，联系人和账户等基础信息无关联，未发现明确围标串标线索。",
                "riskLevel":"LOW",
                "findings":[],
                "recommendations":["进一步核查IP地址、保证金缴纳账户及工商股权关系。","核查投标文件元数据，确认是否存在同一台电脑制作的情况。"],
                "success":true
              },
              {
                "dimension":"PAGE_NUMBER",
                "summary":"两家投标人的响应文件页码均连续完整，未发现缺失、重复、跳号或错号，也未发现共享同一种非必要页码错误模式。",
                "riskLevel":"LOW",
                "findings":[],
                "recommendations":[],
                "pageSummary":[{"bidder":"宜昌几何测绘科技有限公司","totalPages":103},{"bidder":"湖北捷帆科技有限公司","totalPages":232}],
                "success":true
              },
              {
                "dimension":"TYPO_SIMILARITY",
                "summary":"在两份投标文件中发现两处明显的非典型错误雷同：均将“项目实施”误写为“案件实施”；均出现“中标人对测绘成果质量负责市自然资源和规划局将根据该实施细则推行情况”的相同漏标点错误。",
                "riskLevel":"HIGH",
                "findings":[
                  {
                    "bidders":["宜昌几何测绘科技有限公司","湖北捷帆科技有限公司"],
                    "indicator":"共同错别字：将项目实施误写为案件实施",
                    "evidence":[
                      {"bidder":"宜昌几何测绘科技有限公司","document":"Scan-宜昌几何测绘科技有限公司8960772218806061282.pdf","location":"第55页 商务响应偏离表","excerpt":"招标文件未提及但在案件实施过程中必须发生的一切费用。"},
                      {"bidder":"湖北捷帆科技有限公司","document":"湖北捷帆科技有限公司投标文件5201011215828763487.pdf","location":"第73页 采购需求报价要求响应承诺函","excerpt":"招标文件未提及但在案件实施过程中必须发生的一切费用。"}
                    ],
                    "assessment":"两家公司在同类条款中出现完全相同的非典型错词，可能存在抄袭或使用相同错误模板。",
                    "confidence":"HIGH"
                  },
                  {
                    "bidders":["宜昌几何测绘科技有限公司","湖北捷帆科技有限公司"],
                    "indicator":"共同语病或漏标点",
                    "evidence":[
                      {"bidder":"宜昌几何测绘科技有限公司","document":"Scan-宜昌几何测绘科技有限公司8960772218806061282.pdf","location":"第66页 工作目标","excerpt":"中标人对测绘成果质量负责市自然资源和规划局将根据该实施细则推行情况"},
                      {"bidder":"湖北捷帆科技有限公司","document":"湖北捷帆科技有限公司投标文件5201011215828763487.pdf","location":"第76页 成果报送","excerpt":"中标人对测绘成果质量负责市自然资源和规划局将根据该实施细则推行情况"}
                    ],
                    "assessment":"两份文件在相同语句位置均缺少句号，形成相同的非典型语病，需重点复核文件来源。",
                    "confidence":"HIGH"
                  }
                ],
                "recommendations":["重点核查两家投标人的技术方案和商务文件。","要求投标人说明文件编制过程。","审查文件制作机器码或IP地址。"],
                "typoMetrics":[{"bidder":"宜昌几何测绘科技有限公司","typoCount":2},{"bidder":"湖北捷帆科技有限公司","typoCount":2}],
                "similarTypos":[
                  {"wrongText":"案件实施过程中","correctText":"项目实施过程中","bidders":["宜昌几何测绘科技有限公司","湖北捷帆科技有限公司"]},
                  {"wrongText":"中标人对测绘成果质量负责市自然资源和规划局将根据该实施细则推行情况","correctText":"中标人对测绘成果质量负责。市自然资源和规划局将根据该实施细则推行情况","bidders":["宜昌几何测绘科技有限公司","湖北捷帆科技有限公司"]}
                ],
                "success":true
              },
              {
                "dimension":"LAYOUT",
                "summary":"两份文件的目录层级、章节顺序及表格结构高度一致，但主要源于对招标文件规定板块顺序的响应。双方标题编号体系存在差异，表格列结构多为统一模板要求，未发现超出模板要求的非必要结构组合或相同异常空白。",
                "riskLevel":"LOW",
                "findings":[],
                "recommendations":[],
                "success":true
              },
              {
                "dimension":"TEXT_SIMILARITY",
                "summary":"未发现技术方案、服务承诺和实施方法等自由编写内容存在跨投标人的同序大段文本雷同或共同异常表达。部分表格及承诺函条款高度一致，但属于对招标文件原文及法定格式模板的直接响应，应予排除。",
                "riskLevel":"LOW",
                "findings":[],
                "recommendations":[],
                "similarityMetrics":[{"bidders":["宜昌几何测绘科技有限公司","湖北捷帆科技有限公司"],"textRepetitionRate":null,"semanticSimilarity":null}],
                "success":true
              }
            ]
            """;

    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<CibDimensionResult> results = objectMapper.readValue(
                RESULTS_JSON, new TypeReference<List<CibDimensionResult>>() {});
        System.out.println("已加载专项结果：" + results.size() + "项，仅执行汇总报告阶段...");
        System.out.println(new ImmBeforeReview(null).generateReport(results));
    }
}
