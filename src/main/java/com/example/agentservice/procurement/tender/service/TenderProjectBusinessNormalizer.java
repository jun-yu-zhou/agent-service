package com.example.agentservice.procurement.tender.service;

import cn.hutool.core.convert.NumberChineseFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 将项目数据库值整理成可直接写入招标文件的中文业务事实。 */
@Component
public class TenderProjectBusinessNormalizer {

    /** 招标文件中统一使用的日期格式。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss");
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy年MM月");

    /** 业务库的项目类型、采购方式编码。 */
    private static final Map<String, String> PROJECT_TYPES =
            Map.of("1", "货物", "2", "工程", "3", "服务");
    private static final Map<String, String> PROCUREMENT_METHODS = Map.of(
            "1", "校内招标", "2", "邀请招标", "3", "单一来源",
            "4", "竞争性谈判", "5", "竞争性磋商", "6", "询价");
    private static final Map<String, String> COMMENT_TYPES = Map.ofEntries(
            Map.entry("0", "基本信息"), Map.entry("1", "采购清单"),
            Map.entry("2", "现场踏勘"), Map.entry("3", "投标担保"),
            Map.entry("4", "履约担保"), Map.entry("5", "资格要求"),
            Map.entry("6", "评标方法"), Map.entry("7", "招标答疑"),
            Map.entry("8", "招标文件"), Map.entry("9", "开标安排"),
            Map.entry("10", "公告要求"), Map.entry("11", "招标代理"),
            Map.entry("12", "企业资质"), Map.entry("13", "人员资质"),
            Map.entry("14", "最高限价"), Map.entry("15", "工程量清单"),
            Map.entry("17", "成本警戒线"), Map.entry("18", "施工图纸"),
            Map.entry("19", "主要材料品牌"), Map.entry("20", "投标承诺"),
            Map.entry("38", "实质性响应条款"), Map.entry("47", "中小企业政策"));

    private final ObjectMapper objectMapper;

    public TenderProjectBusinessNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 汇总可直接写入招标文件的业务事实。
     *
     * @param project 已转换为 camelCase 的项目主表数据
     * @param comments 已将 JSON 正文展开的项目补充资料
     * @param capitalSources 采购单位配置的资金来源
     * @param projectDates 公告、报名、投标、开标等项目时间
     * @param items 采购品目及其核心产品标记
     * @return 中文化、格式化后的业务事实
     */
    public ObjectNode normalize(Map<String, Object> project, List<Map<String, Object>> comments,
            List<Map<String, Object>> capitalSources, List<Map<String, Object>> projectDates,
            List<Map<String, Object>> items) {
        ObjectNode facts = objectMapper.createObjectNode();
        put(facts, "projectTypeName", PROJECT_TYPES.get(text(project.get("projectType"))));
        put(facts, "procurementMethodName",
                PROCUREMENT_METHODS.get(text(project.get("classifyCode"))));
        put(facts, "evaluationMethodName", evaluationMethod(project.get("evaluateWayCode")));
        put(facts, "evaluationModeName", evaluationMode(project.get("evaluatingBidType")));
        put(facts, "acceptImportedProducts", accepted(project.get("isAcceptInput")));
        put(facts, "fundingSourceName", fundingSource(project.get("fundingSource"), capitalSources));
        put(facts, "purchaseMoneyUppercase", uppercaseMoney(project.get("purchaseMoney")));
        put(facts, "discountedRatePercent", percentage(project.get("discountedRate")));
        facts.set("formattedDates", formattedDates(project, projectDates));
        facts.set("projectParties", projectParties(project));
        facts.set("projectContacts", projectContacts(project));
        facts.set("openingArrangement", openingArrangement(project, projectDates));
        facts.set("coreProduct", coreProduct(items));
        facts.set("performanceGuarantee", performance(comment(comments, "4")));
        facts.set("substantiveRequirements", substantiveRequirements(comment(comments, "38")));
        JsonNode company = comment(comments, "47");
        if (company != null) {
            put(facts, "companyType", company.path("companyType").asText());
        }
        facts.set("supplementaryMaterials", supplementaryMaterials(comments));
        return facts;
    }

    /** 明确采购人、招标人和代理机构的角色，避免模型在封面及邀请函中混用。 */
    private ObjectNode projectParties(Map<String, Object> project) {
        ObjectNode parties = objectMapper.createObjectNode();
        put(parties, "purchaser", text(project.get("collegeName")));
        put(parties, "tenderee", text(project.get("tenderee")));
        put(parties, "tenderAgent", text(project.get("tendereeAgent")));
        if (!text(project.get("isAgent")).isBlank()) {
            put(parties, "entrustedAgency", yes(project.get("isAgent")) ? "是" : "否");
        }
        return parties;
    }

