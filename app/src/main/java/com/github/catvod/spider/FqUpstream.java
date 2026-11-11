package com.github.catvod.spider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * fqnovel 上游直连层：设备自注册 + 签名 + 端点映射 + 播放直链。
 *
 * 完全不依赖原 APP：不读 127.0.0.1:18921、不用 Frida 桥、不读 APP 私有离线文件、
 * 不加载任何 native so。首次调用会纯网络注册一个设备身份（device_register），
 * 之后所有请求用 FqSign 自己算签名。
 *
 * 端点与参数来自 ch6vip/novel-sound (GPL-3.0) rust/src/endpoints|device/*.rs，
 * 已在 PC 上逐项实测通过。
 */
public final class FqUpstream {

    private static final String HOST = "https://api5-normal-sinfonlineb.fqnovel.com";
    private static final String HOST_VIDEO = "https://api3-normal-sinfonlinea.fqnovel.com";
    private static final String UA_DRAGON = "com.dragon.read";
    public static final String PLAY_UA = "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; "
            + "25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 "
            + "2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    private static final String UA_PHOENIX = PLAY_UA;

    private static final String SEARCH_SUFFIX =
            "&tab_name=store&pad_column_cover=0&is_first_enter_search=false"
                    + "&iid={iid}&device_id={did}&ac=wifi&channel=xiaomi_1967_64&aid=1967"
                    + "&app_name=novelapp&version_code=65132&version_name=6.5.1.32"
                    + "&device_platform=android&os=android&ssmix=a&device_type=FRD-AL10"
                    + "&device_brand=honor&language=zh&os_api=28&os_version=9"
                    + "&manifest_version_code=65132&resolution=1080%2A1920&dpi=480"
                    + "&update_version_code=65132&pv_player=65132&gender=2"
                    + "&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0"
                    + "&host_abi=arm64-v8a&dragon_device_type=phone"
                    + "&rom_version=FRD-AL10%2B8.0.0.556%28C00%29&compliance_status=0";
    private static final String DIRECTORY_SUFFIX =
            "&book_info_md5=&need_version=true&device_id={did}&ac=wifi"
                    + "&channel=xiaomi_1967_64&aid=1967&app_name=novelapp"
                    + "&version_code=65132&version_name=6.5.1.32&device_platform=android"
                    + "&os=android&ssmix=a&device_type=FRD-AL10&device_brand=honor"
                    + "&language=zh&os_api=28&os_version=9&manifest_version_code=65132"
                    + "&resolution=1080%2A1920&dpi=480&update_version_code=65132"
                    + "&pv_player=65132&=&need_personal_recommend=1&player_so_load=1"
                    + "&is_android_pad_screen=0&host_abi=arm64-v8a&dragon_device_type=phone"
                    + "&rom_version=FRD-AL10+8.0.0.556%28C00%29&compliance_status=0";
    private static final String VIDEO_MODEL_QUERY =
            "iid={iid}&device_id={did}&aid=1967&app_name=novelapp"
                    + "&version_code=72132&version_name=7.2.1.32"
                    + "&device_platform=android&device_brand=Xiaomi&os_version=13&cdid={cdid}";
    private static final String PHOENIX_QUERY =
            "iid={iid}&device_id={did}&ac=wifi&channel=update_64&aid=8662"
                    + "&app_name=novelread&version_code=71332&version_name=7.1.3.32"
                    + "&device_platform=android&os=android&ssmix=a&device_type=25053RT47C"
                    + "&device_brand=Redmi&language=zh&os_api=36&os_version=16"
                    + "&manifest_version_code=71332&resolution=1280*2772&dpi=520"
                    + "&update_version_code=71332&host_abi=arm64-v8a"
                    + "&dragon_device_type=phone&pv_player=71332&compliance_status=0"
                    + "&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0";

