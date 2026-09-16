package com.example.agentservice.procurement.bid.domain;

import java.util.List;

/** 用户可编辑和确认的投标技术方案树形目录。 */
public record BidTechnicalOutline(
        String title,
        List<Section> sections) {

    /** 末级章节正文的处理方式。 */
    public enum ContentMode {
        /** 由模型生成章节正文。 */
        AI,

        /** 保留章节位置，由用户补充正文。 */
        MANUAL
    }

    /** 一项章节；children 为空时表示后续需要生成正文的末级章节。 */
    public record Section(
            String id,
            String title,
            String writingFocus,
            List<String> requirementRefs,
            ContentMode contentMode,
            List<Section> children) {

        /** 兼容尚未确认的旧目录；旧节点默认沿用 AI 生成。 */
        public ContentMode effectiveContentMode() {
            return contentMode == null ? ContentMode.AI : contentMode;
        }
    }
}
