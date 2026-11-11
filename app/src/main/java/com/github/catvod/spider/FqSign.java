package com.github.catvod.spider;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * fqnovel 上游签名栈的纯 Java 实现（不依赖原 APP / native so / 本地服务）。
 *
 * 算法来源：开源项目 ch6vip/novel-sound (GPL-3.0) rust/src/sign/*.rs，
 * 已用该项目 rust/testdata/vectors.json 与 crypto_vectors.rs 的 golden vectors 逐项对拍。
 *
 * 有效 header（aid=1967）：x-ss-req-ticket / x-khronos / x-ladon / x-argus，
 * POST 时额外 x-ss-stub。x-gorgon 对当前上游已失效，默认关闭。
 */
public final class FqSign {

    public static final long AID = 1967L;
    public static final long LICENSE_ID = 1611921764L;

    private static final byte[] ARGUS_SIGN_KEY = hex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304");
    private static final byte[] ARGUS_AES_KEY = hex("d31e3718288a1027baab59f146a09a9c");
    private static final byte[] ARGUS_AES_IV = hex("ea180a0336ed352fcd24e4d50018ae54");
    private static final byte[] MAGIC8 = {(byte) 0xf2, (byte) 0xf7, (byte) 0xfc, (byte) 0xff,
            (byte) 0xf2, (byte) 0xf7, (byte) 0xfc, (byte) 0xff};
    private static final byte[] TAIL9 = {(byte) 0xea, (byte) 0xfb, 0x2c, (byte) 0xfe,
            (byte) 0x85, 0x68, 0x51, (byte) 0x91, 0x54};
    private static final byte[] PREFIX2 = {(byte) 0xf2, (byte) 0x81};
    private static final long SIMON_Z = 0x3DC94C3A046D678BL;

    private static final SecureRandom RND = new SecureRandom();

    private FqSign() {}

    // ------------------------------------------------------------ 对外入口

    /** 生成一次请求所需的全部签名 header。query 必须是 URL 上 '?' 之后的原始串。 */
    public static Map<String, String> headers(String query, byte[] body, String installId) {
        long ts = System.currentTimeMillis() / 1000L;
        byte[] ladonRandom = new byte[4];
        RND.nextBytes(ladonRandom);
        int argusRand = RND.nextInt();
        long aid = AID;
        String aidStr = queryParam(query, "aid");
        if (aidStr.length() > 0) {
            try { aid = Long.parseLong(aidStr); } catch (Exception ignored) {}
        }
        String stubHex = "";
        if (body != null && body.length > 0) stubHex = hexOf(md5(body));
        String deviceId = queryParam(query, "device_id");

        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("x-ss-req-ticket", Long.toString(ts * 1000L));
        h.put("x-khronos", Long.toString(ts));
        h.put("x-ladon", ladonEncrypt(ts, LICENSE_ID, aid, ladonRandom));
        h.put("x-argus", argusGetSign(query, stubHex, ts, aid, LICENSE_ID,
                deviceId, installId == null ? "" : installId, argusRand));
        if (stubHex.length() > 0) h.put("x-ss-stub", stubHex);
        return h;
    }

