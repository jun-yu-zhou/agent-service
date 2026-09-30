#!/usr/bin/env python3
"""保守修改 DOCX 的排版属性；只有目录和内容校验通过才接受 UNO 输出。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
from xml.dom import minidom
from zipfile import ZIP_DEFLATED, ZipFile

W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
REL = "http://schemas.openxmlformats.org/package/2006/relationships"
NS = {"w": W, "r": R}
DIRECTORY_LABELS = {"目录", "目錄", "Table of Contents", "Contents"}


def children(node, name=None):
    if node is None:
        return []
    return [n for n in node.childNodes if n.nodeType == n.ELEMENT_NODE
            and (name is None or (n.namespaceURI == W and n.localName == name))]


def descendants(node, name):
    return list(node.getElementsByTagNameNS(W, name)) if node is not None else []


def first(node, name):
    return next(iter(children(node, name)), None)


def attr(node, name, default=""):
    return node.getAttributeNS(W, name) if node is not None and node.hasAttributeNS(W, name) else default


def text(node):
    return "".join(n.firstChild.data if n.firstChild else "" for n in descendants(node, "t"))


def make(parent, name, **values):
    node = parent.ownerDocument.createElementNS(W, "w:" + name)
    for key, value in values.items():
        node.setAttributeNS(W, "w:" + key, str(value))
    parent.appendChild(node)
    return node


def ensure(parent, name, leading=False):
    node = first(parent, name)
    if node is None:
        node = make(parent, name)
        if leading and parent.firstChild:
            parent.insertBefore(node, parent.firstChild)
    return node


def set_value(parent, name, value):
    ensure(parent, name).setAttributeNS(W, "w:val", str(value))


class Package:
    """保留所有 ZIP 部件，只重新序列化确实修改过的 XML。"""

    def __init__(self, path):
        with ZipFile(path) as archive:
            infos = archive.infolist()
            if len(infos) > 4000 or sum(i.file_size for i in infos) > 200 * 1024 * 1024:
                raise ValueError("DOCX 部件过多或解压后超过 200 MiB")
            if len({i.filename for i in infos}) != len(infos):
                raise ValueError("DOCX 包含重复部件")
            self.parts = {i.filename: archive.read(i) for i in infos}
        self.xml = {}
        self.document = self.parse("word/document.xml")
        self.body = self.document.getElementsByTagNameNS(W, "body")[0]
        self.styles = {}
        if "word/styles.xml" in self.parts:
            self.styles = {attr(s, "styleId"): s for s in descendants(self.parse("word/styles.xml"), "style")}

    def parse(self, name):
        if name not in self.xml:
            data = self.parts[name]
            if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
                raise ValueError("不允许含 DTD 或实体声明的 XML")
            self.xml[name] = minidom.parseString(data)
        return self.xml[name]

    def save(self, path):
        with ZipFile(path, "w", ZIP_DEFLATED) as archive:
            for name, data in self.parts.items():
                archive.writestr(name, self.xml[name].toxml(encoding="UTF-8") if name in self.xml else data)

    def level(self, paragraph):
        """大纲级别优先，9 是正文；样式继承使用 visited 避免循环。"""
        props = first(paragraph, "pPr")
        outline = first(props, "outlineLvl") if props else None
        if outline is not None:
            value = int(attr(outline, "val", "9"))
            return value + 1 if 0 <= value < 9 else 0
        style_id = attr(first(props, "pStyle"), "val") if props else ""
        visited = set()
        while style_id and style_id not in visited:
            visited.add(style_id)
            style = self.styles.get(style_id)
            if style is not None:
                outline = next(iter(descendants(style, "outlineLvl")), None)
                if outline is not None:
                    value = int(attr(outline, "val", "9"))
                    return value + 1 if 0 <= value < 9 else 0
            normalized = style_id.lower().replace(" ", "")
            if normalized.startswith("heading") and normalized[7:] in [str(i) for i in range(1, 10)]:
                return int(normalized[7:])
            style_id = attr(first(style, "basedOn"), "val") if style is not None else ""
        return 0


def is_toc_instruction(value):
    return value.strip().upper().split()[:1] == ["TOC"]


def field_instruction(node):
    return "".join(n.firstChild.data if n.firstChild else "" for n in descendants(node, "instrText"))


def toc_style(paragraph):
    props = first(paragraph, "pPr")
    value = attr(first(props, "pStyle"), "val").lower().replace(" ", "") if props else ""
    return any(value.startswith(prefix) and (value[len(prefix):] in [str(i) for i in range(1, 10)]
               or value[len(prefix):] == "heading") for prefix in ("toc", "contents")) or value == "indexheading"


def toc_ranges(package):
    """仅排除完整、独立的目录域；手写目录和混入正文的控件均保留。"""
    blocks = children(package.body)
    ranges, suspicious = [], False
    start, depth, instruction, separated = None, 0, "", False
    for index, block in enumerate(blocks):
        if block.localName == "sdt":
            instr = field_instruction(block)
            simple = descendants(block, "fldSimple")
            has_toc = is_toc_instruction(instr) or any(is_toc_instruction(attr(n, "instr")) for n in simple)
            if has_toc:
                paragraphs = descendants(block, "p")
                allowed = not descendants(block, "tbl") and all(
                    not text(p) or toc_style(p) or text(p).strip() in DIRECTORY_LABELS for p in paragraphs)
                if allowed:
                    ranges.append((index, index + 1))
                else:
                    suspicious = True
                continue
        if block.localName != "p":
            if start is not None:
                suspicious = True
            continue
        simple = descendants(block, "fldSimple")
        if any(is_toc_instruction(attr(n, "instr")) for n in simple):
            if toc_style(block):
                ranges.append((index, index + 1))
            else:
                suspicious = True
            continue
        for node in block.getElementsByTagNameNS(W, "*"):
            if node.localName == "fldChar":
                kind = attr(node, "fldCharType")
                if kind == "begin":
                    if depth == 0:
                        start, instruction, separated = index, "", False
                    depth += 1
                elif kind == "separate" and depth == 1:
                    separated = True
                elif kind == "end" and depth:
                    depth -= 1
                    if depth == 0:
                        if is_toc_instruction(instruction):
                            region = blocks[start:index + 1]
                            if all(b.localName == "p" and (toc_style(b) or not text(b)
                                   or text(b).strip() in DIRECTORY_LABELS) for b in region):
                                begin = start - 1 if start and text(blocks[start - 1]).strip() in DIRECTORY_LABELS else start
                                ranges.append((begin, index + 1))
                            else:
                                suspicious = True
                        start = None
            elif node.localName == "instrText" and depth == 1 and not separated:
                instruction += node.firstChild.data if node.firstChild else ""
    if depth and is_toc_instruction(instruction):
        suspicious = True
    excluded = {i for begin, end in ranges for i in range(begin, end)}
    for index, block in enumerate(blocks):
        if index not in excluded and (text(block).strip() in DIRECTORY_LABELS
                                     or (block.localName == "p" and toc_style(block))):
            suspicious = True
    return ranges, suspicious


def unsupported(package):
    """不支持的对象不能靠文字校验保证安全，直接交还原文件。"""
    risky_parts = ("word/vbaProject", "_xmlsignatures/", "word/embeddings/", "word/charts/")
    if any(name.startswith(risky_parts) for name in package.parts):
        return "文档包含宏、数字签名、嵌入对象或图表"
    for name in package.parts:
        if not name.startswith("word/") or not name.endswith(".xml"):
            continue
        document = package.parse(name)
        risky = ("ins", "del", "moveFrom", "moveTo", "pPrChange", "rPrChange", "tblPrChange",
                 "sectPrChange", "tcPrChange", "trPrChange", "numberingChange", "altChunk", "txbxContent", "object", "pict")
        if any(descendants(document, tag) for tag in risky):
            return "文档包含修订、文本框、旧式绘图或未支持的嵌入内容"
        dangerous = {"INCLUDETEXT", "INCLUDEPICTURE", "LINK", "DDE", "DDEAUTO", "DATABASE"}
        instructions = [field_instruction(p) for p in descendants(document, "p")]
        instructions.extend(attr(n, "instr") for n in descendants(document, "fldSimple"))
        if any(value.strip().upper().split()[:1] and value.strip().upper().split()[0] in dangerous for value in instructions):
            return "文档含可能读取外部资源的域"
        for node in descendants(document, "drawing"):
            # 仅放行图片；SmartArt、组合图形等的正文完整性无法可靠验证。
            uris = [n.getAttribute("uri") for n in node.getElementsByTagNameNS("*", "graphicData")]
            if not uris or any(uri != "http://schemas.openxmlformats.org/drawingml/2006/picture" for uri in uris):
                return "文档包含未支持的复杂绘图"
    allowed = {"p", "tbl", "sdt", "sectPr", "bookmarkStart", "bookmarkEnd", "proofErr"}
    if any(b.localName not in allowed or b.namespaceURI != W for b in children(package.body)):
        return "正文包含未支持的顶层块"
    for name in package.parts:
        if name.endswith(".rels"):
            for link in package.parse(name).getElementsByTagNameNS(REL, "Relationship"):
                if link.getAttribute("TargetMode") == "External" and not link.getAttribute("Type").endswith("/hyperlink"):
                    return "文档包含外部图片、模板或其他外部资源关系"
    return None


def paragraph_content(paragraph):
    """合并碎片 run，但保留换行、制表符；分页域的缓存值允许重新计算。"""
    result, stack = [], []
    for node in paragraph.getElementsByTagNameNS(W, "*"):
        if node.localName == "fldChar":
            kind = attr(node, "fldCharType")
            if kind == "begin":
                stack.append("")
            elif kind == "end" and stack:
                stack.pop()
        elif node.localName == "instrText" and stack:
            stack[-1] += node.firstChild.data if node.firstChild else ""
        elif node.localName in {"t", "tab", "br", "cr", "noBreakHyphen", "softHyphen", "sym"}:
            instructions = list(stack)
            parent = node.parentNode
            while parent is not None and parent != paragraph:
                if parent.nodeType == parent.ELEMENT_NODE and parent.localName == "fldSimple":
                    instructions.append(attr(parent, "instr"))
                parent = parent.parentNode
            if any(i.strip().upper().split()[:1] in [["PAGE"], ["NUMPAGES"], ["SECTIONPAGES"]] for i in instructions):
                continue
            if node.localName == "t":
                result.append(node.firstChild.data if node.firstChild else "")
            elif node.localName == "tab":
                result.append("\t")
            elif node.localName in {"br", "cr"}:
                if attr(node, "type") not in {"page", "column"}:
                    result.append("\n")
            elif node.localName == "sym":
                result.append("[symbol:" + attr(node, "font") + ":" + attr(node, "char") + "]")
            else:
                result.append("\u2011" if node.localName == "noBreakHyphen" else "\u00ad")
    return "".join(result)


def content_signature(node):
    """按文档顺序比较段落和单元格，不用总字数掩盖移位、缺失或重复。"""
    result = []
    for child in children(node):
        if child.localName == "p":
            value = paragraph_content(child)
            if value:
                result.append(("p", value))
        elif child.localName == "tbl":
            rows = []
            for row in children(child, "tr"):
                cells = []
                for cell in children(row, "tc"):
                    props = first(cell, "tcPr")
                    span = attr(first(props, "gridSpan"), "val", "1") if props else "1"
                    merge = first(props, "vMerge") if props else None
                    cells.append((span, attr(merge, "val", "continue") if merge is not None else "",
                                  content_signature(cell)))
                rows.append(cells)
            result.append(("table", rows))
        elif child.localName in {"sdt", "sdtContent"}:
            result.extend(content_signature(child))
    return result


def snapshot(package):
    ranges, suspicious = toc_ranges(package)
    if suspicious:
        raise ValueError("目录范围不明确，不能执行整份文档重保存")
    excluded = {i for begin, end in ranges for i in range(begin, end)}
    holder = package.document.createElementNS(W, "w:body")
    for index, node in enumerate(children(package.body)):
        if index not in excluded:
            holder.appendChild(node.cloneNode(True))
    auxiliary = []
    for name in package.parts:
        if name.startswith(("word/header", "word/footer", "word/footnotes", "word/endnotes")) and name.endswith(".xml"):
            signature = content_signature(package.parse(name).documentElement)
            if signature:
                auxiliary.append(signature)
    media = sorted(hashlib.sha256(value).hexdigest() for name, value in package.parts.items() if name.startswith("word/media/"))
    targets = []
    for name in package.parts:
        if name.endswith(".rels"):
            for link in package.parse(name).getElementsByTagNameNS(REL, "Relationship"):
                if link.getAttribute("TargetMode") == "External":
                    targets.append((link.getAttribute("Type"), link.getAttribute("Target")))
    geometry = []
    for section in descendants(package.document, "sectPr"):
        values = {}
        for tag in ("pgSz", "pgMar", "pgNumType", "cols", "type", "titlePg"):
            node = first(section, tag)
            if node is not None:
                values[tag] = {a.localName: a.value for a in node.attributes.values() if a.namespaceURI == W}
        geometry.append(values)
    objects = {tag: len(descendants(package.document, tag)) for tag in ("drawing", "footnoteReference", "endnoteReference")}
    bookmarks = sorted(attr(n, "name") for n in descendants(holder, "bookmarkStart")
                       if not attr(n, "name").startswith("_Toc"))
    return {"body": content_signature(holder), "auxiliary": sorted(auxiliary, key=repr), "media": media,
            "targets": sorted(targets), "geometry": geometry, "objects": objects, "bookmarks": bookmarks}


def verify_content(before, after):
    for key in ("body", "auxiliary", "media", "targets", "objects", "bookmarks"):
        if before[key] != after[key]:
            raise ValueError("排版前后内容或结构不一致：" + key)
    if len(before["geometry"]) != len(after["geometry"]):
        raise ValueError("排版前后节数量发生变化")
    for old, new in zip(before["geometry"], after["geometry"]):
        for tag, values in old.items():
            if any(new.get(tag, {}).get(key) != value for key, value in values.items()):
                raise ValueError("排版前后页面或分节设置发生变化：" + tag)


def available_width(section):
    size, margin = first(section, "pgSz"), first(section, "pgMar")
    return max(720, int(attr(size, "w", "11906")) - int(attr(margin, "left", "1440"))
               - int(attr(margin, "right", "1440")) - int(attr(margin, "gutter", "0")))


def format_table(table, available):
    props = ensure(table, "tblPr", leading=True)
    indent = first(props, "tblInd")
    available = max(720, available - max(0, int(attr(indent, "w", "0"))))
    grid = first(table, "tblGrid")
    widths = children(grid, "gridCol") if grid else []
    old = [int(attr(c, "w", "0")) for c in widths]
    total = sum(old)
    scale = min(1, available / total) if total else 1
    if scale < 1:
        scaled = [max(1, round(value * scale)) for value in old]
        scaled[-1] += available - sum(scaled)
        for column, value in zip(widths, scaled):
            column.setAttributeNS(W, "w:w", str(value))
    table_width = first(props, "tblW")
    if table_width is not None and attr(table_width, "type") == "dxa" and int(attr(table_width, "w", "0")) > available:
        table_width.setAttributeNS(W, "w:w", str(available))
    for row in children(table, "tr"):
        for cell in children(row, "tc"):
            cell_props = ensure(cell, "tcPr", leading=True)
            cell_width = first(cell_props, "tcW")
            width = available
            if cell_width is not None and attr(cell_width, "type") == "dxa":
                width = max(1, round(int(attr(cell_width, "w", str(available))) * scale))
                if scale < 1:
                    cell_width.setAttributeNS(W, "w:w", str(width))
            margins = ensure(cell_props, "tcMar")
            for side, minimum in (("top", 100), ("bottom", 100), ("left", 120), ("right", 120)):
                # start/end 是新版左右边距；已有合法值不缩小，也不以字号代替单元格空间。
                equivalent = first(margins, "start" if side == "left" else "end") if side in {"left", "right"} else None
                node = equivalent if equivalent is not None else ensure(margins, side)
                if attr(node, "type", "dxa") == "dxa":
                    node.setAttributeNS(W, "w:type", "dxa")
                    node.setAttributeNS(W, "w:w", str(max(minimum, int(attr(node, "w", "0")))))
            left = first(margins, "start") or first(margins, "left")
            right = first(margins, "end") or first(margins, "right")
            nested_width = max(360, width - int(attr(left, "w", "120")) - int(attr(right, "w", "120")))
            for nested in children(cell, "tbl"):
                format_table(nested, nested_width)


def format_properties(package, kind):
    headings = [(p, package.level(p)) for p in children(package.body, "p") if package.level(p)]
    ranges, _ = toc_ranges(package)
    excluded = {i for begin, end in ranges for i in range(begin, end)}
    headings = [(p, level) for p, level in headings if children(package.body).index(p) not in excluded]
    shallow = min((level for _, level in headings), default=0)
    for paragraph, level in headings:
        props = ensure(paragraph, "pPr", leading=True)
        set_value(props, "outlineLvl", level - 1)
        for run in descendants(paragraph, "r"):
            rprops = ensure(run, "rPr", leading=True)
            set_value(rprops, "i", 0)
            set_value(rprops, "iCs", 0)
        if kind == "tender" and level == shallow:
            set_value(props, "pageBreakBefore", 1)
    # 从各块后面的 sectPr 确定它所属的节，兼容横向页、分栏和不同页边距。
    blocks = children(package.body)
    section = next(iter(reversed(descendants(package.document, "sectPr"))), None)
    for block in reversed(blocks):
        found = descendants(block, "sectPr") if block.localName != "sectPr" else [block]
        if found:
            section = found[-1]
        width = available_width(section) if section is not None else 9026
        if section is not None:
            cols = first(section, "cols")
            if cols is not None and int(attr(cols, "num", "1")) > 1:
                width = (width - (int(attr(cols, "num")) - 1) * int(attr(cols, "space", "720"))) // int(attr(cols, "num"))
        if block.localName == "tbl":
            format_table(block, width)
    return headings


def verify_toc(package, expected):
    ranges, suspicious = toc_ranges(package)
    if suspicious or not ranges:
        raise ValueError("保存后没有边界明确的真实目录")
    blocks = children(package.body)
    entries = []
    for begin, end in ranges:
        for block in blocks[begin:end]:
            paragraphs = [block] if block.localName == "p" else descendants(block, "p")
            for paragraph in paragraphs:
                value = paragraph_content(paragraph)
                if "\t" not in value:
                    continue
                title, number = value.rsplit("\t", 1)
                number = number.strip()
                if number and (number.isdecimal() or all(c in "IVXLCDMivxlcdm" for c in number)):
                    entries.append((title.strip(), number))
    # 自动编号可能出现在目录标题前面，按顺序匹配正文标题，不伪造缺失项。
    cursor = 0
    for title in expected:
        while cursor < len(entries) and not entries[cursor][0].endswith(title.strip()):
            cursor += 1
        if cursor == len(entries):
            raise ValueError("目录缺少章节或实际页码：" + title)
        cursor += 1
    return entries


def execute_uno(command, timeout):
    """超时关闭整个私有进程组，避免杀掉 Python 后留下沙箱 soffice。"""
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, start_new_session=os.name == "posix")
    try:
        stdout, stderr = process.communicate(timeout=timeout)
        return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
    except subprocess.TimeoutExpired:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
        process.communicate()
        raise


def run(source, output, kind="tender", timeout=180, uno_python="/usr/bin/python3"):
    source, output = Path(source).resolve(), Path(output).resolve()
    if source == output or output.exists():
        raise ValueError("输出必须是不同且不存在的新文件，禁止覆盖")
    if not source.is_file() or source.suffix.lower() != ".docx":
        raise ValueError("输入必须是已存在的 DOCX 文件")
    if not output.parent.is_dir():
        raise ValueError("输出父目录不存在")
    warnings = []
    try:
        package = Package(source)
        reason = unsupported(package)
        if reason:
            raise ValueError(reason)
        ranges, suspicious = toc_ranges(package)
        if suspicious:
            # 不经过 LibreOffice，避免它自行刷新边界不明的目录。
            format_properties(package, kind)
            package.save(output)
            return {"status": "partial", "output": str(output), "warnings": ["疑似手写或边界不明目录已保留，未生成真实目录或刷新页码"]}
        before = snapshot(package)
        headings = format_properties(package, kind)
        expected = [text(p) for p, _ in headings if text(p).strip()]
        if not expected:
            package.save(output)
            return {"status": "partial", "output": str(output), "warnings": ["没有可靠正文标题，仅修改表格；未生成目录或刷新页码"]}
        with tempfile.TemporaryDirectory(prefix="docx-format-") as directory:
            prepared = Path(directory) / "prepared.docx"
            candidate = Path(directory) / "candidate.docx"
            package.save(prepared)
            command = [uno_python, str(Path(__file__).with_name("update_fields.py")),
                       str(prepared), str(candidate), "--timeout", str(timeout)]
            response = execute_uno(command, timeout + 10)
            if response.returncode:
                raise RuntimeError((response.stderr or response.stdout or "UNO 执行失败").strip()[-2000:])
            details = json.loads(response.stdout)
            warnings.extend(details.get("warnings", []))
            formatted = Package(candidate)
            verify_content(before, snapshot(formatted))
            remaining_headings = [text(p) for p in children(formatted.body, "p") if formatted.level(p) and text(p).strip()]
            if remaining_headings != expected:
                raise ValueError("排版后正文标题的大纲语义或顺序发生变化")
            entries = verify_toc(formatted, expected)
            shutil.copyfile(candidate, output)
        return {"status": "success", "output": str(output), "heading_count": len(expected),
                "toc_entries": entries, "warnings": warnings}
    except Exception as error:
        shutil.copyfile(source, output)
        return {"status": "fallback", "output": str(output), "warnings": warnings + [str(error)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--kind", choices=("tender", "report"), default="tender")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--uno-python", default="/usr/bin/python3")
    args = parser.parse_args()
    try:
        if args.timeout < 1:
            raise ValueError("timeout 必须大于 0")
        result = run(args.input, args.output, args.kind, args.timeout, args.uno_python)
    except Exception as error:
        result = {"status": "error", "warnings": [str(error)]}
    print(json.dumps(result, ensure_ascii=False))
    return {"success": 0, "partial": 2, "fallback": 2, "error": 1}[result["status"]]


if __name__ == "__main__":
    sys.exit(main())
