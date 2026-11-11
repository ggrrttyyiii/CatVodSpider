package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 什锦铺短剧 / fqapp 系客户端 -> TVBox(FongMi / CatVod) Java 爬虫。
 *
 * 数据来源：fqnovel 上游 HTTPS 接口，纯 Java 直连，不依赖原 APP。
 * 设备身份由 FqUpstream 纯网络注册（device_register），签名由 FqSign 本地计算
 * （x-khronos / x-ladon / x-argus / x-ss-stub），播放走 video_model/v1 POST 取 CDN 直链。
 * 不再读取 127.0.0.1:18921、不加载 native so、不需要 Frida 桥、不读 APP 私有离线文件。
 *
 * ext 可选：deviceId / installId（注入已注册设备，跳过注册）、limit、cellXxx。
 * 旧参数 apiBase / playApi / playBridge / fileRoot 保留为可选回退，默认完全不用。
 */
public class Shipu extends Spider {

    private static final String DEFAULT_BASE = "http://127.0.0.1:18921";
    private static final String UA = "com.fqapp.fqapp/1.0 (Linux; Android 10; TVBox)";
    private static final String[][] CATEGORIES = {
            {"recommend", "推荐"}, {"manju", "漫剧"}
    };
    /** 播放地址常见字段，按优先级匹配。 */
    private static final String[] URL_KEYS = {
            "main_url", "video_url", "play_url", "main_play_url", "backup_url_1",
            "backup_url_2", "backup_url", "download_url", "url", "play_addr",
            "hls_url", "mp4_url"
    };
    /** feed 一次只回少量剧，用短剧高频词补出可浏览的列表。
     *  实测每个词能带来 8~18 张不重复卡片，词表越大，分类可翻的页数越深。 */
    private static final String[] WORDS = {
            "总裁", "重生", "逆袭", "战神", "穿越", "甜宠", "复仇", "龙王", "豪门", "萌宝",
            "都市", "悬疑", "系统", "赘婿", "神医", "女频", "虐恋", "闪婚", "千金", "校园",
            "宫斗", "权谋", "末世", "灵异", "年代", "种田", "空间", "异能", "仙侠", "武侠",
            "兵王", "至尊", "废柴", "打脸", "马甲", "少奶奶", "真假千金", "先婚后爱", "带球跑", "隐婚"
    };
    private static final int MAX_CARDS = 48;
    /** 真分页：每页 20 条；卡片池跨请求累积，翻页时继续从源队列补充。 */
    private static final int PAGE_SIZE = 20;
    private static final int POOL_MAX = 600;
    /** 补池并发数：每个上游源约 1.5~2.5s，8 路能保证连续翻页时池子始终够用。 */
    private static final int POOL_WORKERS = 8;
    /** 单次 categoryContent 最长等待（毫秒），必须小于 TVBox 的请求超时。 */
    private static final long POOL_WAIT_MS = 9000L;
    /** -Dfq.pool.debug=true 时打印每个源的实际产出，便于排查上游空包/限流。 */
    private static final boolean POOL_DEBUG = Boolean.getBoolean("fq.pool.debug");
    /** 漫剧 / 二次元热词：排在漫剧分类前面，且与 WORDS 基本不重叠，避免重复请求。 */
    private static final String[] MANJU_WORDS = {
            "漫剧", "动漫", "国漫", "动态漫", "沙雕动画", "二次元", "玄幻", "修仙", "热血", "搞笑",
            "脑洞", "奇幻", "都市异能", "修真", "仙途", "妖怪", "机甲", "末日", "推理", "古风",
            "恋爱", "冒险", "战斗", "卡牌", "兽世", "快穿", "穿书", "女尊", "长生", "灵气复苏"
    };

    private String apiBase = DEFAULT_BASE;
    private String cellRecommend = "7294257141911650341";
    private String cellDrama = "8";
    private String cellManju = "24";
    private String cellRank = "7098235271900037133";
    private String playApi = "";
    private String playBridge = "";
    private String fileRoot = "";
    private int filePort = 18923;
    private String syncCmd = "";
    private int limit = 20;
    private boolean danmaku = false;

    /** 列表->详情的卡片缓存，避免详情接口失败时丢数据。 */
    private final Map<String, JSONObject> cards =
            Collections.synchronizedMap(new LinkedHashMap<String, JSONObject>());
    /** item_id -> 真实视频 vid(v02...) 缓存。 */
    private final Map<String, String> realVids = new HashMap<String, String>();
    /** 响应缓存（path -> 原文）。静态跨 Spider 实例共享：TVBox 切分类会重建实例，
     *  实例级缓存会失效，导致每个分类都要重新串行拉 12 个慢接口而超时。 */
    private static final Map<String, String> CACHE =
            Collections.synchronizedMap(new HashMap<String, String>());
    /** sid -> 按集号排列的 item_id 列表。静态跨 Spider 实例共享，供播放时预取下一集。 */
    private static final Map<String, List<String>> EPISODES =
            Collections.synchronizedMap(new LinkedHashMap<String, List<String>>());

    private static void rememberEpisodes(String sid, List<String> ids) {
        if (sid == null || sid.length() == 0 || ids == null || ids.isEmpty()) return;
        synchronized (EPISODES) {
            EPISODES.put(sid, new ArrayList<String>(ids));
            while (EPISODES.size() > 32) {
                String oldest = EPISODES.keySet().iterator().next();
                EPISODES.remove(oldest);
            }
        }
    }

