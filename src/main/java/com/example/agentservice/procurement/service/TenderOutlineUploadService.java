package com.example.agentservice.procurement.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/** 读取用于生成初稿的招标文件 HTML 模板。 */
@Service
public class TenderOutlineUploadService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("html", "htm");

    public String readTemplate(MultipartFile file) throws Exception {
        if (file == null) {
            throw new IllegalArgumentException("请上传招标文件 HTML 模板");
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("招标文件 HTML 模板是空文件（0 字节），请先写入模板内容");
        }
        String template = new String(file.getBytes(), StandardCharsets.UTF_8);
        String extension = extension(file.getOriginalFilename());
        if (!SUPPORTED_EXTENSIONS.contains(extension) && !isHtml(file.getContentType(), template)) {
            throw new IllegalArgumentException("上传内容不是有效的 HTML 模板，文件名：" + file.getOriginalFilename());
        }
        if (template.isBlank()) {
            throw new IllegalArgumentException("招标文件 HTML 模板不能为空");
        }
        return template;
    }

    private boolean isHtml(String contentType, String content) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
            return true;
        }
        String normalized = content.stripLeading().toLowerCase(Locale.ROOT);
        return normalized.startsWith("<!doctype html") || normalized.startsWith("<html")
                || normalized.startsWith("<div") || normalized.startsWith("<p");
    }

    private String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int index = filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index + 1).trim().toLowerCase(Locale.ROOT);
    }
}
