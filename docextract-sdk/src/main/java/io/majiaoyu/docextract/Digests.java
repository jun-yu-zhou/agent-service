package io.majiaoyu.docextract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 极小工具集：替代 hutool 的摘要能力，保持 SDK 零额外依赖。 */
final class Digests {

    private Digests() {
    }

    static String md5Hex(byte[] bytes) {
        try {
            byte[] hashed = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 MD5 实现", exception);
        }
    }
}
