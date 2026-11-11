package com.forcemind.chatgpt.profiles;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import org.json.JSONObject;

/** The public feed selects only a pinned release asset, never an arbitrary APK URL. */
final class AppUpdateRelease {
    static final String PACKAGE = "com.forcemind.chatgpt.profiles";
    static final String FEED = "https://chatgpt.xincreates.com/updates/android.json";
    static final String FIXTURE = BuildConfig.APP_UPDATE_TEST_ORIGIN;
    static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final DateTimeFormatter VERSION = DateTimeFormatter.ofPattern("uuuu.MM.dd-HHmm", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    final String name, abi, sha256, certificate, url;
    final long code, size;
    final boolean privateSource;
    final JSONObject metadata;
    final Delta delta;
    record Delta(long fromVersionCode, String baseSHA256, long size, String sha256, String url) {}

    private AppUpdateRelease(JSONObject data, boolean fixture) throws Exception {
        if (integer(data, "schema") != 1 || !PACKAGE.equals(data.getString("packageName")))
            throw new IllegalArgumentException("更新清单身份不符");
        name = data.getString("versionName");
        if (!name.matches("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}-[0-9]{4}"))
            throw new IllegalArgumentException("更新版本格式无效");
        LocalDateTime date = LocalDateTime.parse(name, VERSION);
        code = integer(data, "versionCode");
        size = integer(data, "size");
        if (code < 1 || code > Integer.MAX_VALUE || size < 1 || size > MAX_BYTES)
            throw new IllegalArgumentException("更新版本或文件大小无效");
        abi = data.getString("abi");
        if (!(fixture ? "x86_64" : "arm64-v8a").equals(abi))
            throw new IllegalArgumentException("更新包架构不符");
        sha256 = data.getString("sha256");
        certificate = data.getString("certificateSHA256");
        if (!sha256.matches("[a-f0-9]{64}") || !certificate.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("更新摘要无效");
        String tag = String.format(Locale.ROOT, "v%d.%d.%d-%02d%02d", date.getYear(), date.getMonthValue(),
                date.getDayOfMonth(), date.getHour(), date.getMinute());
        String expected = "https://github.com/ForceMind/ChatGPT/releases/download/" + tag
                + "/ChatGPT-Studio-" + name + "-android-arm64.apk";
        url = data.getString("url");
        privateSource = !fixture && BuildConfig.APP_UPDATE_ACCEPTANCE && AppUpdateAcceptance.APK.equals(url);
        if (!privateSource && !(fixture ? FIXTURE + "/update.apk" : expected).equals(url))
            throw new IllegalArgumentException("更新包地址无效");
        metadata = new JSONObject().put("schema", 1).put("packageName", PACKAGE).put("versionName", name)
                .put("versionCode", code).put("size", size).put("abi", abi).put("sha256", sha256)
                .put("certificateSHA256", certificate).put("url", url);
        delta = optionalDelta(data.optJSONObject("delta"), fixture);
        if (delta != null) metadata.put("delta", new JSONObject()
                .put("fromVersionCode", delta.fromVersionCode()).put("baseSHA256", delta.baseSHA256())
                .put("size", delta.size()).put("sha256", delta.sha256()).put("url", delta.url()));
    }

    private Delta optionalDelta(JSONObject data, boolean fixture) {
        if (data == null || privateSource) return null;
        try {
            long from = integer(data, "fromVersionCode");
            long patchSize = integer(data, "size");
            String base = data.getString("baseSHA256");
            String digest = data.getString("sha256");
            String address = data.getString("url");
            String expected = fixture ? FIXTURE + "/update.delta"
                    : url.substring(0, url.length() - 4) + "-from-" + from + ".delta";
            if (from < 1 || from >= code || patchSize < 1 || patchSize >= size
                    || !base.matches("[a-f0-9]{64}") || !digest.matches("[a-f0-9]{64}") || !expected.equals(address))
                return null;
            return new Delta(from, base, patchSize, digest, address);
        } catch (Exception invalid) {
            return null;
        }
    }

    static AppUpdateRelease parse(String text, boolean fixture) throws Exception {
        if (text == null || text.length() > 65536) throw new IllegalArgumentException("更新清单过大");
        return new AppUpdateRelease(new JSONObject(text), fixture);
    }

    private static long integer(JSONObject data, String key) throws Exception {
        Object raw = data.get(key);
        if (!(raw instanceof Integer) && !(raw instanceof Long))
            throw new IllegalArgumentException("更新清单数字无效");
        return ((Number) raw).longValue();
    }

    String downloadUrl(boolean accelerated, boolean fixture) {
        return accelerated && !fixture && !privateSource ? "https://gh-proxy.org/" + url : url;
    }

    boolean allowsDownload(String candidate, boolean accelerated, boolean fixture) {
        return allowsAsset(url, candidate, accelerated, fixture || privateSource);
    }

    static boolean allowsAsset(String original, String candidate, boolean accelerated, boolean exactOnly) {
        if (exactOnly) return original.equals(candidate);
        if (original.equals(candidate) || accelerated && ("https://gh-proxy.org/" + original).equals(candidate))
            return true;
        try {
            URI uri = URI.create(candidate);
            return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null && uri.getPort() == -1
                    && uri.getRawFragment() == null
                    && ("release-assets.githubusercontent.com".equals(uri.getHost())
                    || "objects.githubusercontent.com".equals(uri.getHost()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