    /** phoenix multi_video_model/v1：唯一能产出 1080p 档位的入口（实测其 fallback_api 有 video_5 1080p）。 */
    private static final String PHOENIX_MULTI_QUERY =
            "iid={iid}&device_id={did}&ac=wifi&channel=update_64&aid=8662"
                    + "&app_name=novelread&version_code=71332&version_name=7.1.3.32"
                    + "&device_platform=android&os=android&ssmix=a&device_type=25053RT47C"
                    + "&device_brand=Redmi&language=zh&os_api=36&os_version=16"
                    + "&manifest_version_code=71332&resolution=1280*2772&dpi=520"
                    + "&update_version_code=71332&host_abi=arm64-v8a"
                    + "&dragon_device_type=phone&pv_player=71332&compliance_status=0"
                    + "&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0";
    /** fallback_api 是火山 unsigned GET，用这个 UA（Rust FALLBACK_USER_AGENT）。 */
    private static final String FALLBACK_UA =
            "com.xs.fm/632 (Linux; U; Android 15; zh_CN; 23049RAD8C; Build/AQ3A.250226.002; "
                    + "Cronet/TTNetVersion:fc4cebd3 2024-12-10 QuicVersion:d9628e3d 2024-10-11)";
    /** Rust video.rs SPADE_CONSTANTS：key_seed -> spade URL 的 AES 密钥推导常量。 */
    private static final byte[] SPADE_CONSTANTS = {
            77, (byte) 212, (byte) 194, (byte) 230, (byte) 184, 49, 98, 9, 14, 82,
            (byte) 179, (byte) 199, (byte) 166, 115, 59, (byte) 164, 28, (byte) 178,
            70, 43, (byte) 130, (byte) 154, (byte) 181, (byte) 138, 25, 107, 57,
            (byte) 219, 87, 23, 117, 36, (byte) 244, (byte) 155, (byte) 175, 127, 8,
            (byte) 232, (byte) 214, (byte) 141, 38, (byte) 167, 46, 55, (byte) 193,
            (byte) 169, 90, 47, 31, 5, (byte) 165, 24, (byte) 146, (byte) 174,
            (byte) 242, (byte) 148, (byte) 151, 50, (byte) 182, 42, 56, (byte) 170,
            (byte) 221, 88
    };

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 上游认可的设备身份；进程内缓存，也可由 ext 直接注入以免每次重注册。 */
    private static volatile Device DEVICE = null;
    private static String extDeviceId = "", extInstallId = "";

    static final class Device {
        String id, iid, cdid, openudid;
    }

    private FqUpstream() {}

    /** 可选：由站点 ext 注入已注册的设备身份，跳过注册请求。 */
    public static void configure(String deviceId, String installId) {
        extDeviceId = deviceId == null ? "" : deviceId.trim();
        extInstallId = installId == null ? "" : installId.trim();
    }

    public static synchronized Device device() {
        if (DEVICE != null) return DEVICE;
        Device d = new Device();
        if (extDeviceId.length() > 0 && extInstallId.length() > 0) {
            d.id = extDeviceId;
            d.iid = extInstallId;
        } else {
            Device reg = registerDevice();
            if (reg != null) d = reg;
        }
        if (d.id == null || d.id.length() == 0) {
            // 注册失败时仍可用随机身份访问大多数端点（搜索除外）。
            java.security.SecureRandom sr = new java.security.SecureRandom();
            d.id = Long.toString(1000000000000000000L + (sr.nextLong() & 0x7FFFFFFFFFFFFFFFL) % 8000000000000000000L);
            d.iid = Long.toString(1000000000L + (sr.nextLong() & 0x7FFFFFFFFFFFFFFFL) % 8000000000L);
        }
        if (d.cdid == null || d.cdid.length() == 0) d.cdid = UUID.randomUUID().toString();
        if (d.openudid == null || d.openudid.length() == 0) d.openudid = FqSign.hexOf(FqSign.md5(UUID.randomUUID().toString().getBytes(UTF8))).substring(0, 16);
        DEVICE = d;
        return d;
    }

    public static String statusJson() {
        Device d = device();
        try {
            return new JSONObject().put("mode", "standalone")
                    .put("registered", d != null)
                    .put("device_id", d == null ? "" : d.id)
                    .put("install_id", d == null ? "" : d.iid).toString();
        } catch (Exception e) { return "{\"mode\":\"standalone\"}"; }
    }

    // ------------------------------------------------------------ 端点映射

