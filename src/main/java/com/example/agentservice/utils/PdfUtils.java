package com.example.agentservice.utils;

import com.example.agentservice.imm.task.DocumentToImgTask;
import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.service.ImmService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PdfUtils {

    private final ImmService immService;

    public PdfUtils(ImmService immService) {
        this.immService = immService;
    }

    public List<ImmImagePage> pdfToImage(String pdfUrl) throws Exception {
        return pdfToImage(List.of(pdfUrl));
    }

    /** 使用 OSS PDF URL 提交转换任务，并获取转换后的图片页。 */
    public List<ImmImagePage> pdfToImage(List<String> pdfUrls) throws Exception {
        if (pdfUrls == null || pdfUrls.isEmpty()) {
            throw new IllegalArgumentException("至少需要传入一个 PDF 文件地址");
        }
        List<DocumentToImgTask> tasks = new ArrayList<>();
        for (String pdfUrl : pdfUrls) {
            tasks.add(immService.convertDocumentToImages(pdfUrl));
        }
        List<ImmImagePage> pages = new ArrayList<>();
        for (DocumentToImgTask task : tasks) {
            pages.addAll(immService.getDocumentToImagesResult(task));
        }
        return pages;
    }
}
