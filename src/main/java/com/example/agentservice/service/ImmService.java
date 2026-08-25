package com.example.agentservice.service;

import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.imm.task.DocumentToImgTask;

import java.util.List;

public interface ImmService {

    /** Submits one OSS document conversion task and returns the task context. */
    DocumentToImgTask convertDocumentToImages(String pdfUrl) throws Exception;

    /** Polls one document conversion task and returns its signed image URLs. */
    List<ImmImagePage> getDocumentToImagesResult(DocumentToImgTask task) throws Exception;

    /** Extracts plain text from a Word document in OSS. */
    String extractDocumentText(String wordOssUrl, String fileExtension) throws Exception;

    /** Stitches up to 10 OSS images horizontally and returns the signed output URL. */
    String spliceImages(List<String> imageUrls) throws Exception;

    /** Extracts all OCR text from an OSS image. */
    String detectImageTexts(String imageUrl) throws Exception;
}