    /** 把旧 /api/v1/* 路径翻译成上游直连请求，返回与本地服务同构的 JSON 原文。 */
    public static String call(String localPath) {
        try {
            Device d = device();
            if (localPath.startsWith("/api/v1/recommend/channels")) return channels(d);
            if (localPath.startsWith("/api/v1/recommend/series-feed")) {
                return feed(d, queryParamRaw(localPath, "cell_id", "7294257141911650341"),
                        queryParamRaw(localPath, "offset", "0"));
            }
            if (localPath.startsWith("/api/v1/search")) {
                String q = queryParamRaw(localPath, "query", "");
                String off = queryParamRaw(localPath, "offset", "0");
                return search(d, q, off);
            }
            if (localPath.startsWith("/api/v1/books/")) {
                String rest = localPath.substring("/api/v1/books/".length());
                int slash = rest.indexOf('/');
                String sid = slash < 0 ? rest : rest.substring(0, slash);
                int q = sid.indexOf('?');
                if (q > 0) sid = sid.substring(0, q);
                return directory(d, sid);
            }
            if (localPath.startsWith("/api/v1/series/")) {
                String sid = localPath.substring("/api/v1/series/".length());
                int q = sid.indexOf('?');
                if (q > 0) sid = sid.substring(0, q);
                return videoDetail(d, sid);
            }
            // chapters / videos / danmaku：独立链路不再需要，播放直接走 video_model。
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String channels(Device d) throws Exception {
        LinkedHashMap<String, String> p = reading724(d);
        put(p, "tab_type", "16"); put(p, "last_tab_type", "-1");
        put(p, "client_template", "0"); put(p, "bottom_tab_type", "7");
        put(p, "landing_bottom_tab_type", "0"); put(p, "client_req_type", "3");
        put(p, "app_mode", "0"); put(p, "classic_tab_style", "v3");
        put(p, "lore_tab_style", "v5"); put(p, "migration_top_tab_enable", "false");
        put(p, "auth_aweme", "false"); put(p, "auth_backward", "true");
        put(p, "cold_start_is_double_gd", "false"); put(p, "after_genre_preference_popup", "0");
        put(p, "first_use_category_select", "false"); put(p, "current_name", "");
        put(p, "book_id", "0"); put(p, "last_tab_index", "0");
        put(p, "enable_search_box_collapse", "false"); put(p, "top_tab_extra", "");
        return get(HOST + "/reading/bookapi/bookmall/tab/v?" + encodeQuery(p), UA_DRAGON, null);
    }

    private static String feed(Device d, String cellId, String offset) throws Exception {
        LinkedHashMap<String, String> p = reading724(d);
        put(p, "cell_id", cellId); put(p, "tab_type", "16");
        put(p, "client_template", "2"); put(p, "client_req_type", "2");
        put(p, "unlimited_selector_change_type", "2"); put(p, "screen_width_px", "1281");
        put(p, "offset", offset == null || offset.length() == 0 ? "0" : offset);
        put(p, "video_type_preferences_str", "[]");
        put(p, "req_rank_algo", "101"); put(p, "device_level", "3");
        return get(HOST + "/reading/bookapi/bookmall/cell/change/v?" + encodeQuery(p), UA_DRAGON, null);
    }

    private static String search(Device d, String q, String offset) throws Exception {
        String url = HOST + "/reading/bookapi/search/tab/v?"
                + "bookshelf_search_plan=4&offset=" + goEscape(offset)
                + "&user_is_login=1&bookstore_tab=2&passback=" + goEscape(offset)
                + "&query=" + q + "&count=0&search_source=1"
                + "&clicked_content=search_history&use_lynx=false&use_correct=true&tab_type=1"
                + fillDevice(SEARCH_SUFFIX, d);
        return get(url, UA_DRAGON, null);
    }

    private static String directory(Device d, String sid) throws Exception {
        String url = HOST + "/reading/bookapi/directory/all_items/v?"
                + "book_type=0&item_data_list_md5=&catalog_data_md5=&book_id=" + goEscape(sid)
                + fillDevice(DIRECTORY_SUFFIX, d);
        return get(url, UA_DRAGON, null);
    }

    private static String videoDetail(Device d, String sid) throws Exception {
        String url = HOST + "/novel/player/video_detail/v1/?" + fillDevice(PHOENIX_QUERY, d);
        JSONObject body = new JSONObject().put("series_id", sid)
                .put("biz_param", new JSONObject()
                        .put("device_level", 3)
                        .put("need_all_video_definition", true)
                        .put("need_mp4_align", false)
                        .put("source", 4)
                        .put("use_os_player", false)
                        .put("video_id_type", 1));
        return post(url, body.toString().getBytes(UTF8), UA_PHOENIX,
                "application/json", phoenixHeaders());
    }

    // ------------------------------------------------------------ 播放

    /** video_model 选档结果：CDN 地址 + 是否 CENC 加密 + 解密 key + 实际清晰度。 */
    public static final class Play {
        public String url = "";
        public String backupUrl = "";   // 同档位不同 CDN 节点，主节点 connection reset 时重试
        public boolean encrypted = false;
        public byte[] key = null;
        public String definition = "";  // 360p/480p/540p/720p/1080p
        public long size = 0;           // 上游给出的密文体积，供缓存预算参考
        public String source = "";      // phoenix-fallback / shortplay
    }

    /** 兼容旧调用：只要 CDN 地址。 */
    public static String play(String itemId) {
        Play p = playInfo(itemId);
        return p == null ? "" : p.url;
    }

    /**
     * 取 1080p（首选），失败回落 shortplay 最高档。
     *
     * 实测结论（2026-11-11 纯 PC 直连）：
     *   shortplay video_model/v1 自身的 video_list 只有 360/480/540/720p，
     *   改 resolution / device_level / video_platform / definition 都解锁不了；
     *   1080p 只在 phoenix multi_video_model/v1 的 fallback_api 列表里
     *   （video_5 1080p 1080x1920 bytevc1 h265_hvc1 CENC，CDN Range 206 正常）。
     * 因此先走 phoenix；上游偶发返回火山空包时，退回 shortplay 保证一定能播。
     */
    public static Play playInfo(String itemId) {
        if (itemId == null || itemId.length() == 0) return null;
        Play hi = phoenix1080(itemId);
        if (hi != null && hi.url.length() > 0 && hi.key != null) return hi;
        Play low = shortplayBest(itemId);
        return low != null ? low : hi;
    }

    /** 原 shortplay video_model/v1 链路：取自带列表中可用的最高档（通常 720p）。 */
    private static Play shortplayBest(String itemId) {
        try {
            Device d = device();
            String url = HOST_VIDEO + "/novel/player/video_model/v1/?"
                    + fillDevice(VIDEO_MODEL_QUERY, d);
            JSONObject body = new JSONObject()
                    .put("video_id", itemId)
                    .put("content_type", 1004)
                    .put("biz_param", new JSONObject()
                            .put("detail_page_version", 0)
                            .put("device_level", 3)
                            .put("disable_digg_stat", false)
                            .put("disable_video_relate_book", false)
                            .put("from_video_id", "")
                            .put("need_all_video_definition", true)
                            .put("need_mp4_align", false)
                            .put("source", 4)
                            .put("use_os_player", false)
                            .put("use_server_dns", false)
                            .put("video_platform", 3));
            String raw = post(url, body.toString().getBytes(UTF8), UA_DRAGON,
                    "application/json; charset=utf-8", null);
            JSONObject root = new JSONObject(raw);
            String vm = root.optJSONObject("data") == null ? ""
                    : root.optJSONObject("data").optString("video_model", "");
            if (vm.length() == 0) return null;
            JSONObject model = new JSONObject(vm);
            JSONObject list = model.optJSONObject("video_list");
            if (list == null) return null;
            JSONObject best = null;
            int bestShort = -1;
            java.util.Iterator<String> keys = list.keys();
            while (keys.hasNext()) {
                JSONObject v = list.optJSONObject(keys.next());
                if (v == null) continue;
                if (v.optString("main_url", "").length() == 0
                        && v.optString("backup_url_1", "").length() == 0) continue;
                int w = v.optInt("vwidth", 0), h = v.optInt("vheight", 0);
                int shortEdge = w > 0 && h > 0 ? Math.min(w, h) : Math.max(w, h);
                if (shortEdge > bestShort) { bestShort = shortEdge; best = v; }
            }
            if (best == null) return null;
            String u = decodeVariantUrl(best.optString("main_url", ""));
            String bk = decodeVariantUrl(best.optString("backup_url_1", ""));
            if (!u.startsWith("http") && bk.startsWith("http")) {
                String t = u; u = bk; bk = t;
            }
            if (!u.startsWith("http")) return null;
            Play p = new Play();
            p.url = u;
            if (bk.startsWith("http") && !bk.equals(u)) p.backupUrl = bk;
            p.encrypted = best.optBoolean("encrypt", false) || best.optInt("encrypt", 0) != 0;
            if (p.encrypted) p.key = FqCenc.deriveKey(best.optString("spade_a", ""));
            p.definition = best.optString("definition", shortEdgeName(bestShort));
            p.size = best.optLong("size", 0L);
            p.source = "shortplay";
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static String shortEdgeName(int s) {
        if (s >= 1080) return "1080p";
        if (s >= 720) return "720p";
        if (s >= 540) return "540p";
        if (s >= 480) return "480p";
        return "360p";
    }

    /**
     * phoenix multi_video_model/v1 -> fallback_api -> video_info.data.video_list。
     * main_url 是 spade 密文（a8 00 01 00 头），必须用 key_seed 推导的 AES 密钥解开。
     */
    private static Play phoenix1080(String itemId) {
        try {
            Device d = device();
            String url = HOST + "/novel/player/multi_video_model/v1/?"
                    + fillDevice(PHOENIX_MULTI_QUERY, d);
            JSONObject body = new JSONObject()
                    .put("biz_param", new JSONObject()
                            .put("detail_page_version", 0)
                            .put("device_level", 3)
                            .put("disable_digg_stat", false)
                            .put("need_all_video_definition", true)
                            .put("need_mp4_align", false)
                            .put("use_os_player", false)
                            .put("use_server_dns", false)
                            .put("video_platform", 1024))
                    .put("mixed_video_id_map", new JSONObject()
                            .put("1004", new JSONArray().put(itemId)));
            String raw = post(url, body.toString().getBytes(UTF8), UA_PHOENIX,
                    "application/json", phoenixHeaders());
            JSONObject root = new JSONObject(raw);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;
            JSONObject model = findPhoenixModel(data);
            if (model == null) return null;
            String fb = fallbackApiUrl(model);
            if (!fb.startsWith("http")) return null;
            String fbRaw = unsignedGet(fb, FALLBACK_UA, 9000);
            JSONObject fbRoot = new JSONObject(fbRaw);
            JSONObject vi = fbRoot.optJSONObject("video_info");
            JSONObject fbData = vi == null ? null : vi.optJSONObject("data");
            if (fbData == null) return null;
            byte[] keySeed = FqSign.b64Decode(fbData.optString("key_seed", ""));
            JSONObject list = fbData.optJSONObject("video_list");
            if (list == null || keySeed.length == 0) return null;
            JSONObject best = pick1080(list);
            if (best == null) return null;
            String u = decodeSpadeOrPlain(best.optString("main_url", ""), keySeed);
            String bk = decodeSpadeOrPlain(best.optString("backup_url_1", ""), keySeed);
            if (!u.startsWith("http") && bk.startsWith("http")) {
                String t = u; u = bk; bk = t;
            }
            if (!u.startsWith("http")) return null;
            Play p = new Play();
            p.url = u;
            if (bk.startsWith("http") && !bk.equals(u)) p.backupUrl = bk;
            p.encrypted = best.optBoolean("encrypt", false) || best.optInt("encrypt", 0) != 0;
            if (p.encrypted) p.key = FqCenc.deriveKey(best.optString("spade_a", ""));
            p.definition = best.optString("definition", "");
            p.size = best.optLong("size", 0L);
            p.source = "phoenix-fallback";
            return p.key != null ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject findPhoenixModel(JSONObject data) {
        JSONObject direct = data.optJSONObject("video_model");
        if (direct != null) return direct;
        Object v = data.opt("video_model");
        if (v instanceof String && ((String) v).length() > 0) {
            try { return new JSONObject((String) v); } catch (Exception ignored) {}
        }
        java.util.Iterator<String> it = data.keys();
        while (it.hasNext()) {
            JSONObject one = data.optJSONObject(it.next());
            if (one == null) continue;
            JSONObject vm = one.optJSONObject("video_model");
            if (vm == null) {
                Object s = one.opt("video_model");
                if (s instanceof String && ((String) s).length() > 0) {
                    try { vm = new JSONObject((String) s); } catch (Exception ignored) {}
                }
            }
            if (vm != null) return vm;
        }
        return null;
    }

    private static String fallbackApiUrl(JSONObject model) {
        String u = "";
        Object f = model.opt("fallback_api");
        if (f instanceof JSONObject) {
            u = ((JSONObject) f).optString("fallback_api", "");
        } else if (f instanceof String) {
            String s = (String) f;
            try { u = new JSONObject(s).optString("fallback_api", ""); }
            catch (Exception ignored) {}
            if (u.length() == 0) u = s;
        }
        u = u.replace("&stream_type=audio_encrypt", "")
             .replace("?stream_type=audio_encrypt&", "?");
        return u.startsWith("http") ? u : "";
    }

    /** 选 1080p 档；没有 1080p 就返回 null，交给 shortplay 回落（避免拿到 bytevc2 低档）。 */
    private static JSONObject pick1080(JSONObject list) {
        JSONObject best = null;
        int bestShort = -1;
        java.util.Iterator<String> keys = list.keys();
        while (keys.hasNext()) {
            JSONObject v = list.optJSONObject(keys.next());
            if (v == null) continue;
            int w = v.optInt("vwidth", 0), h = v.optInt("vheight", 0);
            int shortEdge = w > 0 && h > 0 ? Math.min(w, h) : Math.max(w, h);
            String codec = (v.optString("codec_type", "") + " "
                    + v.optString("gear_des_key", "")).toLowerCase();
            if (codec.contains("bytevc2")) continue;   // 无解码器，Rust 同样丢弃
            boolean is1080 = "1080p".equalsIgnoreCase(v.optString("definition", ""))
                    || shortEdge >= 1080;
            if (is1080 && shortEdge > bestShort) { bestShort = shortEdge; best = v; }
        }
        return best;
    }

    private static String decodeSpadeOrPlain(String raw, byte[] keySeed) {
        if (raw == null || raw.length() == 0) return "";
        if (raw.startsWith("http")) return raw;
        String u = decryptSpadeUrl(raw, keySeed);
        if (!u.startsWith("http")) u = decodeVariantUrl(raw);
        if (!u.startsWith("http")) u = decodeVariantUrl(u);
        return u;
    }

    /** Rust decrypt_spade_url：SHA512(key_seed)+常量 -> SHA512 -> AES-128-CBC 解 URL。 */
    public static String decryptSpadeUrl(String value, byte[] keySeed) {
        if (value == null || value.length() == 0 || keySeed == null || keySeed.length == 0)
            return value == null ? "" : value;
        try {
            byte[] raw = FqSign.b64Decode(value);
            if (raw.length < 5 || (raw[0] & 255) != 168 || (raw[2] & 255) != 1
                    || (raw[3] & 255) != 0) return value;
            int len = (raw.length - 4) / 16 * 16;
            if (len <= 0) return value;
            byte[] cipherData = Arrays.copyOfRange(raw, 4, 4 + len);
            byte[] h1 = FqSign.sha512(keySeed);
            byte[] seed = Arrays.copyOf(h1, h1.length + SPADE_CONSTANTS.length);
            System.arraycopy(SPADE_CONSTANTS, 0, seed, h1.length, SPADE_CONSTANTS.length);
            byte[] h2 = FqSign.sha512(seed);
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(Arrays.copyOfRange(h2, 0, 16), "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(h2, 16, 32)));
            byte[] out = c.doFinal(cipherData);
            int pad = out.length > 0 ? (out[out.length - 1] & 255) : 0;
            int n = (pad >= 1 && pad <= 16) ? out.length - pad : out.length;
            while (n > 0 && out[n - 1] == 0) n--;
            return new String(out, 0, n, UTF8);
        } catch (Exception e) {
            return value;
        }
    }

    /** fallback_api 是 unsigned GET；绝不能带 Referer。 */
    private static String unsignedGet(String url, String ua, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(6000);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "application/json");
        return read(c);
    }

    /** main_url / backup_url_1 是 base64 包着的明文地址，只解一层。 */
    private static String decodeVariantUrl(String raw) {
        if (raw == null || raw.length() == 0) return "";
        if (raw.startsWith("http")) return raw;
        try {
            String dec = new String(FqSign.b64Decode(raw), UTF8);
            return dec.startsWith("http") ? dec : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------ 公共参数

    private static LinkedHashMap<String, String> reading724(Device d) {
        LinkedHashMap<String, String> p = new LinkedHashMap<String, String>();
        put(p, "iid", d.iid); put(p, "device_id", d.id); put(p, "ac", "wifi");
        put(p, "channel", "xiaomi_1967_64"); put(p, "aid", "1967"); put(p, "app_name", "novelapp");
        put(p, "version_code", "72432"); put(p, "version_name", "7.2.4.32");
        put(p, "device_platform", "android"); put(p, "os", "android"); put(p, "ssmix", "a");
        put(p, "device_type", "25053RT47C"); put(p, "device_brand", "Redmi");
        put(p, "language", "zh"); put(p, "os_api", "36"); put(p, "os_version", "16");
        put(p, "manifest_version_code", "72432"); put(p, "resolution", "1280*2620");
        put(p, "dpi", "520"); put(p, "update_version_code", "72432");
        put(p, "host_abi", "arm64-v8a"); put(p, "dragon_device_type", "phone");
        put(p, "pv_player", "72432"); put(p, "compliance_status", "0");
        put(p, "need_personal_recommend", "1"); put(p, "player_so_load", "1");
        put(p, "is_android_pad_screen", "0");
        put(p, "rom_version", "miui_V816_OS3.0.9.0.WOLCNXM");
        return p;
    }

    private static void put(LinkedHashMap<String, String> p, String k, String v) { p.put(k, v); }

    private static String fillDevice(String tpl, Device d) {
        return tpl.replace("{iid}", d.iid).replace("{did}", d.id)
                .replace("{cdid}", d.cdid).replace("{openudid}", d.openudid);
    }

    /** Go url.Values.Encode：键排序 + unreserved 集合 + 空格变 '+'。 */
    private static String encodeQuery(LinkedHashMap<String, String> p) {
        List<Map.Entry<String, String>> entries = new ArrayList<Map.Entry<String, String>>(p.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, String>>() {
            public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                return a.getKey().compareTo(b.getKey());
            }
        });
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : entries) {
            if (sb.length() > 0) sb.append('&');
            sb.append(goEscape(e.getKey())).append('=').append(goEscape(e.getValue()));
        }
        return sb.toString();
    }

    private static String goEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte bb : s.getBytes(UTF8)) {
            int b = bb & 255;
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '_' || b == '.' || b == '~') sb.append((char) b);
            else if (b == ' ') sb.append('+');
            else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((b >>> 4) & 15, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(b & 15, 16)));
            }
        }
        return sb.toString();
    }

    /** 取本地路径 query 中已编码的原始值（不再二次编码）。 */
    private static String queryParamRaw(String path, String name, String def) {
        int q = path.indexOf('?');
        if (q < 0) return def;
        for (String pair : path.substring(q + 1).split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            if (k.equals(name)) return i < 0 ? "" : pair.substring(i + 1);
        }
        return def;
    }

    private static Map<String, String> phoenixHeaders() {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        h.put("x-xs-from-web", "0");
        h.put("sdk-version", "2");
        return h;
    }

    // ------------------------------------------------------------ 签名 HTTP

    private static String get(String url, String ua, Map<String, String> extra) throws Exception {
        String query = queryOf(url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "application/json");
        applyHeaders(c, FqSign.headers(query, null, ""), extra);
        return read(c);
    }

    private static String post(String url, byte[] body, String ua, String contentType,
                               Map<String, String> extra) throws Exception {
        String query = queryOf(url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Content-Type", contentType);
        applyHeaders(c, FqSign.headers(query, body, ""), extra);
        OutputStream os = c.getOutputStream();
        os.write(body);
        os.close();
        return read(c);
    }

    private static void applyHeaders(HttpURLConnection c, Map<String, String> sign,
                                     Map<String, String> extra) {
        for (Map.Entry<String, String> e : sign.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
    }

    private static String queryOf(String url) {
        int i = url.indexOf('?');
        return i < 0 ? "" : url.substring(i + 1);
    }

    private static String read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
            c.disconnect();
        }
    }

    // ------------------------------------------------------------ 纯网络设备注册

    private static final byte[] TT_MAGIC = {0x74, 0x63, 0x05, 0x10, 0x00, 0x00};
    private static final byte[] TT_FIXED = FqSign.b64Decode(
            "TdTC5rgxYgkOUrPHpnM7pByyRiuCmrWKGWs521cXdST0m69/COjWjSanLjfBqVovHwWlGJKu8pSXMrYqOKrdWA==");

    private static Device registerDevice() {
        try {
            java.security.SecureRandom rnd = new java.security.SecureRandom();
            String cdid = UUID.randomUUID().toString();
            String openudid = FqSign.hexOf(FqSign.md5(UUID.randomUUID().toString().getBytes(UTF8))).substring(0, 16);
            String clientudid = UUID.randomUUID().toString();
            byte[] mac = new byte[6];
            rnd.nextBytes(mac);
            StringBuilder macStr = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) macStr.append(':');
                macStr.append(String.format("%02X", mac[i] & 255));
            }
            JSONObject header = new JSONObject()
                    .put("display_name", "番茄小说")
                    .put("update_version_code", "65132")
                    .put("manifest_version_code", "65132")
                    .put("aid", 1967)
                    .put("channel", "xiaomi_1967_64")
                    .put("package", "com.dragon.read")
                    .put("app_version", "6.5.1.32")
                    .put("version_code", "65132")
                    .put("sdk_version", "v04.04.05-ov-android")
                    .put("sdk_target_version", 29)
                    .put("os", "Android").put("os_version", "10").put("os_api", "29")
                    .put("device_model", "MI 12").put("device_brand", "Xiaomi")
                    .put("device_manufacturer", "Google").put("device_category", "phone")
                    .put("cpu_abi", "arm64-v8a")
                    .put("release_build", UUID.randomUUID().toString())
                    .put("density_dpi", "560").put("display_density", "mdpi")
                    .put("resolution", "1440x2392").put("language", "zh")
                    .put("mac", macStr.toString()).put("timezone", 8)
                    .put("access", "wifi").put("not_request_sender", 0)
                    .put("carrier", "CHINA MOBILE").put("mcc_mnc", "46007")
                    .put("rom", "EMUI-" + UUID.randomUUID().toString().replace("-", "").substring(0, 13))
                    .put("rom_version", UUID.randomUUID().toString().substring(0, 2))
                    .put("sig_hash", FqSign.hexOf(FqSign.md5(UUID.randomUUID().toString().getBytes(UTF8))))
                    .put("openudid", openudid).put("clientudid", clientudid)
                    .put("sim_serial_number", new JSONArray())
                    .put("region", "CN").put("tz_name", "Asia/Shanghai")
                    .put("tz_offset", 28800).put("sim_region", "cn");
            JSONObject payload = new JSONObject()
                    .put("magic_tag", "ss_app_log").put("header", header)
                    .put("_gen_time", System.currentTimeMillis());
            byte[] gz = gzip(payload.toString().getBytes(UTF8));
            byte[] encBody = ttEncrypt(gz);

            LinkedHashMap<String, String> p = new LinkedHashMap<String, String>();
            put(p, "tt_data", "a"); put(p, "os_api", "29"); put(p, "device_type", "MI 12");
            put(p, "ssmix", "a"); put(p, "manifest_version_code", "65132");
            put(p, "dpi", "560"); put(p, "version_name", "6.5.1.32");
            put(p, "ts", Long.toString(System.currentTimeMillis() / 1000L));
            put(p, "cpu_support64", "true"); put(p, "app_type", "normal");
            put(p, "appTheme", "light"); put(p, "ac", "wifi");
            put(p, "host_abi", "arm64-v8a"); put(p, "update_version_code", "65132");
            put(p, "channel", "xiaomi_1967_64");
            put(p, "_rticket", Long.toString(System.currentTimeMillis()));
            put(p, "device_platform", "android"); put(p, "version_code", "65132");
            put(p, "cdid", cdid); put(p, "os", "android"); put(p, "is_android_pad", "0");
            put(p, "openudid", openudid); put(p, "package", "com.dragon.read");
            put(p, "resolution", "1440*2392"); put(p, "os_version", "10");
            put(p, "language", "zh"); put(p, "device_brand", "Xiaomi");
            put(p, "need_personal_recommend", "1"); put(p, "aid", "1967");
            put(p, "minor_status", "0"); put(p, "app_name", "novel"); put(p, "mcc_mnc", "46007");

            String url = "https://log.snssdk.com/service/2/device_register/?" + encodeQuery(p);
            String ua = "com.dragon.read/65132 (Linux; U; Android 10; zh_CN; MI 12; "
                    + "Build/MMB29M; Cronet/TTNetVersion:9ac8d95c 2024-11-25 "
                    + "QuicVersion:3f326df4 2024-11-14)";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("content-type", "application/octet-stream;tt-data=a");
            c.setRequestProperty("user-agent", ua);
            c.setRequestProperty("host", "log.snssdk.com");
            c.setRequestProperty("connection", "Keep-Alive");
            OutputStream os = c.getOutputStream();
            os.write(encBody);
            os.close();
            String raw = read(c);
            JSONObject j = new JSONObject(raw);
            Device d = new Device();
            d.id = j.optString("device_id_str", j.optString("device_id", ""));
            d.iid = String.valueOf(j.opt("install_id_str"));
            if ("null".equals(d.iid) || d.iid.length() == 0) d.iid = String.valueOf(j.opt("install_id"));
            d.cdid = cdid;
            d.openudid = openudid;
            return d.id.length() > 0 && !"null".equals(d.iid) ? d : null;
        } catch (Exception e) {
            System.err.println("[FqUpstream] device_register failed: " + e);
            return null;
        }
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(bos);
        gz.write(data);
        gz.close();
        return bos.toByteArray();
    }

    /** key/iv = SHA512(SHA512(rnd32) + fixed)；body = magic + rnd32 + AES-CBC(PKCS7(SHA512(gz)+gz))。 */
    private static byte[] ttEncrypt(byte[] data) throws Exception {
        byte[] rnd32 = new byte[32];
        new java.security.SecureRandom().nextBytes(rnd32);
        byte[] inner = FqSign.sha512(rnd32);
        byte[] mix = new byte[inner.length + TT_FIXED.length];
        System.arraycopy(inner, 0, mix, 0, inner.length);
        System.arraycopy(TT_FIXED, 0, mix, inner.length, TT_FIXED.length);
        byte[] hv = FqSign.sha512(mix);
        byte[] digest = FqSign.sha512(data);
        byte[] plain = new byte[digest.length + data.length];
        System.arraycopy(digest, 0, plain, 0, digest.length);
        System.arraycopy(data, 0, plain, digest.length, data.length);
        int pad = 16 - (plain.length % 16);
        byte[] padded = new byte[plain.length + pad];
        System.arraycopy(plain, 0, padded, 0, plain.length);
        java.util.Arrays.fill(padded, plain.length, padded.length, (byte) pad);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(java.util.Arrays.copyOfRange(hv, 0, 16), "AES"),
                new IvParameterSpec(java.util.Arrays.copyOfRange(hv, 16, 32)));
        byte[] enc = cipher.doFinal(padded);
        byte[] out = new byte[TT_MAGIC.length + 32 + enc.length];
        System.arraycopy(TT_MAGIC, 0, out, 0, TT_MAGIC.length);
        System.arraycopy(rnd32, 0, out, TT_MAGIC.length, 32);
        System.arraycopy(enc, 0, out, TT_MAGIC.length + 32, enc.length);
        return out;
    }
}
