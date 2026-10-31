package com.example.agentservice;

import com.example.agentservice.service.ImmService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/** IMM 各项能力的集成测试，后续新增功能可继续集中在此类中。 */
@SpringBootTest
class ImmServiceTest {

    private static final String WORD_DOCUMENT_URL =
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/%E3%80%90%E6%8B%9B%E6%A0%87%E6%96%87%E4%BB%B6%E3%80%91%E5%AD%A6%E7%94%9F%E5%AE%BF%E8%88%8D%E7%8B%AC%E7%AB%8B%E5%BC%8F%E7%83%9F%E6%84%9F%E6%8A%A5%E8%AD%A6%E5%99%A8%E9%87%87%E8%B4%AD%E9%A1%B9%E7%9B%AE%EF%BC%88%E5%8F%91%E5%B8%83%EF%BC%89.doc";
    private static final String IMAGE_URL =
            "https://javawebemp.oss-cn-beijing.aliyuncs.com/db7f98ad93c6d5e333ba85b576e22524.jpg";

    @Autowired
    private ImmService immService;

    @Test
    void shouldExtractDocumentTextFromWordDocument() throws Exception {
        String text = immService.extractDocumentText(WORD_DOCUMENT_URL, "doc");

        Assertions.assertNotNull(text);
        Assertions.assertFalse(text.isBlank());
        System.out.println("文档正文提取结果：\n" + normalizeLineBreaks(text));
    }

    @Test
    void shouldExtractDocumentTextByFileExtensionInUrl() throws Exception {
        String text = immService.extractDocumentText(WORD_DOCUMENT_URL);

        Assertions.assertNotNull(text);
        Assertions.assertFalse(text.isBlank());
        System.out.println("自动解析后缀的文档正文提取结果：\n" + normalizeLineBreaks(text));
    }

    @Test
    void shouldSpliceImagesVertically() throws Exception {
        String resultUrl = immService.spliceImagesVertically(List.of(IMAGE_URL, IMAGE_URL));

        Assertions.assertNotNull(resultUrl);
        Assertions.assertFalse(resultUrl.isBlank());
        System.out.println("图片纵向拼接结果：\n" + resultUrl);
    }

    @Test
    void shouldDetectTextsFromImage() throws Exception {
        String text = immService.detectImageTexts(IMAGE_URL);

        Assertions.assertNotNull(text);
        Assertions.assertFalse(text.isBlank());
        System.out.println("图片正文提取结果：\n" + normalizeLineBreaks(text));
    }

    private String normalizeLineBreaks(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n');
    }
}
