# document-text-extractor API 使用手册

> 坐标：`io.majiaoyu:document-text-extractor:0.1.0`
> 运行要求：JDK ≥ 17；零 Spring、零 Tika 依赖。能力：pdf / doc / docx / txt / md / 图片 → 全文文本，
> 扫描页与混排页自动视觉 OCR（qwen3.5-ocr），印章自动识读，doc/docx 内嵌图自动 OCR。

---

## 1. 接入三步

### ① 引依赖

```xml
<dependency>
    <groupId>io.majiaoyu</groupId>
    <artifactId>document-text-extractor</artifactId>
    <version>0.1.0</version>
</dependency>
```

### ② 注入配置（按宿主类型选做，完整代码见第 3 节）

需要两项：
- OCR 端点配置三项：`baseUrl`（DashScope 原生 API 根，含 `/api/v1`）、`apiKey`、`model`——经
  `QwenOcrClient.setConfigProvider(...)` 注入；
- 提取防护：单文件大小上限 `maxFileBytes`（可选）——经 `DocumentTextExtractor.setMaxFileBytes(...)` 注入。

### ③ 调用

```java
String fullText = DocumentTextExtractor.extract(Path.of("投标文件.pdf"));
```

---

## 2. 门面 API：DocumentTextExtractor

入口类 `io.majiaoyu.docextract.DocumentTextExtractor`，三个静态重载：

```java
// ① 全默认：使用 SDK 内置提示词
public static String extract(Path file) throws Exception

// ② 覆盖"整页 OCR 提示词"（对 扫描页/混排页/图片/doc内嵌图 生效）
public static String extract(Path file, String ocrPrompt) throws Exception

// ③ 再覆盖"印章识读提示词"
public static String extract(Path file, String ocrPrompt, String sealPrompt) throws Exception
```

**远程文档重载**（OSS 等 http/https URL 直接入参，三个重载与上面一一对应）：

```java
// 下载到临时文件 → 按 URL 后缀/Content-Type 识别格式 → 全能力解析 → 自动清理临时文件
public static String extract(String fileUrl) throws Exception
public static String extract(String fileUrl, String ocrPrompt) throws Exception
public static String extract(String fileUrl, String ocrPrompt, String sealPrompt) throws Exception
```

约束：仅 http/https；默认拒绝解析到内网/环回的地址（SSRF 闸门，可经系统属性
`docextract.url.allow-private-network` 关闭以支持内网自建 OSS）；下载量受同一大小上限约束；
URL 需已签名且有效（过期/无权限以下载失败抛出）。

提示词传 `null` 或空白即回落 SDK 默认；txt/md 等文本层直读格式不受提示词影响。

### 2.1 支持格式与路由行为

| 格式 | 处理方式 | OCR 触发条件 | 成本 |
|---|---|---|---|
| pdf | 逐页三分类 | 见下方 PDF 策略 | 按触发页计 |
| doc / docx | POI 文字层直提 | 内嵌图（短边 ≥200px、去重后）逐张 OCR | 每张图一次 |
| txt | 直接读文件 | 无 | 免费 |
| md | commonmark 渲染为纯文本（去标记留结构） | 无 | 免费 |
| jpg/jpeg/png/bmp/tif/tiff | 整张图直传 OCR | 必然 | 一次 |

**PDF 逐页三分类**（与原 Tika AUTO 策略阈值一致）：

| 页类型 | 判定 | 处理 |
|---|---|---|
| 纯文本页 | 文本层充足、无大幅嵌入图、未检出印章 | 文本层直读，**不花 OCR 钱** |
| 稀疏页 | 页文本 <10 字符 或 未映射字符 ≥10 | 300dpi 整页渲染 → OCR |
| 混排页 | 存在 ≥页面面积 15% 的位图嵌入图 | 300dpi 整页渲染 → OCR（图文合并输出，含 Markdown 表格） |
| 含章页（任意类型） | SealLocator 颜色检测命中候选框 | 整页印章识读，结果逐行追加 |

