package com.github.tvbox.osc.util;

import android.net.Uri;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M3U8 解析器
 * 支持解析 HLS 播放列表，提取分片信息
 */
public class M3U8Parser {

    private static final String TAG = "M3U8Parser";
    private static final String EXTM3U = "#EXTM3U";
    private static final String EXT_X_STREAM_INF = "#EXT-X-STREAM-INF";
    private static final String EXTINF = "#EXTINF";
    private static final String EXT_X_KEY = "#EXT-X-KEY";
    private static final String EXT_X_ENDLIST = "#EXT-X-ENDLIST";
    private static final String EXT_X_MAP = "#EXT-X-MAP";

    /**
     * M3U8 信息类
     */
    public static class M3U8Info {
        public String baseUrl;              // 基础URL
        public List<Segment> segments;      // 分片列表
        public List<String> variantUrls;    // 多码率流URL列表（主播放列表）
        public boolean isVariantPlaylist;   // 是否是主播放列表
        public EncryptionInfo encryptionInfo; // 加密信息
        public String initSegmentUrl;       // 初始化分片URL（fMP4格式）
        public boolean hasEndList;          // 是否有结束标记

        public M3U8Info() {
            segments = new ArrayList<>();
            variantUrls = new ArrayList<>();
        }

        public int getTotalSegments() {
            return segments.size();
        }
    }

    /**
     * 分片信息
     */
    public static class Segment {
        public String url;          // 分片URL
        public float duration;      // 时长（秒）
        public int index;           // 分片索引
        public EncryptionInfo encryptionInfo; // 该分片的加密信息

        public Segment(String url, float duration, int index) {
            this.url = url;
            this.duration = duration;
            this.index = index;
        }
    }

    /**
     * 加密信息
     */
    public static class EncryptionInfo {
        public String method;       // 加密方法 (AES-128, SAMPLE-AES, etc.)
        public String keyUrl;       // 密钥URL
        public String iv;           // 初始化向量

        public boolean isEncrypted() {
            return !TextUtils.isEmpty(method) && !"NONE".equalsIgnoreCase(method);
        }
    }

    /**
     * 解析 M3U8 内容
     *
     * @param content M3U8 文件内容
     * @param baseUrl M3U8 文件的URL（用于解析相对路径）
     * @return M3U8Info 解析结果
     */
    public static M3U8Info parse(String content, String baseUrl) throws IOException {
        if (TextUtils.isEmpty(content)) {
            throw new IOException("M3U8 content is empty");
        }

        M3U8Info info = new M3U8Info();
        info.baseUrl = getBaseUrl(baseUrl);

        BufferedReader reader = new BufferedReader(new StringReader(content));
        String line;
        boolean isFirstLine = true;
        float currentDuration = 0;
        int segmentIndex = 0;
        EncryptionInfo currentEncryption = null;

        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (TextUtils.isEmpty(line)) {
                continue;
            }

            // 验证 M3U8 格式
            if (isFirstLine) {
                if (!line.startsWith(EXTM3U)) {
                    throw new IOException("Invalid M3U8 format: missing #EXTM3U header");
                }
                isFirstLine = false;
                continue;
            }

            // 解析主播放列表（多码率）
            if (line.startsWith(EXT_X_STREAM_INF)) {
                info.isVariantPlaylist = true;
                String nextLine = reader.readLine();
                if (nextLine != null && !nextLine.startsWith("#")) {
                    String variantUrl = resolveUrl(info.baseUrl, nextLine.trim());
                    info.variantUrls.add(variantUrl);
                }
                continue;
            }

            // 解析加密信息
            if (line.startsWith(EXT_X_KEY)) {
                currentEncryption = parseEncryptionInfo(line, info.baseUrl);
                if (info.encryptionInfo == null) {
                    info.encryptionInfo = currentEncryption;
                }
                continue;
            }

            // 解析初始化分片（fMP4格式）
            if (line.startsWith(EXT_X_MAP)) {
                info.initSegmentUrl = parseMapUri(line, info.baseUrl);
                continue;
            }

            // 解析分片时长
            if (line.startsWith(EXTINF)) {
                currentDuration = parseExtInf(line);
                continue;
            }

            // 解析结束标记
            if (line.startsWith(EXT_X_ENDLIST)) {
                info.hasEndList = true;
                continue;
            }

            // 解析分片URL（非注释行）
            if (!line.startsWith("#")) {
                String segmentUrl = resolveUrl(info.baseUrl, line);
                Segment segment = new Segment(segmentUrl, currentDuration, segmentIndex++);
                segment.encryptionInfo = currentEncryption;
                info.segments.add(segment);
                currentDuration = 0;
            }
        }

