package com.example.agentservice;

import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.imm.task.DocumentToImgTask;
import com.example.agentservice.service.ImmService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档按页切图真实集成测试（走真实 OSS/IMM，需要本地凭证）。
 *
 * <p>测试样例：src/test/resources/fixtures/imm-sample-10p.docx 与 imm-sample-10p.doc（10 页）。
 * 切图入口 {@link ImmService#convertDocumentToImages(String)} 只接受 OSS http/https URL，
 * 样例文件上传 OSS 后把 URL 填入下方常量并移除 {@code @Disabled}。</p>
 */
@SpringBootTest
class DocumentToImgIntegrationTest {

    private static final String DOCX_OSS_URL =
            "http://oss1.easyjcx.com/co-order/2026/10/30/b5a45f17439b4c42bc3b6cc13fd1e6079093546697574386031.docx";
    private static final String DOC_OSS_URL =
            "http://oss1.easyjcx.com/co-order/2026/10/30/9ac2add036b845659dbf6ef2d3defd322502854779531257731.doc";

    @Autowired
    private ImmService immService;

    @Test
    void shouldConvertTenPageDocxToImages() throws Exception {
        assertTenPages(DOCX_OSS_URL, "imm-sample-10p.docx");
    }

    @Test
    void shouldConvertTenPageDocToImages() throws Exception {
        assertTenPages(DOC_OSS_URL, "imm-sample-10p.doc");
    }

    private void assertTenPages(String ossUrl, String documentName) throws Exception {
        DocumentToImgTask task = immService.convertDocumentToImages(ossUrl);
        assertThat(task.taskId()).isNotBlank();
        System.out.println("已提交转换任务: " + task.taskId() + ", document=" + documentName);

        List<ImmImagePage> pages = immService.getDocumentToImagesResult(task);
        assertThat(pages).hasSize(10);
        assertThat(pages).extracting(ImmImagePage::page)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(pages).allMatch(page -> page.url().startsWith("http"));
        pages.forEach(page -> System.out.println("第" + page.page() + "页: " + page.url()));
    }
}