### 2.2 调用示例（全场景）

```java
// ---- 最小用法：任何支持格式，一行出全文 ----
String text = DocumentTextExtractor.extract(Path.of("扫描合同.pdf"));

// ---- 文本层直读（零 OCR 成本）----
String txt = DocumentTextExtractor.extract(Path.of("说明.txt"));
String md  = DocumentTextExtractor.extract(Path.of("需求.md"));      // Markdown 已渲染为纯文本

// ---- ② 覆盖整页 OCR 提示词：抑制 LaTeX、要求表格强化 ----
String text2 = DocumentTextExtractor.extract(
        Path.of("招标文件.pdf"),
        """
        请识别图片中的全部文字，并以 Markdown 返回。
        表格保留为 Markdown 表格；公式、单位一律用普通文本表示，禁止输出 LaTeX。
        不要描述图片，不要输出扫描页码。
        """);

// ---- ③ 印章提示词单独覆盖：强调环形文字逐字辨认（压城市名漂移）----
String text3 = DocumentTextExtractor.extract(
        Path.of("投标文件.pdf"),
        null,                                  // 整页 OCR 提示词保持默认
        """
        图中可能有多枚印章。请逐枚识别印章环形排列的全部文字，每个字可能带旋转角度，
        务必逐字仔细辨认形近字（如扬/苏/杭、专利/律师），按印章阅读顺序输出。
        每枚印章单独一行；不要输出非印章内容。
        """);

// ---- 图片文件直传（字节不落解码内存，由模型端解码）----
String imageText = DocumentTextExtractor.extract(Path.of("盖章页.png"));

// ---- 各格式统一走同一入口 ----
DocumentTextExtractor.extract(Path.of("合同.doc"));    // HWPF 文字层 + 内嵌图 OCR
DocumentTextExtractor.extract(Path.of("合同.docx"));   // XWPF 文字层 + 内嵌图 OCR
DocumentTextExtractor.extract(Path.of("会议纪要.md"));
```

### 2.3 返回值结构约定

页与页之间以空行分隔；两类追加段有固定标记，可按标记切分：

```
（第 1 页正文/表格 Markdown）


（第 2 页正文……）


---- 印章文字（OCR）----
扬州苏中专利事务所（普通合伙）
中华人民共和国国家知识产权局


（第 N 页……）

---- 内嵌图片文字（OCR）----          ← 仅 doc/docx 且有内嵌图时出现
[内嵌图片 1]
测试签章文字
```

按标记定位示例：

```java
String sealSection = null;
int idx = text.indexOf("---- 印章文字（OCR）----");
if (idx >= 0) {
    sealSection = text.substring(idx);
}
```

### 2.4 异常契约

| 异常 | 触发 | 处理建议 |
|---|---|---|
| `IllegalArgumentException`（文件不存在） | 路径无效 | 调用方校验 |
| `IllegalArgumentException`（不支持的格式） | 扩展名不在支持清单 | 报错信息自带支持格式清单 |
| `IllegalArgumentException`（超过大小上限） | 文件 > 当前上限 | 提示用户或调上限 |
| `IllegalStateException`（OCR 调用失败/未返回） | 稀疏页 OCR 失败、密钥缺失、端点异常 | 重试或告警；**稀疏页无兜底会抛出** |
| （不抛出，自动降级） | 混排页 OCR 失败 → 回退文本层；office 单张内嵌图失败 → 跳过该图；印章识读失败 → 跳过该段 | 已记 WARN 日志 |

---

## 3. 配置注入（Spring Boot 宿主与纯 Java 宿主，代码可整体复制）

SDK 零 Spring：一切配置经两个注入口进入——
`QwenOcrClient.setConfigProvider(OcrConfigProvider)`（baseUrl/apiKey/model）+
`DocumentTextExtractor.setMaxFileBytes(long)`（单文件上限，≤0 不限制）。
未注入时 SDK 回退读系统属性/环境变量（键名见 3.3 方式 1）。