        reader.close();
        return info;
    }

    /**
     * 解析 EXTINF 标签获取时长
     */
    private static float parseExtInf(String line) {
        try {
            // #EXTINF:10.0, or #EXTINF:10,
            Pattern pattern = Pattern.compile("#EXTINF:([\\d.]+)");
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return Float.parseFloat(matcher.group(1));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }

    /**
     * 解析加密信息
     */
    private static EncryptionInfo parseEncryptionInfo(String line, String baseUrl) {
        EncryptionInfo info = new EncryptionInfo();

        // 解析 METHOD
        Pattern methodPattern = Pattern.compile("METHOD=([^,]+)");
        Matcher methodMatcher = methodPattern.matcher(line);
        if (methodMatcher.find()) {
            info.method = methodMatcher.group(1);
        }

        // 解析 URI
        Pattern uriPattern = Pattern.compile("URI=\"([^\"]+)\"");
        Matcher uriMatcher = uriPattern.matcher(line);
        if (uriMatcher.find()) {
            info.keyUrl = resolveUrl(baseUrl, uriMatcher.group(1));
        }

        // 解析 IV
        Pattern ivPattern = Pattern.compile("IV=([^,]+)");
        Matcher ivMatcher = ivPattern.matcher(line);
        if (ivMatcher.find()) {
            info.iv = ivMatcher.group(1);
        }

        return info;
    }

    /**
     * 解析 EXT-X-MAP 标签获取初始化分片URL
     */
    private static String parseMapUri(String line, String baseUrl) {
        Pattern pattern = Pattern.compile("URI=\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return resolveUrl(baseUrl, matcher.group(1));
        }
        return null;
    }

    /**
     * 获取基础URL（去掉文件名部分）
     */
    private static String getBaseUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return "";
        }
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash > 0) {
            return url.substring(0, lastSlash + 1);
        }
        return url;
    }

    /**
     * 解析相对URL为绝对URL
     */
    public static String resolveUrl(String baseUrl, String relativeUrl) {
        if (TextUtils.isEmpty(relativeUrl)) {
            return relativeUrl;
        }

        // 已经是绝对URL
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl;
        }

        // 协议相对URL
        if (relativeUrl.startsWith("//")) {
            try {
                Uri baseUri = Uri.parse(baseUrl);
                return baseUri.getScheme() + ":" + relativeUrl;
            } catch (Exception e) {
                return "https:" + relativeUrl;
            }
        }

        // 根路径相对URL
        if (relativeUrl.startsWith("/")) {
            try {
                Uri baseUri = Uri.parse(baseUrl);
                return baseUri.getScheme() + "://" + baseUri.getHost() +
                        (baseUri.getPort() > 0 ? ":" + baseUri.getPort() : "") + relativeUrl;
            } catch (Exception e) {
                return relativeUrl;
            }
        }

        // 普通相对URL
        return baseUrl + relativeUrl;
    }

    /**
     * 判断URL是否是M3U8格式
     */
    public static boolean isM3u8Url(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lowerUrl = url.toLowerCase();
        // 检查扩展名
        if (lowerUrl.contains(".m3u8")) {
            return true;
        }
        // 检查常见的HLS标识
        if (lowerUrl.contains("/hls/") || lowerUrl.contains("format=m3u8")) {
            return true;
        }
        return false;
    }

    /**
     * 判断内容是否是M3U8格式
     */
    public static boolean isM3u8Content(String content) {
        if (TextUtils.isEmpty(content)) {
            return false;
        }
        return content.trim().startsWith(EXTM3U);
    }
}
