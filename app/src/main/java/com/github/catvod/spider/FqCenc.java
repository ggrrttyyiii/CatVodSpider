package com.github.catvod.spider;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * fqnovel 短剧的 CENC 明文还原 + 本地明文播放服务。
 *
 * video_model/v1 返回的 MP4 是 cenc-aes-ctr 加密的（encrypt=true, kid, spade_a）：
 * ftyp/moov 是明文，mdat 里的 sample 用 AES-CTR 加密。直接把 CDN 地址交给
 * TVBox 播放器会报 Container Malformed。本类：
 *   1) 从 spade_a 推导 16 字节 content key（开源 Rust derive_spade_content_key）；
 *   2) 下载整份加密 MP4，解析 stsz/stsc/stco/senc 得到每个 sample 的 offset 与 8 字节 IV；
 *   3) 按 sample 做 AES-CTR 解密，并把 stsd 里的 encv/enca 改成 hvc1/mp4a、
 *      sinf 改成等长 free box（否则播放器仍按加密容器解析）；
 *   4) 通过进程内 127.0.0.1 HTTP 服务把明文字节流给播放器，支持 Range。
 *
 * 顺带彻底绕开 CDN 防盗链：播放器只访问本地地址，spider 下载时绝不发 Referer。
 * 算法已用 novel-sound/rust/testdata/cenc_sample_fixture.json 的 golden 明文对拍。
 */
public final class FqCenc {

    public static final int PORT = 18924;
    /** 明文 LRU 预算：1080p 单集约 20 MB，够留当前集 + 下一集 + 上一集。 */
    private static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;
    private static final long MIN_SPLIT_SIZE = 2L * 1024 * 1024;
    private static final long MAX_SPLIT_SIZE = 200L * 1024 * 1024;
    private static final int PARTS = 8;

    /** 同一集只下载一次：预取中 + 播放请求会合流到同一个 Task。 */
    private static final ConcurrentHashMap<String, Task> INFLIGHT =
            new ConcurrentHashMap<String, Task>();
    private static final ExecutorService PREFETCH =
            Executors.newSingleThreadExecutor(daemon("fq-prefetch"));
    private static final ExecutorService PARTS_POOL =
            Executors.newFixedThreadPool(PARTS, daemon("fq-part"));

    private static final class Task {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String url = "";
    }

