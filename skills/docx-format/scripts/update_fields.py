#!/usr/bin/env python3
"""在独立 LibreOffice 实例中计算目录与页码；仅由排版入口调用。"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from xml.sax.saxutils import escape


def font_environment(source, directory):
    """仅为本次进程配置缺失中文字体的替代，不更改模板字体声明。"""
    from format_docx import Package, descendants, attr
    package = Package(source)
    families = set()
    for name in package.parts:
        if name.startswith("word/") and name.endswith(".xml"):
            for node in descendants(package.parse(name), "rFonts"):
                for key in ("ascii", "hAnsi", "eastAsia", "cs"):
                    if attr(node, key):
                        families.add(attr(node, key))
    aliases, warnings = [], []
    fontconfig = shutil.which("fc-match")
    if not fontconfig:
        raise RuntimeError("缺少 fc-match，无法验证字体")
    for family in sorted(families):
        response = subprocess.run([fontconfig, "-f", "%{family}", family], capture_output=True, text=True, timeout=5)
        if response.returncode:
            raise RuntimeError("字体检查失败：" + family)
        matches = {value.strip().casefold() for value in response.stdout.split(",")}
        if family.casefold() in matches:
            continue
        chinese = any("\u3400" <= char <= "\u9fff" for char in family) or family.lower() in {
            "simsun", "nsimsun", "simhei", "fangsong", "kaiti", "dengxian", "microsoft yahei"}
        if chinese:
            sans = any(word in family.lower() for word in ("黑", "雅黑", "等线", "simhei", "yahei", "dengxian", "sans"))
            replacement = "Noto Sans CJK SC" if sans else "Noto Serif CJK SC"
            aliases.append("<alias binding='strong'><family>" + escape(family)
                           + "</family><prefer><family>" + replacement + "</family></prefer></alias>")
            warnings.append("云端缺少字体 " + family + "，本轮渲染使用 " + replacement)
        else:
            warnings.append("云端缺少字体 " + family + "，由环境字体替代；需检查分页")
    environment = os.environ.copy()
    if aliases:
        config = Path(directory) / "fonts.conf"
        config.write_text("<?xml version='1.0'?><fontconfig><include ignore_missing='yes'>"
                          "/etc/fonts/fonts.conf</include>" + "".join(aliases) + "</fontconfig>", encoding="utf-8")
        environment["FONTCONFIG_FILE"] = str(config)
    return environment, warnings


def properties(uno, **values):
    result = []
    for name, value in values.items():
        prop = uno.createUnoStruct("com.sun.star.beans.PropertyValue")
        prop.Name, prop.Value = name, value
        result.append(prop)
    return tuple(result)


def add_page_numbers(document):
    """只补缺失的当前页码；不重置页码起始值，不覆盖已有页脚文字。"""
    fields = []
    enumeration = document.getTextFields().createEnumeration()
    while enumeration.hasMoreElements():
        fields.append(enumeration.nextElement())
    styles = document.getStyleFamilies().getByName("PageStyles")
    start = document.getText().createTextCursor()
    first_style = start.getPropertyValue("PageStyleName") if start.getPropertySetInfo().hasPropertyByName("PageStyleName") else ""
    for name in styles.getElementNames():
        style = styles.getByName(name)
        if not style.isInUse():
            continue
        # 首页单独样式且未启用页脚时保留封面；不要为了页码破坏首页设置。
        if name == first_style and style.FollowStyle != name and not style.FooterIsOn:
            continue
        if not style.FooterIsOn:
            style.FooterIsOn = True
        seen = []
        for key in ("FooterText", "FooterTextLeft", "FooterTextRight", "FooterTextFirst"):
            if not style.getPropertySetInfo().hasPropertyByName(key):
                continue
            footer = style.getPropertyValue(key)
            if footer is None or any(footer == value for value in seen):
                continue
            seen.append(footer)
            if any(field.supportsService("com.sun.star.text.TextField.PageNumber")
                   and field.getAnchor().getText() == footer for field in fields):
                continue
            cursor = footer.createTextCursor()
            cursor.gotoEnd(False)
            if footer.getString():
                footer.insertString(cursor, "\n", False)
            field = document.createInstance("com.sun.star.text.TextField.PageNumber")
            # 使用页面自己的编号格式；不能把模板中的罗马数字强行改成阿拉伯数字。
            import uno
            field.SubType = uno.Enum("com.sun.star.text.PageNumberType", "CURRENT")
            field.NumberingType = uno.getConstantByName("com.sun.star.style.NumberingType.PAGE_DESCRIPTOR")
            footer.insertTextContent(cursor, field, False)
            fields.append(field)


def refresh_directory(document, uno):
    indexes = document.getDocumentIndexes()
    directories = [indexes.getByIndex(i) for i in range(indexes.getCount())
                   if indexes.getByIndex(i).supportsService("com.sun.star.text.ContentIndex")]
    if len(directories) > 1:
        raise RuntimeError("存在多个真实目录，保留原文件，不猜测应更新哪一个")
    headings = []
    paragraphs = document.getText().createEnumeration()
    while paragraphs.hasMoreElements():
        node = paragraphs.nextElement()
        if node.supportsService("com.sun.star.text.Paragraph") and 1 <= node.OutlineLevel <= 9:
            headings.append(node)
    if not headings:
        raise RuntimeError("LibreOffice 未识别正文大纲标题")
    if directories:
        directory = directories[0]
    else:
        cursor = document.getText().createTextCursorByRange(headings[0].getStart())
        directory = document.createInstance("com.sun.star.text.ContentIndex")
        directory.Title = "目录"
        directory.CreateFromOutline = True
        directory.CreateFromMarks = False
        directory.Level = 9
        document.getText().insertTextContent(cursor, directory, False)
    page_before = uno.Enum("com.sun.star.style.BreakType", "PAGE_BEFORE")
    first_heading = document.getText().createTextCursorByRange(headings[0].getStart())
    first_heading.BreakType = page_before
    # 目录标题不加入正文大纲，否则目录会将自己作为章节收录。
    anchor = document.getText().createTextCursorByRange(directory.getAnchor().getStart())
    anchor.BreakType = page_before
    anchor.OutlineLevel = 0
    return directory


def run(source, output, timeout):
    import uno
    source, output = Path(source).resolve(), Path(output).resolve()
    if source == output or output.exists():
        raise ValueError("UNO 输出必须是不同且不存在的新文件")
    soffice = shutil.which("soffice")
    if not soffice:
        raise RuntimeError("未安装 LibreOffice")
    deadline = time.monotonic() + timeout
    with tempfile.TemporaryDirectory(prefix="docx-uno-") as directory:
        environment, warnings = font_environment(source, directory)
        profile = (Path(directory) / "profile").as_uri()
        pipe = "docx_format_" + uuid.uuid4().hex
        process = subprocess.Popen([soffice, "--headless", "--nologo", "--nodefault", "--norestore",
                                    "-env:UserInstallation=" + profile,
                                    "--accept=pipe,name=" + pipe + ";urp;StarOffice.ComponentContext"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=environment)
        document = desktop = None
        try:
            local = uno.getComponentContext()
            resolver = local.ServiceManager.createInstanceWithContext("com.sun.star.bridge.UnoUrlResolver", local)
            while True:
                if process.poll() is not None:
                    raise RuntimeError("LibreOffice 在连接 UNO 前退出")
                try:
                    context = resolver.resolve("uno:pipe,name=" + pipe + ";urp;StarOffice.ComponentContext")
                    break
                except Exception:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("连接 LibreOffice UNO 超时")
                    time.sleep(0.2)
            desktop = context.ServiceManager.createInstanceWithContext("com.sun.star.frame.Desktop", context)
            document = desktop.loadComponentFromURL(source.as_uri(), "_blank", 0, properties(
                uno, Hidden=True, ReadOnly=False,
                MacroExecutionMode=uno.getConstantByName("com.sun.star.document.MacroExecMode.NEVER_EXECUTE"),
                UpdateDocMode=uno.getConstantByName("com.sun.star.document.UpdateDocMode.NO_UPDATE")))
            if document is None or not document.supportsService("com.sun.star.text.TextDocument"):
                raise RuntimeError("无法加载 Word 文档")
            directory_index = refresh_directory(document, uno)
            add_page_numbers(document)
            previous = None
            for _ in range(3):
                if time.monotonic() >= deadline:
                    raise TimeoutError("计算目录和页码超时")
                directory_index.update()
                document.getTextFields().refresh()
                document.storeToURL(output.as_uri(), properties(uno, FilterName="Office Open XML Text", Overwrite=True))
                current = directory_index.getAnchor().getString()
                if previous == current and current.strip():
                    return {"status": "success", "warnings": warnings}
                previous = current
            raise RuntimeError("三轮更新后目录页码仍未稳定")
        finally:
            # 仅关闭本次私有 profile 对应的实例，不能影响其他 Agent 会话。
            if document is not None:
                try:
                    document.close(True)
                except Exception:
                    pass
            if desktop is not None:
                try:
                    desktop.terminate()
                except Exception:
                    pass
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input")
    parser.add_argument("output")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.input, args.output, args.timeout), ensure_ascii=False))
    except Exception as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
