package com.example.agentservice.procurement.tender.service;

import com.example.agentservice.procurement.common.docx.DocxFormatter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Component;

/** 组合通用 DOCX 能力，为下载的招标文件应用正式排版。 */
@Component
public class TenderDocumentDocxFormatter {

    /** 保留云端文档原有页面设置，补齐目录、页码、表格和大章节分页。 */
    public byte[] format(byte[] content) throws IOException {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("招标文件内容不能为空");
        }
        try (var input = new ByteArrayInputStream(content);
                var output = new ByteArrayOutputStream()) {
            WordprocessingMLPackage document = WordprocessingMLPackage.load(input);
            DocxFormatter.of(document)
                    .autoTocHeading()
                    .toc()
                    .pageNumber()
                    .tableLayout()
                    .majorChapterPageBreak()
                    .apply();
            document.save(output);
            return output.toByteArray();
        }
        catch (Exception exception) {
            throw new IOException("招标文件 Word 排版失败", exception);
        }
    }
}
