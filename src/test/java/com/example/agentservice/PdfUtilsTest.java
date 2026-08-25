package com.example.agentservice;

import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.utils.PdfUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
class PdfUtilsTest {

    private static final String PDF_URL =
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/Scan-%E5%AE%9C%E6%98%8C%E5%87%A0%E4%BD%95%E6%B5%8B%E7%BB%98%E7%A7%91%E6%8A%80%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B88960772218806061282.pdf";

    @Autowired
    private PdfUtils pdfUtils;

    @Test
    void shouldConvertPdfToImages() throws Exception {
        List<ImmImagePage> pages = pdfUtils.pdfToImage(PDF_URL);

        assertFalse(pages.isEmpty());
        pages.forEach(page -> System.out.println(
                "PDF图片页: document=" + page.document()
                        + ", page=" + page.page()
                        + ", url=" + page.url()));
    }
}
