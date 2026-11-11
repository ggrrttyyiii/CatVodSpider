# 什锦铺短剧 独立直连版（csp_Shipu）

纯 Java 直连 fqnovel 上游，不依赖原 APP、Rust 本地服务、Flutter FFI 或 Frida 桥。

## 文件

| 文件 | 作用 |
| --- | --- |
| `FqSign.java` | SM3 / Simon128-128 / Ladon / Argus551 / protobuf / `x-ss-stub` 签名栈 |
| `FqUpstream.java` | 纯网络设备注册、`/api/v1/*` 到上游端点映射、播放选档（1080p phoenix fallback） |
| `FqCenc.java` | `spade_a` 推 content key、CENC MP4 解密、8 段并发下载、本地明文 HTTP 服务、下一集预取 |
| `Shipu.java` | TVBox/FongMi `csp_Shipu` 主类：首页、分类真分页、详情、搜索、播放 |
| `jar/Shipu_standalone.jar` | 含 `classes.dex` 的成品，供 `DexClassLoader` 直接加载 |

## 配置

```json
{
  "key": "shipu",
  "name": "什锦铺短剧",
  "type": 3,
  "api": "csp_Shipu",
  "searchable": 1,
  "quickSearch": 0,
  "filterable": 1,
  "timeout": 30,
  "ext": "{\"limit\":20}"
}
```

`ext` 可选注入已注册设备，跳过启动时的设备注册：

```json
{"limit":20,"deviceId":"...","installId":"..."}
```

## 构建

```bash
javac -encoding UTF-8 -cp "json-20180813.jar;stubs.jar" -d out \
      FqSign.java FqUpstream.java FqCenc.java Shipu.java

java -cp d8.jar com.android.tools.r8.D8 \
     --lib android.jar --lib json-20180813.jar --lib stubs.jar \
     --min-api 21 --output dexout out/com/github/catvod/spider/*.class
```

把 `dexout/classes.dex` 替换进 `jar/Shipu_standalone.jar` 即可（JAR 内只需
`META-INF/MANIFEST.MF`、`assets/push.html`、`classes.dex`）。

## 实现要点

- **设备注册**：`log.snssdk.com/service/2/device_register/` + `tt_encrypt`，纯网络；
  分类/feed/目录/播放可用随机设备，搜索接口需要注册过的设备身份。
- **播放**：`POST /novel/player/video_model/v1/`，`video_list` 各档 `main_url` 为
  base64 或 spade 密文；CDN 对任何 `Referer` 返回 403，播放请求不得带 Referer。
- **CENC**：上游 MP4 为 `cenc-aes-ctr`，`mdat` 密文；解密后需把 `encv` 改 `hvc1`、
  `enca` 改 `mp4a`、`sinf` 清空为 `free`。成品由进程内 `127.0.0.1:18924` 提供，
  支持 Range，播放器不直连 CDN。
- **1080p**：shortplay 接口最高 720p；走 phoenix
  `POST /novel/player/multi_video_model/v1/` → `fallback_api` → `video_5`，并用
  `key_seed` 解 spade URL。
- **切集**：8 段并发 Range 下载（单连接被 CDN 限速），当前集发布完成后后台预取下一集，
  命中缓存直接返回本地地址。
- **分类真分页**：多源累积卡片池（搜索热词 + feed + offset 变体），每页 20 条，
  `total` 返回池内总数，源未耗尽时保证 `pagecount > page`。

## 说明

仅用于个人学习与自用影视聚合，请勿用于商业用途。
