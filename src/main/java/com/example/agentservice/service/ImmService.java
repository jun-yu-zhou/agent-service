package com.example.agentservice.service;

import com.example.agentservice.entity.ImmImagePage;

import java.util.List;

public interface ImmService {

    /** Converts PDF files in OSS to page images and returns their signed OSS URLs. */
    List<ImmImagePage> convertPdfsToImages(List<String> pdfUrls) throws Exception;

    /** Extracts plain text from a Word document in OSS. */
    String extractDocumentText(String wordOssUrl, String fileExtension) throws Exception;

    /** Stitches up to 10 OSS images horizontally and returns the signed output URL. */
    String spliceImages(List<String> imageUrls) throws Exception;

    /** Extracts all OCR text from an OSS image. */
    String detectImageTexts(String imageUrl) throws Exception;
}
