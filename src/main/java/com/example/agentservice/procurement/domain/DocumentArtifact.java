package com.example.agentservice.procurement.domain;

import java.time.Instant;

/** 文档版本上传后形成的不可变导出产物。 */
public record DocumentArtifact(
        /** 导出产物的文件类型。 */
        ArtifactType type,

        /** 产物在对象存储中的对象键。 */
        String objectKey,

        /** 供用户访问或下载产物的地址。 */
        String downloadUrl,

        /** 产物文件大小，单位为字节。 */
        long size,

        /** 产物生成时间。 */
        Instant createdAt) {
}