    /** tid -> 累积卡片池。静态跨 Spider 实例：TVBox 切页重建实例也不会丢翻页进度。 */
    private static final Map<String, Pool> POOLS =
            Collections.synchronizedMap(new HashMap<String, Pool>());

    private static final class Pool {
        /** 累积卡片，按插入顺序切片；LinkedHashMap 保证跨页不重复、不乱序。 */
        final LinkedHashMap<String, JSONObject> cards = new LinkedHashMap<String, JSONObject>();
        final ArrayList<String> sources = new ArrayList<String>();
        int next = 0;                 // 下一个待领取的源下标（worker 在 p 锁内串行领取）
        int done = 0;                 // 已处理完成的源数（含空包/失败）
        volatile boolean warming = false;
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void init(Context context) throws Exception {
        init(context, "");
    }

    @Override
    public void init(Context context, String ext) throws Exception {
        JSONObject cfg = new JSONObject();
        if (ext != null) {
            String s = ext.trim();
            if (s.startsWith("{")) {
                cfg = new JSONObject(s);
            } else if (s.startsWith("http")) {
                cfg.put("apiBase", s);
            }
        }
        apiBase = trimEnd(cfg.optString("apiBase", DEFAULT_BASE));
        if (apiBase.length() == 0) apiBase = DEFAULT_BASE;
        cellRecommend = cfg.optString("cellRecommend", cellRecommend);
        cellDrama = cfg.optString("cellDrama", cellDrama);
        cellManju = cfg.optString("cellManju", cellManju);
        cellRank = cfg.optString("cellRank", cellRank);
        playApi = cfg.optString("playApi", "");
        playBridge = trimEnd(cfg.optString("playBridge", ""));
        fileRoot = cfg.optString("fileRoot", "");
        filePort = cfg.optInt("filePort", 18923);
        syncCmd = cfg.optString("syncCmd", "");
        limit = cfg.optInt("limit", 20);
        if (limit < 6) limit = 6;
        if (limit > 60) limit = 60;
        danmaku = cfg.optBoolean("danmaku", false);
        FqUpstream.configure(cfg.optString("deviceId", ""), cfg.optString("installId", ""));
        cards.clear();
        realVids.clear();
    }

    @Override
    public void destroy() {
        cards.clear();
        realVids.clear();
    }

    /** 自检：ping 看服务是否在线；probe 逐个试探播放端点，便于抓到新协议后配 playApi。 */
    @Override
    public String action(String action) {
        try {
            if ("probe".equals(action)) return probe();
            String body = getQuiet("/api/v1/recommend/channels");
            return new JSONObject()
                    .put("mode", "standalone")
                    .put("device", new JSONObject(FqUpstream.statusJson()))
                    .put("online", body.length() > 0)
                    .put("bytes", body.length())
                    .put("tip", body.length() == 0 ? "上游不可达" : "独立直连正常")
                    .toString();
        } catch (Exception e) {
            // action() 覆写基类且基类未声明 throws；这里只能就地兜住 JSONException。
            try {
                return new JSONObject().put("online", false)
                        .put("error", String.valueOf(e)).toString();
            } catch (Exception ignored) {
                return "{\"online\":false}";
            }
        }
    }

    private String probe() throws Exception {
        JSONArray items = new JSONArray();
        String item = firstItemId();
        String rv = realVid(item);
        String[] paths = {
                "/api/v1/recommend/channels",
                "/api/v1/recommend/series-feed?cell_id=" + enc(cellDrama),
                "/api/v1/series/" + enc(firstSeriesId()),
                "/api/v1/chapters/" + enc(item),
                "/api/v1/videos/" + enc(rv.length() > 0 ? rv : item)
        };
        for (String p : paths) {
            String body = getQuiet(p);
            items.put(new JSONObject().put("path", p).put("bytes", body.length())
                    .put("sample", body.length() == 0 ? "" : body.substring(0, Math.min(160, body.length()))));
        }
        return new JSONObject()
                .put("apiBase", apiBase)
                .put("realVid", rv)
                .put("playApi", playApi)
                .put("playBridge", playBridge)
                .put("fileRoot", fileRoot)
                .put("fileServer", FILE_SERVER != null && FILE_SERVER.alive)
                .put("probe", items).toString();
    }

    private String firstSeriesId() throws Exception {
        if (!cards.isEmpty()) return cards.keySet().iterator().next();
        LinkedHashMap<String, JSONObject> found = feedCards(cellDrama);
        return found.isEmpty() ? "0" : found.keySet().iterator().next();
    }

    private String firstItemId() throws Exception {
        String sid = firstSeriesId();
        JSONObject info = parseQuiet(getQuiet("/api/v1/series/" + enc(sid)));
        if (info != null) {
            JSONObject data = info.optJSONObject("data");
            JSONObject vd = data == null ? null : data.optJSONObject("video_data");
            JSONArray eps = vd == null ? null : vd.optJSONArray("video_list");
            if (eps != null && eps.length() > 0) {
                String vid = str(eps.optJSONObject(0), "vid");
                if (vid.length() > 0) return vid;
            }
        }
        return "0";
    }

    // ---------------------------------------------------------------- 首页

    @Override
    public String homeContent(boolean filter) throws Exception {
        JSONArray clazz = new JSONArray();
        for (String[] c : CATEGORIES) {
            clazz.put(new JSONObject().put("type_id", c[0]).put("type_name", c[1]));
        }
        JSONObject result = new JSONObject().put("class", clazz);
        // 只留两个分类：不再下发「每页」筛选，避免 TVBox 按 limit 生成翻页入口。
        if (filter) result.put("filters", new JSONObject());
        // 一进首页就后台补两个分类的卡片池，用户点进分类时翻页几乎零等待。
        poolOf("recommend");
        poolOf("manju");
        return result.toString();
    }

