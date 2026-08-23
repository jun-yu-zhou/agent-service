package com.example.agentservice.service;

import com.example.agentservice.entity.ImmImagePage;

import java.util.List;

public interface ImmService {

    /** Converts PDF files in OSS to page images and returns their signed OSS URLs. */
    List<ImmImagePage> convertPdfsToImages(List<String> pdfUrls) throws Exception;
}
