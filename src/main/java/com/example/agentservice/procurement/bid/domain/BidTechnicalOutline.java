package com.example.agentservice.procurement.bid.domain;

import java.util.List;

/** 用户可编辑和确认的投标技术方案树形目录。 */
public record BidTechnicalOutline(
        String title,
        List<Section> sections) {

    /** 一项章节；children 为空时表示后续需要生成正文的末级章节。 */
    public record Section(
            String id,
            String title,
            String writingFocus,
            List<String> requirementRefs,
            List<Section> children) {
    }
}