    /** Go url.Values.Get 语义：取第一个同名参数（值已 URL 解码）。 */
    public static String queryParam(String query, String name) {
        if (query == null) return "";
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            if (k.equals(name)) {
                String v = i < 0 ? "" : pair.substring(i + 1);
                try {
                    return java.net.URLDecoder.decode(v, "UTF-8");
                } catch (Exception e) {
                    return v;
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------ SM3

    private static final int[] SM3_IV = {
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
            0xa96f30bc, 0x163138aa, (int) 0xe38dee4d, (int) 0xb0fb0e4e};

    private static int p0(int x) { return x ^ Integer.rotateLeft(x, 9) ^ Integer.rotateLeft(x, 17); }
    private static int p1(int x) { return x ^ Integer.rotateLeft(x, 15) ^ Integer.rotateLeft(x, 23); }

    private static int[] sm3Cf(int[] v, byte[] block, int off) {
        int[] w = new int[68];
        for (int i = 0; i < 16; i++) {
            int p = off + i * 4;
            w[i] = ((block[p] & 255) << 24) | ((block[p + 1] & 255) << 16)
                    | ((block[p + 2] & 255) << 8) | (block[p + 3] & 255);
        }
        for (int j = 16; j < 68; j++) {
            w[j] = p1(w[j - 16] ^ w[j - 9] ^ Integer.rotateLeft(w[j - 3], 15))
                    ^ Integer.rotateLeft(w[j - 13], 7) ^ w[j - 6];
        }
        int[] w1 = new int[64];
        for (int j = 0; j < 64; j++) w1[j] = w[j] ^ w[j + 4];
        int a = v[0], b = v[1], c = v[2], d = v[3];
        int e = v[4], f = v[5], g = v[6], h = v[7];
        for (int j = 0; j < 64; j++) {
            int tj = j < 16 ? 0x79cc4519 : 0x7a879d8a;
            int ss1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + e
                    + Integer.rotateLeft(tj, j), 7);
            int ss2 = ss1 ^ Integer.rotateLeft(a, 12);
            int ff = j < 16 ? (a ^ b ^ c) : ((a & b) | (a & c) | (b & c));
            int gg = j < 16 ? (e ^ f ^ g) : ((e & f) | (~e & g));
            int tt1 = ff + d + ss2 + w1[j];
            int tt2 = gg + h + ss1 + w[j];
            d = c;
            c = Integer.rotateLeft(b, 9);
            b = a;
            a = tt1;
            h = g;
            g = Integer.rotateLeft(f, 19);
            f = e;
            e = p0(tt2);
        }
        return new int[]{a ^ v[0], b ^ v[1], c ^ v[2], d ^ v[3],
                e ^ v[4], f ^ v[5], g ^ v[6], h ^ v[7]};
    }

    public static byte[] sm3(byte[] msg) {
        int n = msg.length;
        int reserve = (n % 64) + 1;
        int end = 56 >= reserve ? 56 : 120;
        byte[] padded = new byte[reserve + end - reserve + 8 + n];
        int len = 0;
        System.arraycopy(msg, 0, padded, len, n);
        len += n;
        padded[len++] = (byte) 0x80;
        len += end - reserve;
        long bits = (long) n * 8L;
        for (int i = 7; i >= 0; i--) padded[len++] = (byte) (bits >>> (i * 8));
        int[] v = SM3_IV.clone();
        for (int i = 0; i + 64 <= len; i += 64) v = sm3Cf(v, padded, i);
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (v[i] >>> 24);
            out[i * 4 + 1] = (byte) (v[i] >>> 16);
            out[i * 4 + 2] = (byte) (v[i] >>> 8);
            out[i * 4 + 3] = (byte) v[i];
        }
        return out;
    }

    private static byte[] sm3Prefix6(byte[] msg) {
        byte[] h = sm3(msg);
        byte[] out = new byte[6];
        System.arraycopy(h, 0, out, 0, 6);
        return out;
    }

    // ------------------------------------------------------------ Simon 128/128, 72 rounds

    private static long[] simonKeyExpansion(long[] k4) {
        long[] k = new long[72];
        System.arraycopy(k4, 0, k, 0, 4);
        for (int i = 4; i < 72; i++) {
            long tmp = Long.rotateRight(k[i - 1], 3);
            tmp ^= k[i - 3];
            tmp ^= Long.rotateRight(tmp, 1);
            long bit = (SIMON_Z >>> ((i - 4) % 62)) & 1L;
            k[i] = (~k[i - 4]) ^ tmp ^ bit ^ 3L;
        }
        return k;
    }

    private static long[] simonEnc(long x0, long x1, long[] k) {
        long[] keys = simonKeyExpansion(k);
        for (long rk : keys) {
            long tmp = x1;
            long f = Long.rotateLeft(x1, 1) & Long.rotateLeft(x1, 8);
            x1 = x0 ^ f ^ Long.rotateLeft(x1, 2) ^ rk;
            x0 = tmp;
        }
        return new long[]{x0, x1};
    }

    private static long[] simonWords() {
        long[] w = new long[4];
        for (int i = 0; i < 4; i++) w[i] = le64(ARGUS_SIGN_KEY, i * 8);
        return w;
    }

    private static byte[] simonEncryptPadded(byte[] data) {
        byte[] out = data.clone();
        long[] k = simonWords();
        for (int i = 0; i + 16 <= data.length; i += 16) {
            long[] ct = simonEnc(le64(data, i), le64(data, i + 8), k);
            putLe64(out, i, ct[0]);
            putLe64(out, i + 8, ct[1]);
        }
        return out;
    }

    // ------------------------------------------------------------ 编码型 protobuf

    private static void writeVarint(ByteArrayOutputStream dst, long v) {
        long x = v & 0xFFFFFFFFL;
        while (x >= 128) {
            dst.write((int) ((x & 127) | 128));
            x >>>= 7;
        }
        dst.write((int) (x & 127));
    }

    static final class ProtoBuf {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        ProtoBuf putVarint(int idx, long v) {
            writeVarint(buf, ((long) idx << 3) | 0L);
            writeVarint(buf, v);
            return this;
        }

        ProtoBuf putBlob(int idx, byte[] data) {
            writeVarint(buf, ((long) idx << 3) | 2L);
            writeVarint(buf, data.length);
            buf.write(data, 0, data.length);
            return this;
        }

        ProtoBuf putUtf8(int idx, String s) {
            return putBlob(idx, s.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        }

        ProtoBuf putProto(int idx, ProtoBuf nested) {
            return putBlob(idx, nested.toBuf());
        }

        byte[] toBuf() { return buf.toByteArray(); }
    }

    // ------------------------------------------------------------ Argus

    private static byte[] argusBodyHash(String stubHex) {
        if (stubHex == null || stubHex.length() == 0) return sm3Prefix6(new byte[16]);
        return sm3Prefix6(hex(stubHex));
    }

    private static byte[] argusQueryHash(String query) {
        if (query == null || query.length() == 0) return sm3Prefix6(new byte[16]);
        return sm3Prefix6(query.getBytes(java.nio.charset.Charset.forName("UTF-8")));
    }

    private static ProtoBuf buildBean(String query, String stubHex, long ts, long aid,
                                     long licenseId, String deviceId, String installId,
                                     int randField) {
        ProtoBuf b = new ProtoBuf();
        b.putVarint(1, 1077940818L);
        b.putVarint(2, 2L);
        b.putVarint(3, randField);
        b.putUtf8(4, Long.toString(aid));
        b.putUtf8(5, deviceId);
        b.putUtf8(6, Long.toString(licenseId));
        b.putUtf8(7, queryParam(query, "version_name"));
        b.putUtf8(8, "v04.04.05-ov-android");
        b.putVarint(9, 134744640L);
        b.putBlob(10, new byte[8]);
        b.putVarint(11, 0L);
        b.putVarint(12, ts << 1);
        b.putBlob(13, argusBodyHash(stubHex));
        b.putBlob(14, argusQueryHash(query));
        ProtoBuf sub15 = new ProtoBuf();
        sub15.putVarint(1, 1L).putVarint(2, 1L).putVarint(3, 1L).putVarint(7, 0xC792ECCCL);
        b.putProto(15, sub15);
        b.putUtf8(16, installId);
        b.putUtf8(20, "none");
        b.putVarint(21, 738L);
        ProtoBuf sub23 = new ProtoBuf();
        sub23.putUtf8(1, "NX551J").putVarint(2, 8196L).putVarint(4, 0x80E0D800L);
        b.putProto(23, sub23);
        b.putVarint(25, 2L);
        return b;
    }

    public static String argusGetSign(String query, String stubHex, long ts, long aid,
                                     long licenseId, String deviceId, String installId,
                                     int randField) {
        if (aid == 0) aid = AID;
        if (licenseId == 0) licenseId = LICENSE_ID;
        byte[] pb = pkcs7(buildBean(query, stubHex, ts, aid, licenseId,
                deviceId, installId, randField).toBuf());
        byte[] encPb = simonEncryptPadded(pb);
        byte[] un = new byte[8 + encPb.length + 9];
        System.arraycopy(MAGIC8, 0, un, 0, 8);
        System.arraycopy(encPb, 0, un, 8, encPb.length);
        System.arraycopy(TAIL9, 0, un, 8 + encPb.length, 9);
        byte[] buf = new byte[un.length];
        System.arraycopy(MAGIC8, 0, buf, 0, 8);
        for (int i = 8; i < un.length; i++) buf[i] = (byte) (un[i] ^ MAGIC8[i % 8]);
        reverse(buf);
        byte[] framed = new byte[buf.length + 2];
        System.arraycopy(buf, 0, framed, 0, buf.length);
        framed[buf.length] = 'a';
        framed[buf.length + 1] = 'o';
        byte[] enc = aesCbc(pkcs7(framed));
        byte[] raw = new byte[2 + enc.length];
        System.arraycopy(PREFIX2, 0, raw, 0, 2);
        System.arraycopy(enc, 0, raw, 2, enc.length);
        return b64Encode(raw);
    }

    // ------------------------------------------------------------ Ladon

    private static byte[] ladonExpandHashTable(String md5Hex) {
        byte[] ht = new byte[288];
        byte[] raw = md5Hex.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        System.arraycopy(raw, 0, ht, 0, Math.min(32, raw.length));
        java.util.LinkedList<Long> temp = new java.util.LinkedList<Long>();
        for (int i = 0; i < 4; i++) temp.add(le64(ht, i * 8));
        long bufB0 = temp.removeFirst();
        long bufB8 = temp.removeFirst();
        for (int i = 0; i < 34; i++) {
            long x9 = bufB0;
            long x8 = Long.rotateRight(bufB8, 8);
            x8 += x9;
            x8 ^= i;
            temp.addLast(x8);
            x8 ^= Long.rotateLeft(x9, 3);
            putLe64(ht, (i + 1) * 8, x8);
            bufB0 = x8;
            bufB8 = temp.removeFirst();
        }
        return ht;
    }

    private static byte[] ladonEncryptBlock(byte[] ht, byte[] block, int off) {
        long d0 = le64(block, off);
        long d1 = le64(block, off + 8);
        for (int i = 0; i < 34; i++) {
            long hash = le64(ht, i * 8);
            d1 = hash ^ (d0 + Long.rotateRight(d1, 8));
            d0 = d1 ^ Long.rotateLeft(d0, 3);
        }
        byte[] out = new byte[16];
        putLe64(out, 0, d0);
        putLe64(out, 8, d1);
        return out;
    }

    public static String ladonEncrypt(long khronos, long lcId, long aid, byte[] randomBytes) {
        byte[] data = (khronos + "-" + lcId + "-" + aid)
                .getBytes(java.nio.charset.Charset.forName("UTF-8"));
        byte[] keygen = new byte[randomBytes.length + Long.toString(aid).length()];
        System.arraycopy(randomBytes, 0, keygen, 0, randomBytes.length);
        byte[] aidBytes = Long.toString(aid).getBytes(java.nio.charset.Charset.forName("UTF-8"));
        System.arraycopy(aidBytes, 0, keygen, randomBytes.length, aidBytes.length);
        String md5Hex = hexOf(md5(keygen));
        byte[] ht = ladonExpandHashTable(md5Hex);
        int size = data.length;
        int newSize = size + (16 - size % 16);
        int pad = newSize - size;
        byte[] input = new byte[newSize];
        java.util.Arrays.fill(input, (byte) pad);
        System.arraycopy(data, 0, input, 0, size);
        byte[] enc = new byte[newSize];
        for (int i = 0; i < newSize; i += 16) {
            byte[] blk = ladonEncryptBlock(ht, input, i);
            System.arraycopy(blk, 0, enc, i, 16);
        }
        byte[] out = new byte[randomBytes.length + enc.length];
        System.arraycopy(randomBytes, 0, out, 0, randomBytes.length);
        System.arraycopy(enc, 0, out, randomBytes.length, enc.length);
        return b64Encode(out);
    }

    // ------------------------------------------------------------ 通用工具

    private static byte[] pkcs7(byte[] data) {
        int n = 16 - (data.length % 16);
        byte[] out = new byte[data.length + n];
        System.arraycopy(data, 0, out, 0, data.length);
        java.util.Arrays.fill(out, data.length, out.length, (byte) n);
        return out;
    }

    private static byte[] aesCbc(byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ARGUS_AES_KEY, "AES"),
                    new IvParameterSpec(ARGUS_AES_IV));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("aes-cbc: " + e);
        }
    }

    public static byte[] md5(byte[] data) {
        try { return MessageDigest.getInstance("MD5").digest(data); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static byte[] sha512(byte[] data) {
        try { return MessageDigest.getInstance("SHA-512").digest(data); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    public static String hexOf(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >>> 4) & 15, 16));
            sb.append(Character.forDigit(x & 15, 16));
        }
        return sb.toString();
    }

    public static String b64Encode(byte[] data) {
        final char[] T = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int a = data[i] & 255;
            int b = i + 1 < data.length ? data[i + 1] & 255 : 0;
            int c = i + 2 < data.length ? data[i + 2] & 255 : 0;
            int n = (a << 16) | (b << 8) | c;
            sb.append(T[(n >>> 18) & 63]).append(T[(n >>> 12) & 63]);
            sb.append(i + 1 < data.length ? T[(n >>> 6) & 63] : '=');
            sb.append(i + 2 < data.length ? T[n & 63] : '=');
        }
        return sb.toString();
    }

    public static byte[] b64Decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] acc = new int[]{0, 0};
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int v;
            if (ch >= 'A' && ch <= 'Z') v = ch - 'A';
            else if (ch >= 'a' && ch <= 'z') v = ch - 'a' + 26;
            else if (ch >= '0' && ch <= '9') v = ch - '0' + 52;
            else if (ch == '+') v = 62;
            else if (ch == '/') v = 63;
            else continue;
            acc[0] = (acc[0] << 6) | v;
            acc[1] += 6;
            if (acc[1] >= 8) {
                acc[1] -= 8;
                out.write((acc[0] >>> acc[1]) & 255);
            }
        }
        return out.toByteArray();
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 255L);
        return v;
    }

    private static void putLe64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (i * 8));
    }

    private static void reverse(byte[] b) {
        for (int i = 0, j = b.length - 1; i < j; i++, j--) {
            byte t = b[i]; b[i] = b[j]; b[j] = t;
        }
    }
}
