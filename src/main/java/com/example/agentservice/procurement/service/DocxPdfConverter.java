package com.example.agentservice.procurement.service;

import org.docx4j.Docx4J;
import org.docx4j.fonts.IdentityPlusMapper;
import org.docx4j.fonts.PhysicalFont;
import org.docx4j.fonts.PhysicalFonts;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 使用 docx4j 的纯 Java XSL-FO 导出器将 DOCX 转换为 PDF。 */
@Service
public class DocxPdfConverter {

    public void convert(Path docx, Path pdf) throws Exception {
        if (docx == null || !Files.isRegularFile(docx)) {
            throw new IllegalArgumentException("DOCX 文件不存在");
        }
        if (pdf == null) {
            throw new IllegalArgumentException("PDF 输出路径不能为空");
        }
        Path parent = pdf.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        WordprocessingMLPackage document = WordprocessingMLPackage.load(docx.toFile());
        PhysicalFont simSun = PhysicalFonts.get("SimSun");
        if (simSun != null) {
            IdentityPlusMapper fontMapper = new IdentityPlusMapper();
            fontMapper.put("宋体", simSun);
            fontMapper.put("SimSun", simSun);
            document.setFontMapper(fontMapper);
        }
        try (OutputStream output = Files.newOutputStream(pdf)) {
            Docx4J.toPDF(document, output);
        } catch (IOException exception) {
            throw exception;
        }
    }
}
