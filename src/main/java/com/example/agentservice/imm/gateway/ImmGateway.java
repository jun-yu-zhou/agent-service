package com.example.agentservice.imm.gateway;

import com.example.agentservice.imm.task.DocumentToImgTask;
import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.imm.request.DocumentToImgRequest;
import com.example.agentservice.imm.request.ExtractDocumentTextRequest;
import com.example.agentservice.imm.request.SpliceImagesRequest;
import com.example.agentservice.imm.request.DetectImageTextsRequest;
import com.example.agentservice.service.DocumentToImgService;
import com.example.agentservice.service.detectImageTextsService;
import com.example.agentservice.service.extractDocumentTextService;
import com.example.agentservice.service.spliceImagesService;
import com.example.agentservice.imm.support.ImmOssPath;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ImmService使用该类，将任务委派到对应service
 */
@Component
public class ImmGateway {

    private final extractDocumentTextService extractDocumentTextService;
    private final spliceImagesService spliceImagesService;
    private final detectImageTextsService detectImageTextsService;
    private final DocumentToImgService documentToImgService;

    public ImmGateway(
            extractDocumentTextService extractDocumentTextService,
            spliceImagesService spliceImagesService,
            detectImageTextsService detectImageTextsService,
            DocumentToImgService documentToImgService) {
        this.extractDocumentTextService = extractDocumentTextService;
        this.spliceImagesService = spliceImagesService;
        this.detectImageTextsService = detectImageTextsService;
        this.documentToImgService = documentToImgService;
    }

    public String extractDocumentText(String wordOssUrl, String fileExtension) throws Exception {
        return extractDocumentTextService.extractDocumentText(
                new ExtractDocumentTextRequest(toSourceUri(wordOssUrl), fileExtension));
    }

    public String extractDocumentText(String wordOssUrl) throws Exception {
        return extractDocumentText(wordOssUrl, fileExtension(wordOssUrl));
    }

    public String spliceImagesHorizontally(List<String> imageUrls) throws Exception {
        List<String> sourceUris = imageUrls.stream().map(this::toSourceUri).toList();
        return spliceImagesService.spliceImagesHorizontally(new SpliceImagesRequest(sourceUris));
    }

    public String spliceImagesVertically(List<String> imageUrls) throws Exception {
        List<String> sourceUris = imageUrls.stream().map(this::toSourceUri).toList();
        return spliceImagesService.spliceImagesVertically(new SpliceImagesRequest(sourceUris));
    }

    public String detectImageTexts(String imageUrl) throws Exception {
        return detectImageTextsService.detectImageTexts(
                new DetectImageTextsRequest(toSourceUri(imageUrl)));
    }

    public DocumentToImgTask convertDocumentToImages(String pdfUrl) throws Exception {
        String sourceUri = toSourceUri(pdfUrl);
        String sourceKey = objectKey(pdfUrl);
        String documentName = sourceKey.substring(sourceKey.lastIndexOf('/') + 1);
        String outputPrefix = "imm-review/" + documentName + "/";
        String targetUriPrefix = ImmOssPath.uri(outputPrefix);
        return documentToImgService.convertDocumentToImages(
                new DocumentToImgRequest(sourceUri, targetUriPrefix, documentName));
    }

    public List<ImmImagePage> getDocumentToImagesResult(DocumentToImgTask task) throws Exception {
        return documentToImgService.getDocumentToImagesResult(task);
    }

    private String toSourceUri(String url) {
        return ImmOssPath.uri(objectKey(url));
    }

    private String objectKey(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("OSS 文件地址不能为空");
        }
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        String rawPath = uri.getRawPath();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || rawPath == null || rawPath.length() <= 1) {
            throw new IllegalArgumentException("文件地址必须是带对象路径的 HTTP 或 HTTPS URL: " + url);
        }
        return URLDecoder.decode(rawPath.substring(1).replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private String fileExtension(String url) {
        String objectKey = objectKey(url);
        String fileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0 || extensionStart == fileName.length() - 1) {
            throw new IllegalArgumentException("无法从OSS URL解析文件后缀: " + url);
        }
        return fileName.substring(extensionStart + 1);
    }
}
