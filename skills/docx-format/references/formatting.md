# 排版规则与环境验收

## 环境

已经验证的基线：Debian 12、LibreOffice 7.4.7.2、系统 Python UNO 可用。固定脚本使用 Python 标准库修改 OOXML，避免通过高层文档对象重建而丢失未知元素。无需 Node.js、Java 或额外部署服务。

环境 apt 预装：`libreoffice-writer`、`python3-uno`、`fonts-noto-cjk`、`fontconfig`。已经安装的 python-docx 可保留，但本 Skill 不依赖它。

字体遵循模板优先：安装了的字体保留，未安装的字体通过本次 LibreOffice 独立进程的字体替代配置映射至 Noto。宋体类及未知中文字体默认 Noto Serif CJK SC，黑体类默认 Noto Sans CJK SC；不修改原始文件字体声明。不能保证 Noto 与宋体、黑体具有相同字宽或分页。Latin 字体也会报告缺失，不擅自改成中文字体。

## 两种模式

| 操作 | tender | report |
| --- | --- | --- |
| 正文最浅可靠标题新起一页 | 是 | 否 |
| 目录独立一页、首章与目录分开 | 是 | 是 |
| 标题不斜体 | 是 | 是 |
| 表格宽度和单元格留白 | 是 | 是 |
| 保留或补充页脚 PAGE 域 | 是 | 是 |

不统一 A4、字体、字号、颜色、页边距，不新增页眉，不改变已有页码起始值、编号方式或首页不同设置。

## 标题与目录

标题仅来源于段落大纲级别 0～8、Heading1～Heading9 样式或其继承链；大纲级别 9 表示正文。仅对主文档顶层段落生成目录，表格内、文本框内的标题不作为章节。最浅层级视为大章节，封面标题应使用 Title 样式而非 Heading1。

目录范围仅接受独立的 TOC 内容控件或边界完整的 TOC 域，且其中正文段落必须具有目录样式或明确属于域结果。无法证实安全范围时，不删除、不刷新、不新增目录，返回 partial。无目录且有可靠标题时，在正文首章之前插入目录；不猜测封面边界。

LibreOffice 更新目录、域并保存 DOCX 后，检查目录条目包含目标章节标题和数字或罗马数字页码。不只检查存在域代码。最多更新三轮，直到目录结果稳定；不稳定视为失败。

## 内容保护的范围与限制

检查主文档、脚注、尾注、页眉页脚的正文顺序、表格单元格文字和合并结构、图片内容摘要、节数量与页面几何、超链接目标以及关键对象数量。允许变化：可靠目录区域、PAGE/NUMPAGES 等分页域结果、排版属性和自动生成的目录书签。

含宏、数字签名、修订、嵌入对象、altChunk、图表、文本框、复杂绘图或未支持的正文块时，脚本拒绝 LibreOffice 重保存并保留原文件。这是避免损坏的明确边界，不声称支持所有模板。

对原有脚注尾注、页眉页脚等会校验文字内容，但不是完整 OOXML 等价证明。视觉抽查仍必须执行；Microsoft Word 与 LibreOffice 的字体和分页可能存在差异。

## 返回结果

- success / 退出码 0：排版已完成，通过当前校验。warnings 仍须展示或记录。
- partial / 退出码 2：保留无法安全处理的目录，仅完成不需重保存的格式调整；目录或页码可能未计算。
- fallback / 退出码 2：输出为原文件副本，排版失败或遇到不支持结构。
- error / 退出码 1：输入/输出路径等参数错误，没有交付物。

不得把 partial、fallback 当作完整成功，也不要在 DOCX 内新增错误说明。

## 验收与打包

本地结构测试：`python -B -m unittest discover -s skills/docx-format/tests -v`。

云端完整测试：将配套 `docx-format-validation.zip` 作为测试附件上传并解压，在解压目录执行 `DOCX_FORMAT_INTEGRATION=1 python3 -B -m unittest discover -s tests -v`。集成测试要求 LibreOffice、UNO 和 fontconfig 可用，检查真实页码、重复执行及正文保留。该 ZIP 是测试附件，不作为 Skill 上传。

上传 ZIP 根目录必须直接包含 SKILL.md、scripts、references，不包含 tests、依赖、字体、业务文件或凭证。绑定明确版本；新版本上传后需显式更新 Agent 的 Skill 版本。