### 3.1 Spring Boot 宿主：yml + 一个配置类（完整两段，可直接复制）

**① yml：**

```yml
docextract:
  base-url: ${DASHSCOPE_OCR_BASE_URL:https://dashscope.aliyuncs.com/api/v1}
  api-key: ${DASHSCOPE_API_KEY:}                # 也可直接写死或引自己的密钥配置
  model: ${DASHSCOPE_OCR_MODEL:qwen3.5-ocr}
  max-file-bytes: ${DOCEXTRACT_MAX_FILE_BYTES:104857600}
```

**② 配置类（实现 SDK 的 SPI 并承担注入，直接可编译）：**

```java
package com.yourcompany.config;

import io.majiaoyu.docextract.DocumentTextExtractor;
import io.majiaoyu.docextract.OcrConfigProvider;
import io.majiaoyu.docextract.QwenOcrClient;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * docextract-sdk 的宿主配置与注入点合一（yml 的 docextract.* 段）：
 * baseUrl/apiKey/model 实现 SDK 的 OcrConfigProvider，maxFileBytes 为提取防护上限。
 */
@Component
@ConfigurationProperties(prefix = "docextract")
public class DocextractProperties implements OcrConfigProvider {

    private String baseUrl;
    private String apiKey;
    private String model;
    /** 单文件大小上限（字节），≤0 表示不限制。 */
    private long maxFileBytes = 100L * 1024 * 1024;

    @PostConstruct
    public void install() {
        List<String> missing = new ArrayList<>();
        if (isBlank(baseUrl)) {
            missing.add("docextract.base-url");
        }
        if (isBlank(apiKey)) {
            missing.add("docextract.api-key");
        }
        if (isBlank(model)) {
            missing.add("docextract.model");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("docextract-sdk 缺少必要配置 " + missing
                    + "（模型可能下架、端点可能变更，须在 yml 显式声明）");
        }
        QwenOcrClient.setConfigProvider(this);
        DocumentTextExtractor.setMaxFileBytes(maxFileBytes);
    }

    @Override public String baseUrl() { return baseUrl; }
    @Override public String apiKey()  { return apiKey; }
    @Override public String model()   { return model; }

    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public void setApiKey(String apiKey)   { this.apiKey = apiKey; }
    public void setModel(String model)     { this.model = model; }
    public long getMaxFileBytes()          { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
}
```

### 3.2 纯 Java 宿主（无 Spring）

**方式 1：零代码——只设环境变量或系统属性：**

| 键 | 必须性 | 含义 |
|---|---|---|
| `DASHSCOPE_OCR_BASE_URL` | **必填** | DashScope 原生 API 根（含 `/api/v1`） |
| `DASHSCOPE_API_KEY` | **必填** | 密钥，缺失在首次 OCR 时抛 `IllegalStateException` |
| `DASHSCOPE_OCR_MODEL` | **必填** | 视觉 OCR 模型 |

```java
// 启动早期设置（也可用真实环境变量）
System.setProperty("DASHSCOPE_API_KEY", "sk-********");

String text = DocumentTextExtractor.extract(Path.of("扫描件.pdf"));
```

**方式 2：自管配置源（密钥在自家保险库时）：**

```java
QwenOcrClient.setConfigProvider(new OcrConfigProvider() {
    @Override public String baseUrl() { return "https://dashscope.aliyuncs.com/api/v1"; }
    @Override public String apiKey()  { return keyVault.get("dashscope-key"); }
    @Override public String model()   { return "qwen3.5-ocr"; }
});
DocumentTextExtractor.setMaxFileBytes(200L * 1024 * 1024);   // 可选：调大文件上限
```

### 3.3 运行时动态换源（两种宿主通用）

两个注入口都是普通静态方法，可随时重复调用，下一次 `recognize/extract` 即生效：

```java
QwenOcrClient.setConfigProvider(anotherProvider);        // 换端点/密钥/模型
DocumentTextExtractor.setMaxFileBytes(50L * 1024 * 1024); // 收紧单文件上限
```

