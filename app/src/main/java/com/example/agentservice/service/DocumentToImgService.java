package com.example.agentservice.service;

import com.example.agentservice.imm.task.DocumentToImgTask;
import com.example.agentservice.entity.ImmImagePage;
import com.example.agentservice.imm.request.DocumentToImgRequest;

import java.util.List;

public interface DocumentToImgService {

    /** 只提交 PDF 转图片任务，不等待任务完成。 */
    DocumentToImgTask convertDocumentToImages(DocumentToImgRequest request) throws Exception;

    /** 查询并轮询任务，直到返回 OSS 图片结果。 */
    List<ImmImagePage> getDocumentToImagesResult(DocumentToImgTask task) throws Exception;
}