    /** 汇总开标时间、场地和投标方式，供邀请函与投标须知共同使用。 */
    private ObjectNode openingArrangement(Map<String, Object> project,
            List<Map<String, Object>> projectDates) {
        ObjectNode opening = objectMapper.createObjectNode();
        put(opening, "openingTime", formatDate(first(project, projectDates, "openDatetime")));
        put(opening, "openingPlace", text(first(project, projectDates, "bidRoom")));
        put(opening, "biddingMode", text(project.get("biddingMode")));
        return opening;
    }

    /** 根据采购品目中的核心产品标记形成唯一结论，禁止沿用模板历史产品。 */
    private ObjectNode coreProduct(List<Map<String, Object>> items) {
        ObjectNode result = objectMapper.createObjectNode();
        if (items.isEmpty()) {
            return result;
        }
        List<String> names = items.stream().filter(item -> yes(item.get("isCore")))
                .map(item -> text(item.get("itemName"))).filter(name -> !name.isBlank()).toList();
        result.set("productNames", objectMapper.valueToTree(names));
        if (!names.isEmpty()) {
            result.put("hasCoreProduct", true);
            result.put("conclusion", "本项目核心产品为" + String.join("、", names));
        }
        else if (items.stream().anyMatch(item -> !text(item.get("isCore")).isBlank())) {
            result.put("hasCoreProduct", false);
            result.put("conclusion", "项目资料未设置核心产品");
        }
        else {
            result.put("conclusion", "项目资料未明确核心产品");
        }
        return result;
    }

    /** 主表优先；主表为空时从项目时间记录中取第一个有效值。 */
    private Object first(Map<String, Object> project, List<Map<String, Object>> rows, String field) {
        Object value = project.get(field);
        if (!text(value).isBlank()) {
            return value;
        }
        return rows.stream().map(row -> row.get(field)).filter(item -> !text(item).isBlank())
                .findFirst().orElse(null);
    }

    private ObjectNode formattedDates(Map<String, Object> project,
            List<Map<String, Object>> projectDates) {
        ObjectNode dates = objectMapper.createObjectNode();
        LocalDateTime now = LocalDateTime.now();
        dates.put("currentDateTime", DATE_TIME.format(now));
        dates.put("currentDate", DATE.format(now));
        dates.put("currentYearMonth", YEAR_MONTH.format(now));
        Map.of("publishDate", "公告发布时间", "signUpDeadline", "报名截止时间",
                "endDatetime", "投标截止时间", "openDatetime", "开标时间")
                .forEach((field, label) -> {
                    Object raw = first(project, projectDates, field);
                    String value = formatDate(raw);
                    if (!value.isBlank()) {
                        ObjectNode date = dates.putObject(field);
                        date.put("label", label);
                        date.put("value", value);
                        put(date, "dateOnly", formatDateOnly(raw));
                    }
                });
        Object publishDate = first(project, projectDates, "publishDate");
        if (publishDate instanceof LocalDateTime value) {
            dates.put("threeYearsBeforePublish", YEAR_MONTH.format(value.minusYears(3)));
        }
        else if (publishDate instanceof LocalDate value) {
            dates.put("threeYearsBeforePublish", YEAR_MONTH.format(value.minusYears(3)));
        }
        return dates;
    }

    /** 将项目主体中的联系人按职责整理，避免模型混用不同角色。 */
    private ArrayNode projectContacts(Map<String, Object> project) {
        ArrayNode contacts = objectMapper.createArrayNode();
        addContact(contacts, project, "招标联系人", "tendereeLinkman", "tendereePhone", "tendereeMail");
        addContact(contacts, project, "项目经办人", "executorName", "executorPhone", "executorEmail");
        addContact(contacts, project, "项目负责人", "projectLeaderName", "projectLeaderPhone", "projectLeaderMail");
        addContact(contacts, project, "项目审核人", "purchaseApproverName", "purchaseApproverPhone", "purchaseApproverMail");
        addContact(contacts, project, "资格审核人", "qualificationPersonName", "", "");
        return contacts;
    }

    private void addContact(ArrayNode contacts, Map<String, Object> project, String role,
            String nameField, String phoneField, String emailField) {
        String name = text(project.get(nameField));
        String phone = phoneField.isBlank() ? "" : text(project.get(phoneField));
        String email = emailField.isBlank() ? "" : text(project.get(emailField));
        if (name.isBlank() && phone.isBlank() && email.isBlank()) {
            return;
        }
        ObjectNode contact = contacts.addObject();
        contact.put("role", role);
        put(contact, "name", "项目经办人".equals(role) ? teacherName(name) : name);
        put(contact, "phone", phone);
        put(contact, "email", email);
    }

    /** 为已解析的补充资料补充中文业务分类，未知类型仍保留原始内容。 */
    private ArrayNode supplementaryMaterials(List<Map<String, Object>> comments) {
        ArrayNode result = objectMapper.createArrayNode();
        comments.forEach(comment -> {
            ObjectNode material = result.addObject();
            String type = text(comment.get("commentsType"));
            material.put("category", COMMENT_TYPES.getOrDefault(type, "其他补充资料"));
            material.set("content", objectMapper.valueToTree(comment.get("comments")));
        });
        return result;
    }

