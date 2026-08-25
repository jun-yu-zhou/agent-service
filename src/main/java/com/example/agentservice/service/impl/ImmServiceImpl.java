package com.example.agentservice.service.impl;

import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.imm.task.DocumentToImgTask;
import com.example.agentservice.imm.gateway.ImmGateway;
import com.example.agentservice.service.ImmService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ImmServiceImpl implements ImmService {

    private final ImmGateway immGateway;

    public ImmServiceImpl(ImmGateway immGateway) {
        this.immGateway = immGateway;
    }

    @Override
    public DocumentToImgTask convertDocumentToImages(String pdfUrl) throws Exception {
        return immGateway.convertDocumentToImages(pdfUrl);
    }

    @Override
    public List<ImmImagePage> getDocumentToImagesResult(DocumentToImgTask task) throws Exception {
        return immGateway.getDocumentToImagesResult(task);
    }

    @Override
    public String extractDocumentText(String wordOssUrl, String fileExtension) throws Exception {
        return immGateway.extractDocumentText(wordOssUrl, fileExtension);
    }

    @Override
    public String spliceImages(List<String> imageUrls) throws Exception {
        return immGateway.spliceImages(imageUrls);
    }

    @Override
    public String detectImageTexts(String imageUrl) throws Exception {
        return immGateway.detectImageTexts(imageUrl);
    }
}
