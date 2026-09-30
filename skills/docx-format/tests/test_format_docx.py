"""结构测试不依赖 Office；UNO 集成测试只在明确启用的云端环境运行。"""

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZIP_DEFLATED, ZipFile

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
import format_docx as fmt


def paragraph(value, style=None, outline=None):
    props = ""
    if style:
        props += '<w:pStyle w:val="' + style + '"/>'
    if outline is not None:
        props += '<w:outlineLvl w:val="' + str(outline) + '"/>'
    return '<w:p><w:pPr>' + props + '</w:pPr><w:r><w:t>' + value + '</w:t></w:r></w:p>'


def table(value="参数内容"):
    return ('<w:tbl><w:tblPr><w:tblW w:w="16000" w:type="dxa"/></w:tblPr>'
            '<w:tblGrid><w:gridCol w:w="8000"/><w:gridCol w:w="8000"/></w:tblGrid>'
            '<w:tr><w:tc><w:tcPr><w:tcW w:w="16000" w:type="dxa"/>'
            '<w:gridSpan w:val="2"/></w:tcPr>' + paragraph(value) + '</w:tc></w:tr></w:tbl>')


def directory(titles=("第一章", "第二章", "第三章"), numbers=("2", "3", "4")):
    entries = "".join('<w:p><w:pPr><w:pStyle w:val="TOC1"/></w:pPr><w:r><w:t>' + title
                      + '</w:t><w:tab/><w:t>' + number + '</w:t></w:r></w:p>' for title, number in zip(titles, numbers))
    return ('<w:sdt><w:sdtPr><w:docPartObj><w:docPartGallery w:val="Table of Contents"/></w:docPartObj>'
            '</w:sdtPr><w:sdtContent>' + paragraph("目录", "TOCHeading")
            + '<w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r>'
            '<w:r><w:instrText> TOC \\o "1-9" \\h \\u </w:instrText></w:r>'
            '<w:r><w:fldChar w:fldCharType="separate"/></w:r></w:p>' + entries
            + '<w:p><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p></w:sdtContent></w:sdt>')


def fixture(path, body=None, extra=None, section=None):
    if body is None:
        body = paragraph("测试封面", "Title")
        for title in ("第一章", "第二章", "第三章"):
            body += paragraph(title, "Heading1") + paragraph(title + "正文")
        body += table()
    section = section or '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:bottom="1440" w:left="1440" w:right="1440"/></w:sectPr>'
    styles = ('<w:styles xmlns:w="' + fmt.W + '">'
              '<w:style w:type="paragraph" w:styleId="Normal"><w:name w:val="Normal"/></w:style>'
              '<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/></w:style>'
              '<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/>'
              '<w:basedOn w:val="Normal"/><w:pPr><w:outlineLvl w:val="0"/></w:pPr>'
              '<w:rPr><w:i/><w:iCs/></w:rPr></w:style>'
              '<w:style w:type="paragraph" w:styleId="Custom"><w:basedOn w:val="Heading1"/></w:style>'
              '<w:style w:type="paragraph" w:styleId="Cycle"><w:basedOn w:val="Cycle"/></w:style>'
              '<w:style w:type="paragraph" w:styleId="TOC1"><w:name w:val="toc 1"/></w:style>'
              '<w:style w:type="paragraph" w:styleId="TOCHeading"><w:name w:val="TOC Heading"/></w:style>'
              '</w:styles>')
    types = ('<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
             '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
             '<Default Extension="xml" ContentType="application/xml"/>'
             '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
             '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
             '</Types>')
    parts = {"[Content_Types].xml": types,
             "_rels/.rels": '<Relationships xmlns="' + fmt.REL + '"><Relationship Id="rId1" Type="'
             + fmt.R + '/officeDocument" Target="word/document.xml"/></Relationships>',
             "word/document.xml": '<w:document xmlns:w="' + fmt.W + '" xmlns:r="' + fmt.R
             + '"><w:body>' + body + section + '</w:body></w:document>',
             "word/styles.xml": styles,
             "word/_rels/document.xml.rels": '<Relationships xmlns="' + fmt.REL
             + '"><Relationship Id="rId1" Type="' + fmt.R + '/styles" Target="styles.xml"/></Relationships>'}
    parts.update(extra or {})
    with ZipFile(path, "w", ZIP_DEFLATED) as archive:
        for name, value in parts.items():
            archive.writestr(name, value)


class StructureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.source = Path(self.temporary.name) / "source.docx"
        self.output = Path(self.temporary.name) / "formatted.docx"
        fixture(self.source)

    def test_heading_inheritance_outline_body_and_cycle(self):
        fixture(self.source, paragraph("继承", "Custom") + paragraph("正文", "Heading1", 9)
                + paragraph("循环", "Cycle") + paragraph("第九级", outline=8))
        package = fmt.Package(self.source)
        self.assertEqual([1, 0, 0, 9], [package.level(p) for p in fmt.children(package.body, "p")])

    def test_visual_heading_is_not_guessed(self):
        fixture(self.source, paragraph("第一章 大标题"))
        result = fmt.run(self.source, self.output)
        self.assertEqual("partial", result["status"])
        self.assertFalse(fmt.toc_ranges(fmt.Package(self.output))[0])

    def test_heading_not_italic_and_report_compact(self):
        package = fmt.Package(self.source)
        headings = fmt.format_properties(package, "report")
        for p, level in headings:
            self.assertEqual("0", fmt.attr(fmt.descendants(p, "i")[0], "val"))
            self.assertEqual(str(level - 1), fmt.attr(fmt.first(fmt.first(p, "pPr"), "outlineLvl"), "val"))
            self.assertIsNone(fmt.first(fmt.first(p, "pPr"), "pageBreakBefore"))

    def test_tender_major_heading_page_break(self):
        package = fmt.Package(self.source)
        fmt.format_properties(package, "tender")
        for p in fmt.children(package.body, "p"):
            if package.level(p):
                self.assertEqual("1", fmt.attr(fmt.first(fmt.first(p, "pPr"), "pageBreakBefore"), "val"))

    def test_table_width_margins_and_merged_content_preserved(self):
        package = fmt.Package(self.source)
        before = fmt.snapshot(package)
        fmt.format_properties(package, "tender")
        fmt.verify_content(before, fmt.snapshot(package))
        columns = fmt.descendants(package.body, "gridCol")
        self.assertEqual(9026, sum(int(fmt.attr(c, "w")) for c in columns))
        self.assertEqual("2", fmt.attr(fmt.descendants(package.body, "gridSpan")[0], "val"))
        self.assertEqual("120", fmt.attr(fmt.descendants(package.body, "left")[0], "w"))

    def test_table_section_width_and_nested_table(self):
        body = table().replace(paragraph("参数内容"), paragraph("参数内容") + table("嵌套内容"))
        fixture(self.source, body, section='<w:sectPr><w:pgSz w:w="16000" w:h="11906" w:orient="landscape"/>'
                '<w:pgMar w:left="1000" w:right="1000"/></w:sectPr>')
        package = fmt.Package(self.source)
        fmt.format_properties(package, "report")
        tables = fmt.descendants(package.body, "tbl")
        outer = sum(int(fmt.attr(c, "w")) for c in fmt.children(fmt.first(tables[0], "tblGrid"), "gridCol"))
        inner = sum(int(fmt.attr(c, "w")) for c in fmt.children(fmt.first(tables[1], "tblGrid"), "gridCol"))
        self.assertEqual(14000, outer)
        self.assertLessEqual(inner, outer - 240)

    def test_native_toc_cached_numbers_verified(self):
        fixture(self.source, directory() + paragraph("第一章", "Heading1") + paragraph("正文"))
        package = fmt.Package(self.source)
        self.assertEqual(([(0, 1)], False), fmt.toc_ranges(package))
        self.assertEqual(3, len(fmt.verify_toc(package, ["第一章", "第二章", "第三章"])))

    def test_toc_placeholder_and_missing_heading_rejected(self):
        fixture(self.source, directory(numbers=("更新目录", "", "")))
        with self.assertRaises(ValueError):
            fmt.verify_toc(fmt.Package(self.source), ["第一章"])
        fixture(self.source, directory())
        with self.assertRaises(ValueError):
            fmt.verify_toc(fmt.Package(self.source), ["不存在的章"])

    def test_manual_toc_preserved_without_uno(self):
        fixture(self.source, paragraph("目录") + paragraph("第一章……1") + paragraph("第一章", "Heading1") + paragraph("真实正文"))
        with patch.object(fmt, "execute_uno", side_effect=AssertionError("不能调用 UNO")):
            result = fmt.run(self.source, self.output)
        self.assertEqual("partial", result["status"])
        self.assertIn("真实正文", fmt.text(fmt.Package(self.output).body))
        self.assertIn("第一章……1", fmt.text(fmt.Package(self.output).body))

    def test_toc_content_control_mixed_with_body_not_removed(self):
        fixture(self.source, directory().replace('</w:sdtContent>', paragraph("混入的正文") + '</w:sdtContent>'))
        self.assertTrue(fmt.toc_ranges(fmt.Package(self.source))[1])

    def test_content_deletion_reordering_and_duplication_detected(self):
        before = fmt.snapshot(fmt.Package(self.source))
        for body in (paragraph("第一章", "Heading1"), paragraph("第三章正文") + paragraph("第一章正文"),
                     paragraph("第一章正文") * 2):
            fixture(self.output, body)
            with self.assertRaises(ValueError):
                fmt.verify_content(before, fmt.snapshot(fmt.Package(self.output)))

    def test_media_and_external_links_protected(self):
        links = '<Relationships xmlns="' + fmt.REL + '"><Relationship Id="rId2" Type="'
        links += fmt.R + '/hyperlink" Target="https://example.com" TargetMode="External"/></Relationships>'
        fixture(self.source, extra={"word/media/image.png": b"image", "word/_rels/document.xml.rels": links})
        before = fmt.snapshot(fmt.Package(self.source))
        fixture(self.output, extra={"word/media/image.png": b"changed", "word/_rels/document.xml.rels": links})
        with self.assertRaises(ValueError):
            fmt.verify_content(before, fmt.snapshot(fmt.Package(self.output)))

    def test_missing_uno_original_bytes_returned(self):
        with patch.object(fmt, "execute_uno", side_effect=FileNotFoundError("没有 UNO")):
            result = fmt.run(self.source, self.output)
        self.assertEqual("fallback", result["status"])
        self.assertEqual(self.source.read_bytes(), self.output.read_bytes())

    def test_success_accepts_only_validated_uno_output(self):
        def fake_uno(command, timeout):
            package = fmt.Package(command[2])
            xml = fmt.minidom.parseString('<w:body xmlns:w="' + fmt.W + '">' + directory() + '</w:body>')
            toc = package.document.importNode(fmt.children(xml.documentElement)[0], True)
            package.body.insertBefore(toc, fmt.children(package.body)[1])
            package.save(command[3])
            return subprocess.CompletedProcess(command, 0, '{"warnings": []}', '')
        with patch.object(fmt, "execute_uno", side_effect=fake_uno):
            result = fmt.run(self.source, self.output)
        self.assertEqual("success", result["status"], result)
        self.assertEqual(3, result["heading_count"])

    def test_uno_content_loss_rolls_back(self):
        def fake_uno(command, timeout):
            fixture(Path(command[3]), directory() + paragraph("第一章", "Heading1"))
            return subprocess.CompletedProcess(command, 0, '{"warnings": []}', '')
        with patch.object(fmt, "execute_uno", side_effect=fake_uno):
            self.assertEqual("fallback", fmt.run(self.source, self.output)["status"])
        self.assertEqual(self.source.read_bytes(), self.output.read_bytes())

    def test_external_resource_field_fallback(self):
        fixture(self.source, '<w:p><w:fldSimple w:instr=" INCLUDETEXT external.docx "><w:r><w:t>外部正文</w:t></w:r></w:fldSimple></w:p>')
        self.assertEqual("fallback", fmt.run(self.source, self.output)["status"])

    def test_images_relationships_do_not_allow_external_fetch(self):
        links = '<Relationships xmlns="' + fmt.REL + '"><Relationship Id="rId2" Type="'
        links += fmt.R + '/image" Target="https://example.com/img.png" TargetMode="External"/></Relationships>'
        fixture(self.source, extra={"word/_rels/document.xml.rels": links})
        self.assertIsNotNone(fmt.unsupported(fmt.Package(self.source)))

    def test_uno_timeout_original_bytes_returned(self):
        with patch.object(fmt, "execute_uno", side_effect=subprocess.TimeoutExpired("UNO", 1)):
            self.assertEqual("fallback", fmt.run(self.source, self.output)["status"])
        self.assertEqual(self.source.read_bytes(), self.output.read_bytes())

    def test_unsupported_revision_and_embedded_object_fallback(self):
        for body in ('<w:p><w:ins w:id="1"><w:r><w:t>修订</w:t></w:r></w:ins></w:p>',
                     '<w:p><w:r><w:object/></w:r></w:p>'):
            fixture(self.source, body)
            if self.output.exists():
                self.output.unlink()
            self.assertEqual("fallback", fmt.run(self.source, self.output)["status"])
            self.assertEqual(self.source.read_bytes(), self.output.read_bytes())

    def test_source_or_existing_output_cannot_be_overwritten(self):
        with self.assertRaises(ValueError):
            fmt.run(self.source, self.source)
        self.output.write_bytes("已有文件".encode())
        with self.assertRaises(ValueError):
            fmt.run(self.source, self.output)
        self.assertEqual("已有文件".encode(), self.output.read_bytes())

    def test_properties_idempotent(self):
        package = fmt.Package(self.source)
        fmt.format_properties(package, "tender")
        once = package.document.toxml()
        fmt.format_properties(package, "tender")
        self.assertEqual(once, package.document.toxml())

    def test_saved_package_preserves_namespace_prefixes_and_unknown_parts(self):
        fixture(self.source, extra={"customXml/item1.xml": '<data xmlns="urn:custom">保留</data>'})
        package = fmt.Package(self.source)
        fmt.format_properties(package, "report")
        package.save(self.output)
        updated = fmt.Package(self.output)
        self.assertEqual(package.parts["customXml/item1.xml"], updated.parts["customXml/item1.xml"])
        self.assertIn(b"xmlns:w=", updated.parts["word/document.xml"])

    def test_footer_page_cache_ignored_but_text_preserved(self):
        def footer(number):
            return '<w:ftr xmlns:w="' + fmt.W + '">' + paragraph("原页脚") + '<w:p><w:fldSimple w:instr=" PAGE ">' \
                '<w:r><w:t>' + number + '</w:t></w:r></w:fldSimple></w:p></w:ftr>'
        fixture(self.source, extra={"word/footer1.xml": footer("1")})
        fixture(self.output, extra={"word/footer2.xml": footer("20")})
        fmt.verify_content(fmt.snapshot(fmt.Package(self.source)), fmt.snapshot(fmt.Package(self.output)))

    def test_long_document_no_content_loss(self):
        fixture(self.source, "".join(paragraph("第" + str(i) + "条正文") for i in range(1500)))
        package = fmt.Package(self.source)
        before = fmt.snapshot(package)
        fmt.format_properties(package, "report")
        fmt.verify_content(before, fmt.snapshot(package))
        self.assertEqual(1500, len(before["body"]))


@unittest.skipUnless(os.environ.get("DOCX_FORMAT_INTEGRATION") == "1", "需在已验证 UNO 的云环境显式启用")
class CloudIntegrationTests(unittest.TestCase):
    def test_real_toc_and_repeat_export(self):
        with tempfile.TemporaryDirectory() as directory_path:
            source = Path(directory_path) / "source.docx"
            output = Path(directory_path) / "formatted.docx"
            repeated = Path(directory_path) / "repeated.docx"
            body = "".join(paragraph(title, "Heading1") + paragraph(title + "正文")
                           for title in ("第一章", "第二章", "第三章")) + table()
            fixture(source, body)
            result = fmt.run(source, output)
            self.assertEqual("success", result["status"], result)
            self.assertEqual(["2", "3", "4"], [number for _, number in result["toc_entries"]])
            again = fmt.run(output, repeated)
            self.assertEqual("success", again["status"], again)
            self.assertEqual(result["toc_entries"], again["toc_entries"])


if __name__ == "__main__":
    unittest.main()