    @Override
    public String homeVideoContent() throws Exception {
        LinkedHashMap<String, JSONObject> found = discoverCards("recommend");
        JSONArray list = toArray(found, MAX_CARDS);
        return pageResult(list, 1, 1, Math.max(1, list.length())).toString();
    }

    // ---------------------------------------------------------------- 分类 / 真分页

    /**
     * 上游 feed 的 offset 只对推荐 cell 有效（漫剧 cell=24 在 offset>0 返回 436B 空包），
     * 所以真分页不能靠单接口翻页，改成「多源累积卡片池」：
     *   4 个 cell feed + channels + 20 个热词搜索 + 搜索 offset 变体，纯 PC 实测可累积
     *   431 张不重复卡片（约 22 页）；池按 tid 静态累积，翻到哪页就补到哪页够用；
     *   total 返回池内总数，源没耗尽时至少给 page+1，耗尽后不再造空页
     *   （原实现把 total 当页条数、还用 pagecount=page 兜底，才是「翻不动 / 空页」的根因）。
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter,
                                  HashMap<String, String> extend) throws Exception {
        int page = parseInt(pg, 1);
        Pool p = poolOf(tid);
        // 后台 worker 持续把源结果并进池；这里只等到本页够用（wait 会释放锁）。
        fill(p, page * PAGE_SIZE, System.currentTimeMillis() + POOL_WAIT_MS);
        JSONArray list = new JSONArray();
        int pagecount, total;
        synchronized (p) {
            List<JSONObject> all = new ArrayList<JSONObject>(p.cards.values());
            int from = (page - 1) * PAGE_SIZE;
            for (int i = from; i < all.size() && list.length() < PAGE_SIZE; i++) {
                list.put(all.get(i));
            }
            total = p.cards.size();
            boolean more = p.done < p.sources.size() && total < POOL_MAX;
            pagecount = (total + PAGE_SIZE - 1) / PAGE_SIZE;
            if (pagecount < 1) pagecount = 1;
            if (more && pagecount <= page) pagecount = page + 1;
            if (pagecount < page) pagecount = page;
        }
        return pageResult(list, page, pagecount, PAGE_SIZE, total).toString();
    }

    /** 取（或建）分类池；建池时把全部内容源排队并启动后台预热。 */
    private Pool poolOf(String tid) {
        synchronized (POOLS) {
            Pool p = POOLS.get(tid);
            if (p == null) {
                p = new Pool();
                p.sources.addAll(buildSources(tid));
                POOLS.put(tid, p);
                startPrewarm(p);
            }
            return p;
        }
    }

    /**
     * 源队列按「实测产出」排序：搜索热词每个约 8~18 张新卡，feed / channels 基本 0~5 张，
     * offset 变体多数被上游去重。所以把高产出搜索放在最前面，保证前几页瞬间凑满；
     * 低产出的 feed / offset 放后面，只用于撑更深的页数。
     */
    private List<String> buildSources(String tid) {
        ArrayList<String> s = new ArrayList<String>();
        boolean manju = "manju".equals(tid);
        String[] first = manju ? MANJU_WORDS : WORDS;
        String[] second = manju ? WORDS : MANJU_WORDS;
        String main = cellOf(tid);
        s.add(feedPath(main, 0));
        s.add("/api/v1/recommend/channels");
        for (String w : first) s.add(searchPath(w, 0));
        for (String w : second) s.add(searchPath(w, 0));
        String[] cells = {cellRecommend, cellDrama, cellManju, cellRank};
        for (String c : cells) if (!c.equals(main)) s.add(feedPath(c, 0));
        for (int off : new int[]{10, 20, 30, 40, 50}) {
            for (int i = 0; i < 8 && i < first.length; i++) {
                s.add(searchPath(first[i], off));
            }
        }
        return s;
    }

    private String feedPath(String cell, int offset) {
        String p = "/api/v1/recommend/series-feed?cell_id=" + enc(cell);
        return offset > 0 ? p + "&offset=" + offset : p;
    }

    private String searchPath(String word, int offset) {
        String p = "/api/v1/search?query=" + enc(word);
        return offset > 0 ? p + "&offset=" + offset : p;
    }

