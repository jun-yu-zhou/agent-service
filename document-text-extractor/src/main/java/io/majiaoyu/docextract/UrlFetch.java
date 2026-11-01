package io.majiaoyu.docextract;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 远程文档拉取：http/https URL → 临时文件。
 *
 * <p>含 SSRF 闸门（仅 http/https，拒绝解析到内网/环回/链路本地的地址，可经
 * {@code docextract.url.allow-private-network} 关闭以支持内网自建 OSS）、
 * 流式写盘并强制大小上限、按 URL 后缀与 Content-Type 识别格式。</p>
 */
final class UrlFetch {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    /** 允许解析到内网/环回地址（自建内网 OSS 场景）；默认拒绝，防 SSRF。 */
    private static final boolean ALLOW_PRIVATE_NETWORK =
            Boolean.parseBoolean(System.getProperty("docextract.url.allow-private-network", "false"));
    /** Content-Type → SDK 支持格式的映射（URL 后缀优先于该表）。 */
    private static final Map<String, String> CONTENT_TYPE_FORMATS = Map.ofEntries(
            Map.entry("application/pdf", "pdf"),
            Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("text/plain", "txt"),
            Map.entry("text/markdown", "md"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("image/tiff", "tif"));

    private UrlFetch() {
    }

    /** 下载远程文档到临时文件（扩展名标识格式），流式写盘并强制大小上限；调用方负责删除临时文件。 */
    static Path downloadToTempFile(String fileUrl, long maxFileBytes) throws Exception {
        URI uri = URI.create(fileUrl);
        validate(uri);
        HttpResponse<InputStream> response = CLIENT.send(request(uri),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("远程文件下载失败 HTTP " + response.statusCode()
                    + "：" + fileUrl + "（签名过期或地址无权限时会 403）");
        }
        validate(response.uri());   // 重定向后以最终地址为准
        Path temp = Files.createTempFile("docextract-", "." + detectFormat(fileUrl,
                response.headers().firstValue("Content-Type").orElse("")));
        long total = 0;
        try (InputStream body = response.body()) {
            try (var output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = body.read(buffer)) != -1) {
                    total += read;
                    if (maxFileBytes > 0 && total > maxFileBytes) {
                        throw new IllegalStateException("远程文件超过大小上限 " + maxFileBytes
                                + " 字节：" + fileUrl);
                    }
                    output.write(buffer, 0, read);
                }
            }
        }
        return temp;
    }

    /** 格式识别：URL 路径后缀优先，其次 Content-Type；均无法判断时抛异常。 */
    static String detectFormat(String fileUrl, String contentType) {
        String path = URI.create(fileUrl).getPath();
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (List.of("pdf", "doc", "docx", "txt", "md", "jpg", "jpeg", "png", "bmp", "tif", "tiff")
                    .contains(ext)) {
                return ext.equals("jpeg") ? "jpg" : ext;
            }
        }
        String mime = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        String format = CONTENT_TYPE_FORMATS.get(mime);
        if (format == null) {
            throw new IllegalArgumentException(
                    "无法识别远程文件格式（URL 后缀与 Content-Type 均无法判断）：" + fileUrl);
        }
        return format;
    }

    private static void validate(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("远程地址仅支持 http/https：" + uri);
        }
        checkHost(uri);
    }

    /** SSRF 闸门：拒绝解析到环回/内网/链路本地的地址；IP 解析失败视为无效地址。 */
    private static void checkHost(URI uri) {
        if (ALLOW_PRIVATE_NETWORK) {
            return;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                    throw new IllegalArgumentException("拒绝访问内网/环回地址（SSRF 防护，如确有内网 OSS "
                            + "场景可经系统属性 docextract.url.allow-private-network 关闭）：" + uri.getHost());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("远程地址无法解析：" + uri.getHost(), exception);
        }
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
    }
}
