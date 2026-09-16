package com.example.agentservice.procurement.bid.service;

import com.example.agentservice.procurement.bid.domain.BidTechnicalOutline;
import java.util.List;
import org.springframework.stereotype.Service;

/** 按已确认目录生成并组装完整的投标技术方案。 */
@Service
public class BidContentGenerationService {

    static final String MANUAL_PLACEHOLDER = "> 【人工填写】请补充本章节内容。";

    private final BidSectionGenerationService sectionService;

    public BidContentGenerationService(BidSectionGenerationService sectionService) {
        this.sectionService = sectionService;
    }

    public String generate(BidTechnicalOutline outline, String tenderFacts, String supplierFacts) {
        if (outline == null || outline.title() == null || outline.title().isBlank()
                || outline.sections() == null || outline.sections().isEmpty()) {
            throw new IllegalArgumentException("已确认的技术方案目录不能为空");
        }
        StringBuilder markdown = new StringBuilder("# ").append(outline.title()).append("\n\n");
        appendSections(markdown, outline, outline.sections(), 2, tenderFacts, supplierFacts);
        return markdown.toString().trim();
    }

    /** 父章节只组织结构，末级章节才调用模型生成正文。 */
    private void appendSections(
            StringBuilder markdown,
            BidTechnicalOutline outline,
            List<BidTechnicalOutline.Section> sections,
            int level,
            String tenderFacts,
            String supplierFacts) {
        for (BidTechnicalOutline.Section section : sections) {
            markdown.append("#".repeat(level)).append(' ').append(section.title()).append("\n\n");
            if (section.children() == null || section.children().isEmpty()) {
                String content = section.effectiveContentMode() == BidTechnicalOutline.ContentMode.MANUAL
                        ? MANUAL_PLACEHOLDER
                        : sectionService.generate(outline, section, tenderFacts, supplierFacts);
                markdown.append(content).append("\n\n");
            }
            else {
                appendSections(markdown, outline, section.children(), level + 1,
                        tenderFacts, supplierFacts);
            }
        }
    }

    /** 判断目录中是否存在需要用户填写的末级章节。 */
    public boolean requiresManualCompletion(BidTechnicalOutline outline) {
        return containsManualSection(outline.sections());
    }

    private boolean containsManualSection(List<BidTechnicalOutline.Section> sections) {
        for (BidTechnicalOutline.Section section : sections) {
            if (section.children() == null || section.children().isEmpty()) {
                if (section.effectiveContentMode() == BidTechnicalOutline.ContentMode.MANUAL) return true;
            }
            else if (containsManualSection(section.children())) {
                return true;
            }
        }
        return false;
    }
}