    private static ThreadFactory daemon(final String prefix) {
        return new ThreadFactory() {
            private int seq = 0;
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + (++seq));
                t.setDaemon(true);
                return t;
            }
        };
    }

    private static final Set<String> CONTAINERS = new HashSet<String>(Arrays.asList(
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf",
            "sinf", "schi", "mvex", "udta"));

    private FqCenc() {
    }

    // ------------------------------------------------------------ content key

    /** spade_a -> 16 字节 AES key。golden: kbwf80+1... -> 4990a92de837e29e18031a370ab744e6 */
    public static byte[] deriveKey(String spadeA) {
        try {
            byte[] raw = FqSign.b64Decode(spadeA == null ? "" : spadeA);
            if (raw.length < 3) return null;
            int outLen = raw.length - (((raw[0] ^ raw[1] ^ raw[2]) & 255)) + 0x2F;
            if (outLen < 33 || outLen + 1 > raw.length) return null;
            byte[] buf = new byte[outLen];
            System.arraycopy(raw, 1, buf, 0, outLen);
            byte[] src = buf.clone();
            int prevA = 0x55, prevB = 0xF6;
            for (int i = 0; i < src.length; i++) {
                int cur = src[i] & 255;
                int keep;
                if ((i & 1) == 1) {
                    keep = cur;
                } else {
                    keep = prevA;
                    prevA = prevB;
                    prevB = cur;
                }
                buf[i] = (byte) (((prevA ^ cur) - Integer.bitCount(i) - 0x15) & 255);
                prevA = keep;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < 33; i++) sb.append((char) (buf[i] & 255));
            byte[] key = hexToBytes(sb.toString());
            return key.length == 16 ? key : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] hexToBytes(String s) {
        if (s.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int a = Character.digit(s.charAt(2 * i), 16);
            int b = Character.digit(s.charAt(2 * i + 1), 16);
            if (a < 0 || b < 0) return new byte[0];
            out[i] = (byte) ((a << 4) | b);
        }
        return out;
    }

    public static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            String h = Integer.toHexString(x & 255);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ MP4 boxes

    private static final class Box {
        final int off, size, hdr;
        final String type;

        Box(int off, int size, int hdr, String type) {
            this.off = off;
            this.size = size;
            this.hdr = hdr;
            this.type = type;
        }
    }

    private static long u32(byte[] d, int o) {
        return ((long) (d[o] & 255) << 24) | ((long) (d[o + 1] & 255) << 16)
                | ((long) (d[o + 2] & 255) << 8) | (d[o + 3] & 255);
    }

    private static long u64(byte[] d, int o) {
        return (u32(d, o) << 32) | u32(d, o + 4);
    }

    private static int u16(byte[] d, int o) {
        return ((d[o] & 255) << 8) | (d[o + 1] & 255);
    }

    private static void writeU32(byte[] d, int o, long v) {
        d[o] = (byte) (v >>> 24);
        d[o + 1] = (byte) (v >>> 16);
        d[o + 2] = (byte) (v >>> 8);
        d[o + 3] = (byte) v;
    }

    private static void add(Map<String, List<Box>> m, String k, Box b) {
        List<Box> l = m.get(k);
        if (l == null) {
            l = new ArrayList<Box>();
            m.put(k, l);
        }
        l.add(b);
    }

    private static void collect(byte[] d, int start, int end, Map<String, List<Box>> out) {
        int off = start;
        while (off + 8 <= end) {
            long sz = u32(d, off);
            String typ = "" + (char) (d[off + 4] & 255) + (char) (d[off + 5] & 255)
                    + (char) (d[off + 6] & 255) + (char) (d[off + 7] & 255);
            int hdr = 8;
            if (sz == 1) {
                if (off + 16 > end) break;
                sz = u64(d, off + 8);
                hdr = 16;
            } else if (sz == 0) {
                sz = end - off;
            }
            if (sz < hdr || sz > Integer.MAX_VALUE || off + sz > end) break;
            int size = (int) sz;
            add(out, typ, new Box(off, size, hdr, typ));
            if (CONTAINERS.contains(typ)) {
                collect(d, off + hdr, off + size, out);
            } else if ("stsd".equals(typ)) {
                collect(d, off + hdr + 8, off + size, out);
            } else if ("encv".equals(typ) || "enca".equals(typ)) {
                // sample entry 的固定字段长度不同，直接在其范围内找 sinf
                for (int p = off + hdr; p + 8 <= off + size; p++) {
                    if (p - 4 >= off && d[p] == 's' && d[p + 1] == 'i'
                            && d[p + 2] == 'n' && d[p + 3] == 'f') {
                        long bs = u32(d, p - 4);
                        if (bs >= 8 && bs <= Integer.MAX_VALUE && p - 4 + bs <= off + size) {
                            add(out, "sinf", new Box(p - 4, (int) bs, 8, "sinf"));
                        }
                        break;
                    }
                }
            }
            off += size;
        }
    }

    private static int[] parseStsz(byte[] d, Box b) {
        int p = b.off + b.hdr;
        int sampleSize = (int) u32(d, p + 4);
        int count = (int) u32(d, p + 8);
        p += 12;
        if (count < 0 || count > 10000000) return new int[0];
        int[] out = new int[count];
        if (sampleSize != 0) {
            Arrays.fill(out, sampleSize);
        } else {
            for (int i = 0; i < count; i++) out[i] = (int) u32(d, p + 4 * i);
        }
        return out;
    }

    private static long[] parseStco(byte[] d, Box b, boolean wide) {
        int p = b.off + b.hdr;
        int count = (int) u32(d, p + 4);
        p += 8;
        if (count < 0 || count > 10000000) return new long[0];
        long[] out = new long[count];
        for (int i = 0; i < count; i++) {
            out[i] = wide ? u64(d, p + 8 * i) : u32(d, p + 4 * i);
        }
        return out;
    }

    private static int[][] parseStsc(byte[] d, Box b) {
        int p = b.off + b.hdr;
        int count = (int) u32(d, p + 4);
        p += 8;
        if (count < 0 || count > 10000000) return new int[0][3];
        int[][] out = new int[count][3];
        for (int i = 0; i < count; i++) {
            out[i][0] = (int) u32(d, p + 12 * i);
            out[i][1] = (int) u32(d, p + 12 * i + 4);
            out[i][2] = (int) u32(d, p + 12 * i + 8);
        }
        return out;
    }

    private static byte[][] parseSenc(byte[] d, Box b, int ivSize) {
        int p = b.off + b.hdr;
        long flags = u32(d, p) & 0xFFFFFF;
        int count = (int) u32(d, p + 4);
        p += 8;
        if (count < 0 || count > 10000000) return new byte[0][];
        byte[][] out = new byte[count][];
        for (int i = 0; i < count; i++) {
            byte[] iv = new byte[ivSize];
            System.arraycopy(d, p, iv, 0, ivSize);
            p += ivSize;
            out[i] = iv;
            if ((flags & 0x2) != 0) {
                int n = u16(d, p);
                p += 2 + 6 * n;
            }
        }
        return out;
    }

    private static long[] sampleOffsets(int[][] stsc, long[] chunks, int[] sizes) {
        long[] offs = new long[sizes.length];
        int si = 0;
        for (int ci = 0; ci < chunks.length; ci++) {
            long coff = chunks[ci];
            int spc = 0;
            for (int[] e : stsc) {
                if (e[0] <= ci + 1) spc = e[1];
                else break;
            }
            for (int j = 0; j < spc && si < sizes.length; j++) {
                offs[si] = coff;
                coff += sizes[si];
                si++;
            }
        }
        return offs;
    }

    // ------------------------------------------------------------ decrypt

    /** 返回解密后的完整 MP4 明文字节；key 为空时原样返回。 */
    public static byte[] decrypt(byte[] data, byte[] key) {
        if (data == null || key == null || key.length != 16) return data;
        try {
            byte[] buf = data.clone();
            Map<String, List<Box>> boxes = new HashMap<String, List<Box>>();
            collect(buf, 0, buf.length, boxes);

            // 头补丁：编码器仍标成 encv/enca 时播放器会去找 DRM，必须改回明文入口。
            patchType(boxes.get("encv"), buf, "hvc1");
            patchType(boxes.get("enca"), buf, "mp4a");
            List<Box> sinfs = boxes.get("sinf");
            if (sinfs != null) {
                for (Box b : sinfs) {
                    writeU32(buf, b.off, b.size);   // 保持 size，box 链才合法
                    buf[b.off + 4] = 'f';
                    buf[b.off + 5] = 'r';
                    buf[b.off + 6] = 'e';
                    buf[b.off + 7] = 'e';
                    Arrays.fill(buf, b.off + 8, b.off + b.size, (byte) 0);
                }
            }

            List<Box> senc = boxes.get("senc");
            List<Box> stsz = boxes.get("stsz");
            List<Box> stsc = boxes.get("stsc");
            List<Box> stco = boxes.get("stco");
            List<Box> co64 = boxes.get("co64");
            if (senc == null || stsz == null || stsc == null) return buf;
            boolean wide = co64 != null && !co64.isEmpty()
                    && (stco == null || stco.isEmpty());
            List<Box> chunksBox = wide ? co64 : stco;
            if (chunksBox == null) return buf;

            int n = Math.min(Math.min(senc.size(), stsz.size()),
                    Math.min(stsc.size(), chunksBox.size()));
            Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
            ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            for (int i = 0; i < n; i++) {
                int[] sizes = parseStsz(buf, stsz.get(i));
                int[][] sc = parseStsc(buf, stsc.get(i));
                long[] chunks = parseStco(buf, chunksBox.get(i), wide);
                byte[][] ivs = parseSenc(buf, senc.get(i), 8);
                long[] offs = sampleOffsets(sc, chunks, sizes);
                int m = Math.min(offs.length, ivs.length);
                for (int j = 0; j < m; j++) {
                    xorCtr(buf, offs[j], sizes[j], ivs[j], ecb);
                }
            }
            return buf;
        } catch (Exception e) {
            return data;
        }
    }

    private static void patchType(List<Box> list, byte[] buf, String type) {
        if (list == null) return;
        for (Box b : list) {
            for (int i = 0; i < 4; i++) buf[b.off + 4 + i] = (byte) type.charAt(i);
        }
    }

    /** AES-CTR：counter0 = IV(8) || 0(8)，按 128-bit big-endian 逐块递增。 */
    private static void xorCtr(byte[] buf, long off, int size, byte[] iv8, Cipher ecb)
            throws Exception {
        if (size <= 0 || off < 0 || off + size > buf.length) return;
        int n = (size + 15) / 16;
        byte[] blocks = new byte[n * 16];
        for (int b = 0; b < n; b++) {
            System.arraycopy(iv8, 0, blocks, b * 16, 8);
            long v = b;
            for (int i = 0; i < 8; i++) {
                blocks[b * 16 + 15 - i] = (byte) (v >>> (8 * i));
            }
        }
        byte[] ks = ecb.doFinal(blocks);
        int o = (int) off;
        for (int i = 0; i < size; i++) buf[o + i] ^= ks[i];
    }

    // ------------------------------------------------------------ 下载 + 发布

    /** 查缓存：预取已完成的集会立刻拿到本地地址，切集零网络等待。 */
    public static String cachedUrl(String id) {
        if (id == null || id.length() == 0) return "";
        String name = safeName(id);
        String u = ClearServer.INSTANCE.urlIfPresent(name);
        if (u != null) {
            ClearServer.INSTANCE.touch(name);   // 当前正在播的集不参与淘汰
            return u;
        }
        return "";
    }

    /**
     * 后台预取下一集：playInfo(优先 1080p) -> 分段下载 -> CENC 解密 -> 发布。
     * 单线程串行队列，绝不阻塞 playerContent；同一集由 INFLIGHT 去重。
     */
    public static void prefetch(final String itemId) {
        if (itemId == null || itemId.length() == 0) return;
        final String name = safeName(itemId);
        if (ClearServer.INSTANCE.urlIfPresent(name) != null) return;
        Task t = new Task();
        if (INFLIGHT.putIfAbsent(name, t) != null) return;
        try {
            PREFETCH.execute(new Runnable() {
                public void run() {
                    String u = "";
                    try {
                        FqUpstream.Play p = FqUpstream.playInfo(itemId);
                        if (p != null && p.key != null && p.url.length() > 0) {
                            u = publishNamed(p.url, p.key, name, false);
                            if (u.length() == 0 && p.backupUrl.length() > 0) {
                                u = publishNamed(p.backupUrl, p.key, name, false);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    t.url = u;
                    t.latch.countDown();
                    INFLIGHT.remove(name);
                }
            });
        } catch (Exception e) {
            t.latch.countDown();
            INFLIGHT.remove(name);
        }
    }

    /** 当前集：先命中缓存 / 等待正在进行的预取，未命中才同步下载解密。 */
    public static String publish(String cdnUrl, byte[] key, String id) {
        return publishNamed(cdnUrl, key, safeName(id), true);
    }

    private static String publishNamed(String cdnUrl, byte[] key, String name,
                                       boolean waitInflight) {
        try {
            String hit = ClearServer.INSTANCE.urlIfPresent(name);
            if (hit != null) {
                ClearServer.INSTANCE.touch(name);
                return hit;
            }
            if (waitInflight) {
                Task t = INFLIGHT.get(name);
                if (t != null) {
                    try { t.latch.await(12, TimeUnit.SECONDS); } catch (Exception ignored) {}
                    hit = ClearServer.INSTANCE.urlIfPresent(name);
                    if (hit != null) {
                        ClearServer.INSTANCE.touch(name);
                        return hit;
                    }
                }
            }
            byte[] enc = download(cdnUrl);
            if (enc == null || enc.length < 1024) return "";
            byte[] clear = decrypt(enc, key);
            return ClearServer.INSTANCE.put(name, clear, waitInflight);
        } catch (Exception e) {
            return "";
        }
    }

    /** 分段并发优先，任一段异常则回落单连接整份下载。 */
    private static byte[] download(String url) throws IOException {
        byte[] multi = downloadParallel(url);
        return multi != null ? multi : fetchRange(url, -1, -1);
    }

    private static long probeTotal(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", FqUpstream.PLAY_UA);
            c.setRequestProperty("Range", "bytes=0-0");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            String cr = c.getHeaderField("Content-Range");
            if (code == 206 && cr != null) {
                int s = cr.lastIndexOf('/');
                if (s >= 0) return Long.parseLong(cr.substring(s + 1).trim());
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return -1;
    }

    /**
     * 8 段并发 Range 下载。CDN 对单连接限速明显（实测同一集：1 段 34.5s /
     * 4 段 5.9s / 8 段 1.0s），1080p 单集 8~20 MB，多连接是切集提速的关键。
     * 失败段单独补下；全部补不回来才回落单连接整份下载。
     */
    private static byte[] downloadParallel(String url) {
        long total = probeTotal(url);
        if (total < MIN_SPLIT_SIZE || total > MAX_SPLIT_SIZE) return null;
        final long[][] ranges = new long[PARTS][2];
        long step = (total + PARTS - 1) / PARTS;
        for (int i = 0; i < PARTS; i++) {
            ranges[i][0] = i * step;
            ranges[i][1] = Math.min(total - 1, (i + 1) * step - 1);
            if (ranges[i][0] > ranges[i][1]) return null;
        }
        final byte[][] chunks = new byte[PARTS][];
        final CountDownLatch latch = new CountDownLatch(PARTS);
        for (int i = 0; i < PARTS; i++) {
            final int idx = i;
            try {
                PARTS_POOL.execute(new Runnable() {
                    public void run() {
                        try {
                            chunks[idx] = fetchRange(url, ranges[idx][0], ranges[idx][1]);
                        } catch (IOException ignored) {
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            } catch (Exception e) {
                return null;
            }
        }
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        // 单段失败不要整体退化成慢速单连接：只补失败的那几段。
        for (int i = 0; i < PARTS; i++) {
            int want = (int) (ranges[i][1] - ranges[i][0] + 1);
            if (chunks[i] == null || chunks[i].length != want) {
                try { chunks[i] = fetchRange(url, ranges[i][0], ranges[i][1]); }
                catch (IOException ignored) { chunks[i] = null; }
            }
        }
        byte[] out = new byte[(int) total];
        for (int i = 0; i < PARTS; i++) {
            int want = (int) (ranges[i][1] - ranges[i][0] + 1);
            if (chunks[i] == null || chunks[i].length != want) return null;
            System.arraycopy(chunks[i], 0, out, (int) ranges[i][0], chunks[i].length);
        }
        return out;
    }

    /** start < 0 表示整份 GET；否则要求 206 且长度精确匹配。绝不发 Referer。 */
    private static byte[] fetchRange(String url, long start, long end) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent", FqUpstream.PLAY_UA);
                if (start >= 0) c.setRequestProperty("Range", "bytes=" + start + "-" + end);
                // 注意：CDN 对任何 Referer 都返回 403，这里绝不能设置 Referer。
                c.setConnectTimeout(12000);
                c.setReadTimeout(30000);
                int code = c.getResponseCode();
                if (code >= 400) throw new IOException("CDN HTTP " + code);
                if (start >= 0 && code != 206) throw new IOException("Range rejected " + code);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] t = new byte[65536];
                int n;
                while ((n = in.read(t)) > 0) bos.write(t, 0, n);
                in.close();
                byte[] out = bos.toByteArray();
                if (start >= 0 && out.length != (int) (end - start + 1))
                    throw new IOException("short range " + out.length);
                return out;
            } catch (IOException e) {
                last = e;   // 偶发 connection reset，同节点重试一次
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last == null ? new IOException("download failed") : last;
    }

    private static String safeName(String id) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(
                    (id == null ? "" : id).getBytes("UTF-8"));
            return hex(h).substring(0, 16);
        } catch (Exception e) {
            return "v" + Math.abs((id == null ? "" : id).hashCode());
        }
    }

    /** 进程内明文 MP4 服务：127.0.0.1:PORT，支持 Range，按字节预算做 LRU。 */
    private static final class ClearServer implements Runnable {
        static final ClearServer INSTANCE = new ClearServer();
        /** accessOrder：播放器每次 Range 请求都会把当前集顶到最新，避免被预取挤掉。 */
        final LinkedHashMap<String, byte[]> files =
                new LinkedHashMap<String, byte[]>(8, 0.75f, true);
        long cachedBytes = 0;
        String pinned = "";
        volatile boolean alive = false;
        ServerSocket ss;

        synchronized String urlIfPresent(String name) {
            return files.get(name) == null ? null
                    : "http://127.0.0.1:" + PORT + "/" + name + ".mp4";
        }

        synchronized void touch(String name) {
            if (files.containsKey(name)) pinned = name;
        }

        synchronized String put(String name, byte[] data, boolean pin) {
            byte[] old = files.remove(name);
            if (old != null) cachedBytes -= old.length;
            files.put(name, data);
            cachedBytes += data.length;
            if (pin) pinned = name;
            while (files.size() > 1 && cachedBytes > MAX_CACHE_BYTES) {
                String victim = null;
                for (String k : files.keySet()) {
                    if (!k.equals(pinned)) { victim = k; break; }
                }
                if (victim == null) break;
                byte[] v = files.remove(victim);
                if (v != null) cachedBytes -= v.length;
            }
            if (!alive) start();
            return "http://127.0.0.1:" + PORT + "/" + name + ".mp4";
        }

        private synchronized void start() {
            if (alive) return;
            try {
                ss = new ServerSocket(PORT, 16, java.net.InetAddress.getByName("127.0.0.1"));
                alive = true;
                Thread t = new Thread(this, "fq-cenc-clear-server");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                alive = false;
            }
        }

        public void run() {
            while (alive && ss != null && !ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    Thread h = new Thread(new Runnable() {
                        public void run() {
                            handle(s);
                        }
                    }, "fq-cenc-http");
                    h.setDaemon(true);
                    h.start();
                } catch (Exception e) {
                    if (ss == null || ss.isClosed()) break;
                }
            }
        }

        private void handle(Socket s) {
            try {
                s.setSoTimeout(20000);
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
                String line = r.readLine();
                String range = "";
                String h;
                while ((h = r.readLine()) != null && h.length() > 0) {
                    if (h.toLowerCase().startsWith("range:")) {
                        range = h.substring(6).trim();
                    }
                }
                String name = "";
                if (line != null) {
                    String[] p = line.split(" ");
                    if (p.length >= 2) {
                        String path = p[1];
                        int q = path.indexOf('?');
                        if (q >= 0) path = path.substring(0, q);
                        int slash = path.lastIndexOf('/');
                        if (slash >= 0) path = path.substring(slash + 1);
                        if (path.endsWith(".mp4")) path = path.substring(0, path.length() - 4);
                        name = path;
                    }
                }
                byte[] data;
                synchronized (this) {
                    data = files.get(name);
                }
                OutputStream o = s.getOutputStream();
                if (data == null) {
                    o.write(("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n"
                            + "Connection: close\r\n\r\n").getBytes("ISO-8859-1"));
                } else {
                    long start = 0, end = data.length - 1;
                    int code = 200;
                    if (range.startsWith("bytes=")) {
                        String spec = range.substring(6);
                        int dash = spec.indexOf('-');
                        if (dash >= 0) {
                            try {
                                String a = spec.substring(0, dash).trim();
                                String b = spec.substring(dash + 1).trim();
                                if (a.length() > 0) start = Long.parseLong(a);
                                if (b.length() > 0) end = Math.min(end, Long.parseLong(b));
                            } catch (Exception ignored) {
                            }
                        }
                        if (start < 0) start = 0;
                        if (end > data.length - 1) end = data.length - 1;
                        code = 206;
                    }
                    if (start > end || start >= data.length) {
                        o.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n"
                                + "Connection: close\r\n\r\n").getBytes("ISO-8859-1"));
                        o.flush();
                        return;
                    }
                    int len = (int) (end - start + 1);
                    StringBuilder head = new StringBuilder();
                    head.append(code == 206 ? "HTTP/1.1 206 Partial Content\r\n"
                            : "HTTP/1.1 200 OK\r\n");
                    head.append("Content-Type: video/mp4\r\n");
                    head.append("Accept-Ranges: bytes\r\n");
                    head.append("Content-Length: ").append(len).append("\r\n");
                    if (code == 206) {
                        head.append("Content-Range: bytes ").append(start).append('-')
                                .append(end).append('/').append(data.length).append("\r\n");
                    }
                    head.append("Connection: close\r\n\r\n");
                    o.write(head.toString().getBytes("ISO-8859-1"));
                    o.write(data, (int) start, len);
                }
                o.flush();
            } catch (Exception ignored) {
            } finally {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 供自测：golden key + 可选本地文件解密。 */
    public static String selfTest() {
        byte[] k = deriveKey("kbwf80+1N+V9nwHQSp431GWvLeBlqizTYJ0o5FOrHuZhqimysg==");
        return "goldenKey=" + hex(k) + " match="
                + "4990a92de837e29e18031a370ab744e6".equals(hex(k));
    }
}