注意：OCR 客户端实例按 baseUrl 缓存复用（连接池不泄漏），换 baseUrl 会新建实例；
apiKey/model 走每次请求参数，换值无需重建客户端。

### 3.4 注入要点提醒

1. `baseUrl` 必须是 **DashScope 原生 API 根且含 `/api/v1`**（SDK 在其后拼
   `/services/aigc/multimodal-generation/generation`）——裸主站或 compatible-mode 地址都会 404；
2. `baseUrl` / `apiKey` / `model` **三项均必填，SDK 不设隐式默认**——模型可能下架、端点可能
   变更，缺失时在启动装配或首次 OCR 时快速失败并提示缺失键名；
3. `maxFileBytes ≤ 0` 表示关闭大小校验。

---

## 4. 低层 API：QwenOcrClient（单图 OCR）

只需要"一张图 → 文字"时可直接用底层客户端（门面内部也走它）：

```java
// 识别单张图片；imageFormat 为数据 URI 的 MIME 子类型
String markdown = QwenOcrClient.recognize(
        Files.readAllBytes(Path.of("页面.png")), "png",
        "请识别图片中的全部文字，以 Markdown 返回，表格保留为表格。");
```

- 请求：`MultiModalConversation`（DashScope 官方 SDK），HTTP/1.1 传输（DashScope 对 HTTP/2
  大请求体会重置流），maxTokens 16384，读超时 5 分钟；
- 默认提示词常量：`QwenOcrClient.DEFAULT_OCR_PROMPT`；
- 印章识读默认提示词在 `PdfTextExtractor.SEAL_READ_PROMPT`（包内私有，宿主覆盖走门面入参）。

---

## 5. 防护上限（面向不可信上传文档）

| 上限 | 配置来源 | 默认 | 0 值语义 |
|---|---|---|---|
| 单文件大小 | yml `docextract.extract.max-file-bytes` / `setMaxFileBytes` | 100MB | 不限制 |
| PDF 渲染像素面积（按 MediaBox×DPI² 换算，超限自动降 DPI） | `docextract.render.max-pixels` | 1600 万 | 不限制 |
| doc/docx 内嵌图解码前像素预检（只读 header，不分配解码内存） | `docextract.decode.max-pixels` | 6400 万 | 不限制 |
| 审计图落盘开关 | `docextract.seal.audit.enabled` | true | — |
| 审计图保留天数（借写盘时机惰性清理） | `docextract.seal.audit.retention-days` | 7 | 永不清理 |

- 渲染像素超限先按比例降 DPI；降无可降（畸形超大页）抛 `IllegalArgumentException` 拒绝。
- 审计裁剪图落盘 `%TEMP%/seal-audit/<净化后的文档名>/`（文件名已做路径穿越与 Windows
  设备名净化），目录名净化规则：仅保留字母/数字/点/横线/下划线，超长截 80。

---

## 6. 已知边界（如实）

1. **章文识别精度**：官方章（国知局、协会、税务专用章）可靠；公司环形章文可能漂移
   （同一枚章读出扬州/苏州/上海/郑州），审计图 `%TEMP%/seal-audit/` 供人工核对——
   章名不建议直接进自动一致性比对；
   按"宁可少做也别做错"原则，章文中的防伪码长数字（≥8 位）与括号序号默认剥离，
   纯数字行整行丢弃——防伪码在整页渲染下不可靠，输出错误比缺失更有害；
2. **文本层页的表格**无结构（裸文本流）；只有 OCR 页的表格是 Markdown 表格；
   需要文本层表格结构化见挂账项"文本层表格页 OCR"；
3. **JDK 8 的 ImageIO 不解 TIFF**：doc/docx 内嵌 TIFF 跳过（独立图片文件不受影响，
   字节直传由模型端解码）；JDK 21 无此问题；
4. **多栏嵌套扫描表格**（长文本跨列）行结构可能打散，OCR 类模型固有边界；
5. doc/docx 页眉页脚中的图片不处理；WMF/EMF 矢量图跳过。