    /**
     * 只等待、不发网络请求：补池完全交给 startPrewarm 的 worker。
     * 旧实现在 fill 里发一批请求并 join 3 秒，超时后源下标已推进而结果没并进池，
     * 这些卡片就被永久丢弃 —— 正是漫剧第 8 页只剩 5 条的根因。
     */
    private void fill(Pool p, int need, long deadline) {
        synchronized (p) {
            while (p.cards.size() < need && p.done < p.sources.size()
                    && p.cards.size() < POOL_MAX) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    p.wait(Math.min(left, 150L));
                } catch (InterruptedException ignored) { }
            }
        }
    }

    /**
     * N 个 worker 从源队列串行领取、并行请求，完成后直接把卡片并进 p.cards 并唤醒等待者。
     * 结果不因某次翻页超时而丢失，单个慢源也不会挡住后面的源。
     */
    private void startPrewarm(final Pool p) {
        if (p.warming) return;
        p.warming = true;
        final Shipu self = this;
        for (int w = 0; w < POOL_WORKERS; w++) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    while (true) {
                        String path;
                        synchronized (p) {
                            if (p.next >= p.sources.size()) return;
                            path = p.sources.get(p.next++);
                        }
                        String body = "";
                        try {
                            body = self.fetch(path);
                        } catch (Exception ignored) { }
                        synchronized (p) {
                            int before = p.cards.size();
                            try {
                                if (body != null && body.length() > 0) {
                                    self.collectCards(self.parseQuiet(body), p.cards, 0);
                                }
                            } catch (Exception ignored) { }
                            if (POOL_DEBUG) {
                                System.out.println("POOL src=" + path + " bytes="
                                        + (body == null ? 0 : body.length()) + " new="
                                        + (p.cards.size() - before) + " total=" + p.cards.size());
                            }
                            p.done++;
                            p.notifyAll();
                        }
                    }
                }
            }, "fq-pool-" + w);
            t.setDaemon(true);
            t.start();
        }
    }

    /** 汇总可浏览的短剧卡片。
     *  APP 的每个 /api/v1/* 都要 1.8~2.9 秒（Rust 后端需签名上游），原先串行 12 个
     *  要 11 秒以上，会被 TVBox 默认超时丢掉，表现就是「分类无数据」。
     *  现在首屏只并行发 4 个请求（feed + channels + 2 个热词），约 3 秒返回；
     *  其余热词由后台线程预热进静态缓存，切分类 / 翻页直接命中缓存。 */
    private LinkedHashMap<String, JSONObject> discoverCards(String tid) throws Exception {
        int offset = wordOffset(tid);
        final String[] paths = {
                "/api/v1/recommend/series-feed?cell_id=" + enc(cellOf(tid)),
                "/api/v1/recommend/channels",
                "/api/v1/search?query=" + enc(WORDS[offset % WORDS.length]),
                "/api/v1/search?query=" + enc(WORDS[(offset + 1) % WORDS.length])
        };
        final String[] bodies = new String[paths.length];
        Thread[] ts = new Thread[paths.length];
        for (int i = 0; i < paths.length; i++) {
            final int idx = i;
            final String p = paths[i];
            ts[i] = new Thread(new Runnable() {
                public void run() { bodies[idx] = fetch(p); }
            });
            ts[i].setDaemon(true);
            ts[i].start();
        }
        long deadline = System.currentTimeMillis() + 9000;
        for (Thread t : ts) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try { t.join(left); } catch (InterruptedException ignored) { }
        }

        LinkedHashMap<String, JSONObject> found = new LinkedHashMap<String, JSONObject>();
        for (String body : bodies) {
            if (body != null && body.length() > 0) collectCards(parseQuiet(body), found, 0);
        }
        // 已预热好的热词结果直接并进来，不再为凑数发任何网络请求
        for (int i = 0; i < WORDS.length && found.size() < MAX_CARDS; i++) {
            String w = WORDS[(i + offset) % WORDS.length];
            String hit = CACHE.get("/api/v1/search?query=" + enc(w));
            if (hit != null && hit.length() > 0) collectCards(parseQuiet(hit), found, 0);
        }
        // 其余热词由分类池的后台 worker 统一预热进静态 CACHE，首页不再单独发请求。
        return found;
    }

    /** 带缓存的 GET；path 即 key，静态缓存跨 Spider 实例复用。 */
    private String fetch(String path) {
        String hit = CACHE.get(path);
        if (hit != null && hit.length() > 0) return hit;
        String body = getQuiet(path);
        if (body.length() > 0) CACHE.put(path, body);
        return body;
    }

    private int wordOffset(String tid) {
        if ("drama".equals(tid)) return 2;
        if ("manju".equals(tid)) return 4;
        if ("rank".equals(tid)) return 6;
        return 0;
    }

    /** 拉一次 feed（带缓存），抽出全部短剧卡片；接口不认 offset/count，所以分页在客户端做。 */
    private LinkedHashMap<String, JSONObject> feedCards(String cell) throws Exception {
        LinkedHashMap<String, JSONObject> found = new LinkedHashMap<String, JSONObject>();
        collectCards(parseQuiet(fetch("/api/v1/recommend/series-feed?cell_id=" + enc(cell))), found, 0);
        return found;
    }

    // ---------------------------------------------------------------- 详情

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return emptyList();
        String sid = ids.get(0);
        JSONObject info = null;

        String body = getQuiet("/api/v1/series/" + enc(sid));
        JSONObject root = parseQuiet(body);
        if (root != null) {
            JSONObject data = root.optJSONObject("data");
            if (data != null) info = data.optJSONObject("video_data");
        }
        if (info == null || info.length() == 0) info = cards.get(sid);
        if (info == null) info = new JSONObject();

        JSONArray episodes = info.optJSONArray("video_list");
        if (episodes == null || episodes.length() == 0) {
            episodes = directoryEpisodes(sid);
        }

        StringBuilder playUrl = new StringBuilder();
        ArrayList<String> vids = new ArrayList<String>();
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject ep = episodes.optJSONObject(i);
            if (ep == null) continue;
            String vid = str(ep, "vid");
            if (vid.length() == 0) vid = str(ep, "item_id");
            if (vid.length() == 0) continue;
            int index = parseInt(str(ep, "vid_index"), i + 1);
            if (index > 0 && index <= 2000) {
                while (vids.size() < index) vids.add("");
                vids.set(index - 1, vid);
            }
            String name = clean("第" + index + "集");
            if (playUrl.length() > 0) playUrl.append("#");
            playUrl.append(name).append("$").append(sid).append("::").append(vid)
                    .append("::").append(index);
        }
        rememberEpisodes(sid, vids);

        String name = first(info, "series_title", "title", "raw_book_name");
        if (name.length() == 0) name = sid;
        String pic = first(info, "series_cover", "cover", "thumb_url");
        String intro = first(info, "series_intro", "video_desc", "sub_title");
        String cnt = first(info, "episode_cnt", "episode");

        JSONObject vod = new JSONObject()
                .put("vod_id", sid)
                .put("vod_name", name)
                .put("vod_pic", pic)
                .put("vod_content", intro)
                .put("vod_remarks", cnt.length() > 0 ? "共" + cnt + "集" : "短剧")
                .put("type_name", "短剧")
                .put("vod_play_from", "短剧")
                .put("vod_play_url", playUrl.toString());
        return new JSONObject().put("list", new JSONArray().put(vod)).toString();
    }

    /** 分集回退：/api/v1/books/{series_id}/directory 的 item_data_list 就是每集 id。 */
    private JSONArray directoryEpisodes(String sid) throws Exception {
        JSONArray out = new JSONArray();
        JSONObject root = parseQuiet(getQuiet("/api/v1/books/" + enc(sid) + "/directory"));
        if (root == null) return out;
        JSONObject data = root.optJSONObject("data");
        if (data == null) return out;
        JSONArray items = data.optJSONArray("item_data_list");
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            out.put(new JSONObject()
                    .put("vid", str(it, "item_id"))
                    .put("vid_index", i + 1)
                    .put("title", str(it, "title")));
        }
        return out;
    }

    // ---------------------------------------------------------------- 搜索

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = parseInt(pg, 1);
        if (page > 1) return pageResult(new JSONArray(), page, 1, 1).toString();
        String path = "/api/v1/search?query=" + enc(key == null ? "" : key);
        JSONObject root = parseQuiet(getQuiet(path));
        LinkedHashMap<String, JSONObject> found = new LinkedHashMap<String, JSONObject>();
        collectCards(root, found, 0);
        JSONArray list = toArray(found, MAX_CARDS);
        return pageResult(list, 1, 1, Math.max(1, list.length())).toString();
    }

    // ---------------------------------------------------------------- 播放

    /** 分集 id 格式：sid::item_id::集号（兼容旧的两段 sid::item_id）。 */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        String sid = "", item = "", ep = "1";
        if (id != null) {
            String[] p = id.split("::");
            if (p.length >= 2) {
                sid = p[0];
                item = p[1];
            } else {
                item = id;
            }
            if (p.length >= 3) ep = p[2];
        }
        String url = resolvePlayUrl(sid, item, ep);
        if (url.length() == 0) {
            throw new Exception("取不到播放地址：" + item
                    + "（独立直连 video_model 未返回可用地址）");
        }
        // 当前集已发布完成，再后台准备下一集：既不与起播下载抢 CDN 连接，
        // 又远早于用户看完本集，切集时直接命中缓存。
        prefetchNext(sid, ep, item);

        // 本地明文服务不需要任何请求头；CDN 兜底时也必须不带 Referer：
        // CDN 的 nginx 是「见 Referer 即 403」（实测与 UA 无关）。
        JSONObject hdr = new JSONObject();
        if (!url.startsWith("http://127.0.0.1:")) {
            hdr.put("User-Agent", FqUpstream.PLAY_UA);
        }
        JSONObject result = new JSONObject()
                .put("parse", 0)
                .put("jx", 0)
                .put("url", url)
                .put("header", hdr);
        return result.toString();
    }

    /**
     * 后台预取下一集。分集表来自 detailContent（正常播放路径一定有）；
     * 从历史/收藏直接播放而没有分集表时，后台补拉一次目录，不阻塞当前播放。
     */
    private void prefetchNext(final String sid, final String ep, final String curItem) {
        final int idx = parseInt(ep, 1);
        List<String> vids = sid.length() > 0 ? EPISODES.get(sid) : null;
        if (vids != null) {
            if (idx >= 1 && idx < vids.size()) {
                String next = vids.get(idx);   // 0-based：idx 即第 idx+1 集
                if (next != null && next.length() > 0 && !next.equals(curItem)) {
                    FqCenc.prefetch(next);
                }
            }
            return;
        }
        if (sid.length() == 0) return;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    JSONArray eps = directoryEpisodes(sid);
                    ArrayList<String> ids = new ArrayList<String>();
                    for (int i = 0; i < eps.length(); i++) {
                        JSONObject e = eps.optJSONObject(i);
                        ids.add(e == null ? "" : str(e, "vid"));
                    }
                    rememberEpisodes(sid, ids);
                    if (idx >= 1 && idx < ids.size()) {
                        String next = ids.get(idx);
                        if (next != null && next.length() > 0) FqCenc.prefetch(next);
                    }
                } catch (Exception ignored) {
                }
            }
        }, "fq-episodes");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 默认（独立直连）：优先 phoenix 1080p -> CENC 解密 -> 本地明文 HTTP 服务。
     * 以下为可选兼容回退，只有独立链路完全取不到地址时才尝试：
     * 1) playApi    自定义模板，支持 {sid} {item} {vid} {ep} {rv}
     * 2) playBridge Frida 桥服务，返回真实 CDN 明文 mp4 直链
     * 3) 原生链    chapters/{item} -> v02 vid -> /api/v1/videos/{v02} -> main_url
     * 4) 数字 id   /api/v1/videos/{item}（旧协议，多数版本会断连）
     * 5) fileRoot  APP 离线缓存的明文 mp4，经内置 HTTP 服务（支持 Range）交给播放器
     */
    private String resolvePlayUrl(String sid, String item, String ep) {
        String cached = FqCenc.cachedUrl(item);
        if (cached.length() > 0) return cached;   // 预取已完成：切集零网络等待
        // 独立链路：优先 phoenix fallback 的 1080p，失败回落 shortplay 最高档。
        // 拿到的是 cenc-aes-ctr 加密 MP4，必须先下载并由 FqCenc 还原成明文，
        // 再由进程内 127.0.0.1 服务提供给播放器（同时绕开 CDN 的 Referer 防盗链）。
        FqUpstream.Play info = FqUpstream.playInfo(item);
        if (info != null && info.url.length() > 0) {
            if (info.encrypted && info.key != null) {
                String local = FqCenc.publish(info.url, info.key, item);
                if (local.length() == 0 && info.backupUrl.length() > 0) {
                    // 某些 CDN 节点会偶发 connection reset，同档位备用线路是同一份密文，
                    // content key 与 IV 完全通用，直接换节点重试。
                    local = FqCenc.publish(info.backupUrl, info.key, item);
                }
                if (local.length() > 0) return local;
            }
            return info.url;   // 未加密或两条线路都失败时兜底（仍不带 Referer）
        }
        if (playApi.length() > 0) {
            String u = fetchPlayUrl(fill(playApi, sid, item, ep, realVid(item)));
            if (u.length() > 0) return u;
        }
        // 本地明文 mp4 优先：零网络、已验证可直接播放，避免每条网络通道的等待。
        String local = offlineFile(item);
        if (local.length() > 0) return local;
        String rv = realVid(item);
        if (playBridge.length() > 0) {
            String q = playBridge + (playBridge.contains("?") ? "&" : "?")
                    + "sid=" + enc(sid) + "&item=" + enc(item) + "&ep=" + enc(ep)
                    + "&rv=" + enc(rv);
            String u = fetchPlayUrl(q);
            if (u.length() > 0) return u;
        }
        if (rv.length() > 0) {
            String[] paths = {
                    "/api/v1/videos/" + enc(rv) + "?series_id=" + enc(sid)
                            + "&video_model=1&need_all_video_definition=1&need_mp4_align=1"
                            + "&use_os_player=1&video_platform=3",
                    "/api/v1/videos/" + enc(rv) + "?series_id=" + enc(sid),
                    "/api/v1/videos/" + enc(rv)
            };
            for (String p : paths) {
                String u = fetchPlayUrl(p);
                if (u.length() > 0) return u;
            }
        }
        if (item.length() > 0) {
            String u = fetchPlayUrl("/api/v1/videos/" + enc(item) + "?series_id=" + enc(sid));
            if (u.length() > 0) return u;
            u = fetchPlayUrl("/api/v1/videos/" + enc(item));
            if (u.length() > 0) return u;
        }
        return "";
    }

    /** 播放端点可能直接吐一行明文 URL，也可能吐 JSON。 */
    private String fetchPlayUrl(String path) {
        String body = getQuiet(path);
        if (body.length() == 0) return "";
        String t = body.trim();
        if (t.startsWith("http")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(0, nl);
            t = t.trim();
            return playable(t) ? t : "";
        }
        return findUrl(parseQuiet(body), 0);
    }

    private String fill(String tpl, String sid, String item, String ep, String rv) {
        return tpl.replace("{sid}", enc(sid)).replace("{item}", enc(item))
                .replace("{vid}", enc(item)).replace("{ep}", enc(ep))
                .replace("{rv}", enc(rv));
    }

    /** APP 把播放过的集缓存成 <item_id>.mp4 明文 progressive mp4，可直接交给播放器。 */
    private String offlineFile(String item) {
        if (fileRoot.length() == 0 || item.length() == 0) return "";
        File dir = new File(fileRoot);
        File f = new File(dir, item + ".mp4");
        if (!f.isFile() || f.length() < 1024) {
            runSync();
            f = new File(dir, item + ".mp4");
        }
        if (!f.isFile() || f.length() < 1024) return "";
        Mp4Server srv = FILE_SERVER;
        if (srv != null && srv.alive && srv.port == filePort) {
            return "http://127.0.0.1:" + filePort + "/" + item + ".mp4";
        }
        return "file://" + f.getAbsolutePath();
    }

    /** 可选：root 设备上把 APP 私有 offline-video 同步到公共目录后再取文件。 */
    private void runSync() {
        if (syncCmd.length() == 0) return;
        try {
            Process p = Runtime.getRuntime().exec(syncCmd);
            try {
                p.waitFor();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            p.destroy();
        } catch (Exception ignored) {
        }
    }

    /** 章节内容里藏着真实字节视频 vid(v02...) 和多档清晰度。响应是 HTML 转义过的 JSON。 */
    private String realVid(String itemId) {
        if (itemId == null || itemId.length() == 0) return "";
        String cached = realVids.get(itemId);
        if (cached != null) return cached;
        String body = getQuiet("/api/v1/chapters/" + enc(itemId));
        JSONObject root = parseQuiet(body);
        String rv = "";
        if (root != null) {
            JSONObject data = root.optJSONObject("data");
            String content = data == null ? "" : str(data, "content");
            if (content.length() > 0) {
                String raw = content.replace("&#34;", "\"").replace("&quot;", "\"")
                        .replace("&#39;", "'").replace("&amp;", "&")
                        .replace("&lt;", "<").replace("&gt;", ">");
                int s = raw.indexOf('{');
                int e = raw.lastIndexOf('}');
                if (s >= 0 && e > s) {
                    JSONObject meta = parseQuiet(raw.substring(s, e + 1));
                    if (meta != null) rv = str(meta, "vid");
                }
                if (rv.length() == 0) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("(v0[0-9a-z]{20,})").matcher(raw);
                    if (m.find()) rv = m.group(1);
                }
            }
        }
        realVids.put(itemId, rv);
        return rv;
    }

    // ---------------------------------------------------------------- 本地 MP4 服务

    private static volatile Mp4Server FILE_SERVER = null;

    private synchronized void startFileServer() {
        if (fileRoot.length() == 0) return;
        Mp4Server old = FILE_SERVER;
        if (old != null && old.alive && old.port == filePort && old.root.equals(fileRoot)) return;
        if (old != null) old.stop();
        Mp4Server srv = new Mp4Server(filePort, fileRoot);
        FILE_SERVER = srv;
        Thread t = new Thread(srv, "shipu-mp4-server");
        t.setDaemon(true);
        t.start();
    }

    /** 极简 HTTP/1.1 文件服务：只服务 .mp4，实现 Range，供 ExoPlayer/IJK seek。 */
    private static final class Mp4Server implements Runnable {
        final int port;
        final String root;
        volatile boolean alive = true;
        private ServerSocket ss;

        Mp4Server(int port, String root) {
            this.port = port;
            this.root = root;
        }

        public void run() {
            try {
                ss = new ServerSocket(port, 16, java.net.InetAddress.getByName("127.0.0.1"));
            } catch (Exception e) {
                alive = false;
                return;
            }
            while (alive) {
                try {
                    final Socket c = ss.accept();
                    new Thread(new Runnable() {
                        public void run() {
                            handle(c);
                        }
                    }, "shipu-mp4-conn").start();
                } catch (Exception e) {
                    if (!alive) break;
                }
            }
        }

        void stop() {
            alive = false;
            try {
                if (ss != null) ss.close();
            } catch (Exception ignored) {
            }
        }

        private void handle(Socket c) {
            try {
                c.setSoTimeout(15000);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) >= 0) {
                    head.write(b);
                    byte[] a = head.toByteArray();
                    int n = a.length;
                    if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n'
                            && a[n - 2] == '\r' && a[n - 1] == '\n') break;
                    if (n > 16384) break;
                }
                String req = new String(head.toByteArray(), "UTF-8");
                String line = req.split("\r\n")[0];
                int s1 = line.indexOf(' '), s2 = line.indexOf(' ', s1 + 1);
                String path = s2 > s1 ? line.substring(s1 + 1, s2) : "/";
                int q = path.indexOf('?');
                if (q > 0) path = path.substring(0, q);
                String name = path.startsWith("/") ? path.substring(1) : path;
                if (name.indexOf("..") >= 0 || name.indexOf('/') >= 0 || !name.endsWith(".mp4")) {
                    writeErr(c, 404);
                    return;
                }
                File f = new File(root, name);
                if (!f.isFile()) {
                    writeErr(c, 404);
                    return;
                }
                long len = f.length(), from = 0, to = len - 1;
                boolean partial = false;
                int ri = indexOfIgnoreCase(req, "Range: bytes=");
                if (ri >= 0) {
                    int e = req.indexOf("\r\n", ri);
                    if (e > ri) {
                        String r = req.substring(ri + 13, e).trim();
                        int dash = r.indexOf('-');
                        if (dash >= 0) {
                            try {
                                String a = r.substring(0, dash).trim();
                                String bb = r.substring(dash + 1).trim();
                                if (a.length() > 0) from = Long.parseLong(a);
                                if (bb.length() > 0) to = Math.min(Long.parseLong(bb), len - 1);
                                partial = true;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                if (from < 0 || from > to || from >= len) {
                    writeErr(c, 416);
                    return;
                }
                long count = to - from + 1;
                OutputStream out = c.getOutputStream();
                StringBuilder h = new StringBuilder(partial
                        ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
                h.append("Content-Type: video/mp4\r\n")
                        .append("Accept-Ranges: bytes\r\n")
                        .append("Content-Length: ").append(count).append("\r\n");
                if (partial) {
                    h.append("Content-Range: bytes ").append(from).append('-')
                            .append(to).append('/').append(len).append("\r\n");
                }
                h.append("Connection: close\r\n\r\n");
                out.write(h.toString().getBytes("UTF-8"));
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                try {
                    raf.seek(from);
                    byte[] buf = new byte[32768];
                    long left = count;
                    while (left > 0) {
                        int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n <= 0) break;
                        out.write(buf, 0, n);
                        left -= n;
                    }
                } finally {
                    raf.close();
                }
                out.flush();
            } catch (Exception ignored) {
            } finally {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void writeErr(Socket c, int code) throws Exception {
            String msg = code == 404 ? "Not Found" : "Range Not Satisfiable";
            OutputStream out = c.getOutputStream();
            out.write(("HTTP/1.1 " + code + " " + msg
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            out.flush();
        }

        private int indexOfIgnoreCase(String s, String k) {
            return s.toLowerCase().indexOf(k.toLowerCase());
        }
    }

    // ---------------------------------------------------------------- JSON 工具

    /** 递归抽取短剧卡片：只认 series_title / series_cover / video_detail / use_video_model 等卡片特征，跳过单集对象。 */
    private void collectCards(Object node, Map<String, JSONObject> out, int depth) throws Exception {
        if (node == null || depth > 16) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) collectCards(arr.opt(i), out, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        // 卡片数据真身通常在 video_detail 里，外层只是 video_data 包装
        JSONObject src = o.optJSONObject("video_detail");
        if (src == null) src = o;
        boolean card = src.has("series_id") && (src.has("series_title") || src.has("series_cover")
                || src.has("use_video_model") || src.has("series_play_cnt"));
        if (card) {
            String sid = str(src, "series_id");
            if (sid.length() > 0 && !out.containsKey(sid)) {
                JSONObject c = new JSONObject()
                        .put("vod_id", sid)
                        .put("vod_name", first(src, "series_title", "title", "raw_book_name"))
                        .put("vod_pic", first(src, "series_cover", "cover", "thumb_url"))
                        .put("vod_content", first(src, "series_intro", "video_desc", "sub_title"))
                        .put("vod_remarks", remark(src))
                        .put("type_name", "短剧");
                out.put(sid, c);
                if (!cards.containsKey(sid)) cards.put(sid, src);
            }
        } else if (o.has("series_id") && (o.has("series_title") || o.has("series_cover")
                || o.has("use_video_model") || o.has("series_play_cnt"))) {
            // 搜索结果的 video_data 卡片：字段平铺在外层
            String sid = str(o, "series_id");
            if (sid.length() > 0 && !out.containsKey(sid)) {
                JSONObject c = new JSONObject()
                        .put("vod_id", sid)
                        .put("vod_name", first(o, "series_title", "title", "raw_book_name"))
                        .put("vod_pic", first(o, "series_cover", "cover", "thumb_url"))
                        .put("vod_content", first(o, "series_intro", "video_desc", "sub_title"))
                        .put("vod_remarks", remark(o))
                        .put("type_name", "短剧");
                out.put(sid, c);
                if (!cards.containsKey(sid)) cards.put(sid, o);
            }
        }
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            collectCards(o.opt(it.next()), out, depth + 1);
        }
    }

    private String remark(JSONObject o) {
        String cnt = first(o, "episode_cnt", "episode");
        if (cnt.length() > 0) return "共" + cnt + "集";
        String play = first(o, "series_play_cnt", "play_cnt");
        return play.length() > 0 ? play + "播放" : "短剧";
    }

    /** 递归找可播放 URL，优先 URL_KEYS 指定字段。 */
    private String findUrl(Object node, int depth) {
        if (node == null || depth > 14) return "";
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                String u = findUrl(arr.opt(i), depth + 1);
                if (u.length() > 0) return u;
            }
            return "";
        }
        if (node instanceof String) return playable((String) node) ? (String) node : "";
        if (!(node instanceof JSONObject)) return "";
        JSONObject o = (JSONObject) node;
        for (String k : URL_KEYS) {
            Object v = o.opt(k);
            if (v instanceof String && playable((String) v)) return (String) v;
        }
        JSONArray urlList = o.optJSONArray("url_list");
        if (urlList != null) {
            for (int i = 0; i < urlList.length(); i++) {
                Object v = urlList.opt(i);
                if (v instanceof String && playable((String) v)) return (String) v;
            }
        }
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String u = findUrl(o.opt(it.next()), depth + 1);
            if (u.length() > 0) return u;
        }
        return "";
    }

    private boolean playable(String s) {
        if (s == null || s.length() < 20 || !s.startsWith("http")) return false;
        String low = s.toLowerCase();
        String[] bad = {".image", ".jpg", ".jpeg", ".png", ".webp", ".heic", ".gif",
                "byteimg", "fqnovelpic", "novel-static", "~tplv", "/novel-pic/", "schema", "dragon"};
        for (String b : bad) if (low.contains(b)) return false;
        return true;
    }

    private JSONArray toArray(Map<String, JSONObject> found, int max) {
        JSONArray arr = new JSONArray();
        for (JSONObject c : found.values()) {
            if (arr.length() >= max) break;
            arr.put(c);
        }
        return arr;
    }

    private JSONObject pageResult(JSONArray list, int page, int pagecount, int lim) throws Exception {
        return pageResult(list, page, pagecount, lim, list.length());
    }

    /** total 必须是卡片池总数，不是当页条数，否则 TVBox 会误判「没有下一页」。 */
    private JSONObject pageResult(JSONArray list, int page, int pagecount, int lim,
                                  int total) throws Exception {
        return new JSONObject().put("list", list).put("page", page)
                .put("pagecount", pagecount).put("limit", lim).put("total", total);
    }

    private String emptyList() throws Exception {
        return new JSONObject().put("list", new JSONArray()).toString();
    }

    // ---------------------------------------------------------------- 基础工具

    private String cellOf(String tid) {
        if ("drama".equals(tid)) return cellDrama;
        if ("manju".equals(tid)) return cellManju;
        if ("rank".equals(tid)) return cellRank;
        return cellRecommend;
    }

    private int pageLimit(HashMap<String, String> extend) {
        if (extend != null && extend.containsKey("limit")) {
            int v = parseInt(extend.get("limit"), limit);
            if (v >= 6 && v <= 60) return v;
        }
        return limit;
    }

    private String getQuiet(String path) {
        try {
            return get(path);
        } catch (Exception e) {
            return "";
        }
    }

    private String get(String path) throws Exception {
        if (path.startsWith("/api/v1/")) return FqUpstream.call(path);
        String target = path.startsWith("http") ? path : apiBase + path;
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
            conn.disconnect();
        }
    }

    private JSONObject parseQuiet(String body) {
        if (body == null || body.length() == 0 || !body.trim().startsWith("{")) return null;
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        Object v = o.opt(key);
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    private String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = str(o, k);
            if (v.length() > 0) return v;
        }
        return "";
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    private String clean(String s) {
        return s.replace("#", " ").replace("$", " ");
    }

    private String trimEnd(String s) {
        String v = s == null ? "" : s.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }
}
