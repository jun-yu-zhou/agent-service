package com.example.agentservice.procurement.service;

import com.example.agentservice.service.ImmService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 根据一份招标文件和多份 OSS 供应商材料生成投标文件初稿。 */
@Service
public class BidDocumentDraftWorkflow {

    private final ImmService immService;
    private final BidDocumentGenerationService generationService;

    public BidDocumentDraftWorkflow(ImmService immService, BidDocumentGenerationService generationService) {
        this.immService = immService;
        this.generationService = generationService;
    }

    public String generateDraft(String tenderDocumentUrl, List<String> supplierDocumentUrls) throws Exception {
        if (tenderDocumentUrl == null || tenderDocumentUrl.isBlank()) {
            throw new IllegalArgumentException("招标文件地址不能为空");
        }
        if (supplierDocumentUrls == null || supplierDocumentUrls.isEmpty()) {
            throw new IllegalArgumentException("供应商资料文件不能为空");
        }
        String tenderText = requireText(immService.extractDocumentText(tenderDocumentUrl), "招标文件");
        String supplierText = extractSupplierText(supplierDocumentUrls);
        return generationService.generateDraft(tenderText, supplierText);
    }

    private String extractSupplierText(List<String> supplierDocumentUrls) throws Exception {
        StringBuilder materials = new StringBuilder();
        for (int index = 0; index < supplierDocumentUrls.size(); index++) {
            String documentUrl = supplierDocumentUrls.get(index);
            if (documentUrl == null || documentUrl.isBlank()) {
                throw new IllegalArgumentException("供应商资料文件地址不能为空");
            }
            String text = requireText(immService.extractDocumentText(documentUrl), "供应商资料第" + (index + 1) + "份");
            if (!materials.isEmpty()) {
                materials.append("\n\n");
            }
            materials.append("## 供应商资料第").append(index + 1).append("份\n\n").append(text);
        }
        return materials.toString();
    }

    private String requireText(String text, String materialName) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(materialName + "未提取到正文，不能静默忽略");
        }
        return text;
    }

}