    /** commentsType=4：提取履约担保是否启用、比例、要求和退还说明。 */
    private ObjectNode performance(JsonNode content) {
        ObjectNode result = objectMapper.createObjectNode();
        if (content == null || !content.isObject()) {
            return result;
        }
        boolean required = "1".equals(content.path("observeLetter").asText());
        result.put("required", required);
        put(result, "amountRate", content.path("observeAmount").asText());
        put(result, "requirement", content.path("observeRemark").asText());
        put(result, "refundDescription", content.path("refundRemark").asText());
        return result;
    }

    /** commentsType=38：提取实质性响应条款，并统一补充 ★ 标记。 */
    private ArrayNode substantiveRequirements(JsonNode content) {
        ArrayNode result = objectMapper.createArrayNode();
        if (content == null || !content.isArray()) {
            return result;
        }
        content.forEach(item -> {
            String requirement = item.path("qualificationMessage").asText().trim();
            if (!requirement.isBlank()) {
                result.add(requirement.startsWith("★") ? requirement : "★" + requirement);
            }
        });
        return result;
    }

    /** 按业务类型编号取得已经解析的补充资料正文。 */
    private JsonNode comment(List<Map<String, Object>> comments, String type) {
        return comments.stream()
                .filter(comment -> type.equals(text(comment.get("commentsType"))))
                .map(comment -> objectMapper.<JsonNode>valueToTree(comment.get("comments")))
                .findFirst().orElse(null);
    }

    /** 将项目保存的一个或多个资金来源 ID 转换为中文名称。 */
    private String fundingSource(Object value, List<Map<String, Object>> capitalSources) {
        if (value == null) {
            return "";
        }
        Map<String, String> names = capitalSources.stream().collect(Collectors.toMap(
                source -> text(source.get("csId")), source -> text(source.get("csName")),
                (left, right) -> left));
        return Arrays.stream(text(value).split(","))
                .map(String::trim).map(id -> names.getOrDefault(id, id))
                .filter(name -> !name.isBlank()).collect(Collectors.joining("、"));
    }

    /** 使用项目已有的 Hutool 依赖生成用于封面和金额条款的人民币大写金额。 */
    private String uppercaseMoney(Object value) {
        try {
            BigDecimal amount = new BigDecimal(text(value));
            return NumberChineseFormatter.format(amount.doubleValue(), true, true);
        }
        catch (NumberFormatException ignored) {
            return "";
        }
    }

    private String percentage(Object value) {
        try {
            return new BigDecimal(text(value)).multiply(BigDecimal.valueOf(100))
                    .stripTrailingZeros().toPlainString() + "%";
        }
        catch (NumberFormatException ignored) {
            return "";
        }
    }

    /** 兼容 MyBatis 常见的日期返回类型；历史文本日期保持原值。 */
    private String formatDate(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return DATE_TIME.format(dateTime);
        }
        if (value instanceof LocalDate date) {
            return DATE.format(date);
        }
        if (value instanceof Timestamp timestamp) {
            return DATE_TIME.format(timestamp.toLocalDateTime());
        }
        return value == null ? "" : text(value);
    }

    private String formatDateOnly(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return DATE.format(dateTime);
        }
        if (value instanceof LocalDate date) {
            return DATE.format(date);
        }
        if (value instanceof Timestamp timestamp) {
            return DATE.format(timestamp.toLocalDateTime());
        }
        return "";
    }

    private String teacherName(String name) {
        return name.isBlank() ? "" : name.substring(0, 1) + "老师";
    }

    /** 评标方法编码：1 为综合评分法，2 为最低评标价法。 */
    private String evaluationMethod(Object value) {
        return switch (text(value)) {
            case "1" -> "综合评分法";
            case "2" -> "最低评标价法";
            default -> "";
        };
    }

    /** 评标方式编码：0 为智能评标，1 为评委打分。 */
    private String evaluationMode(Object value) {
        return switch (text(value)) {
            case "0" -> "智能评标";
            case "1" -> "评委打分";
            default -> "";
        };
    }

    /** 进口产品编码沿用旧版规则：0 为不接受，其余非空值为接受。 */
    private String accepted(Object value) {
        if (value == null || text(value).isBlank()) {
            return "";
        }
        return "0".equals(text(value)) ? "不接受" : "接受";
    }

    private boolean yes(Object value) {
        return switch (text(value).toLowerCase()) {
            case "1", "true", "y", "yes", "是" -> true;
            default -> false;
        };
    }

    /** 空值不进入 documentFacts，避免模型把空字段误写为正文内容。 */
    private void put(ObjectNode target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value);
        }
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }
}
