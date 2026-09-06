package com.example.agentservice.procurement.service;

import org.docx4j.Docx4J;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Converts DOCX to PDF through docx4j's pure-Java XSL-FO exporter. */
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
        try (OutputStream output = Files.newOutputStream(pdf)) {
            Docx4J.toPDF(document, output);
        } catch (IOException exception) {
            throw exception;
        }
    }
}
