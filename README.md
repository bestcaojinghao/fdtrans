# FDTrans — 通用实时机翻（LSPosed）

LSPosed 模块：把目标应用界面里的英文**直接替换成简体中文**，没有悬浮窗、没有悬浮球、不改变布局。
最初为 F-Droid 而写，1.0 起改成**通用**：任何加进作用域的应用都能翻（F-Droid、ShizuStore、GitHub Store 等已实测）。

## 成品

- 模块 APK：`fdtrans/mod/FDTrans-1.0.1.apk`
- 包名：`com.kxxy.fdtrans`，versionName 1.0.1
- 签名：v3，证书 DN `CN=FQ`

## 支持的界面类型

| 界面 | hook 点 | 说明 |
|---|---|---|
| Jetpack Compose | `androidx.compose.ui.text.AnnotatedString` 全部构造器 | 改写 `text` 字段并清 span |
| View（TextView 系） | `TextView.setText(CharSequence[, BufferType])` | 只替换纯 `String`，带 span 的放过 |

## 原理

Compose 的每段文本最终变成 `AnnotatedString`，View 的每段文本最终走 `setText`。模块在目标进程里 hook 这两个点，拿到原文：

- 命中缓存 → 直接改写（Compose 改 `text` 字段，View 直接替换 `setText` 的实参），界面立刻是中文
- 未命中 → 丢后台线程翻译，完成后节流重建当前 Activity（打字时、键盘弹起时不重建）

**改 text 必须同时清 span**：`AnnotatedString.spanStyles`/`paragraphStyles` 的区间按原文长度算，译文变短后越界，Compose 抛
`IndexOutOfBoundsException: setSpan (0 ... 140) ends beyond length 54`（早期"一直在崩溃"的根因）。
`Main.dropSpans()` 把 `spanStylesOrNull`/`paragraphStylesOrNull` 置 null、`annotations` 置空解决。

## 翻译链路

- 引擎：Bing 免费网页接口 `cn.bing.com/ttranslatev3`（`usedLLM:true`，失败自动回退 MyMemory）
- 批量：多条短文本用 ` ||| ` 拼一次请求，返回按同样分隔符切回
- 三级缓存：LRU 内存 → 磁盘 `fdtrans-cache.tsv`（**各目标应用自己的私有目录**）→ 网络
- 过滤：只翻含拉丁字母、不含中日韩、2–2000 字、非 URL/包名/版本号/大小串（`13 MB`）的文本
- 专名：`Config.skipNames=true` 时显示成 `原名（中文）`，如 `ColorBlendr -> ColorBlendr（色彩混合器）`

## 用法（通用）

1. 装模块 APK，在 LSPosed 里启用本模块（不用手动勾作用域，下一步的按钮会改配置）
2. 打开「FDTrans 设置」→「翻译哪个应用」→ 输入目标包名（如 `me.timschneeberger.shizustore`）→ 点「加入」
   - 走 root 直接改 `/data/adb/lspd/config/modules_config.db` 的 `scope` 表，然后重启 lspd（约 20 秒）
   - 首次会弹一次 root 授权
3. 重开目标应用，界面英文即变中文

批量预翻译（目前只针对 F-Droid 的本地索引库）：`预翻译整个仓库（名称+摘要）`，进度条实时显示。
注意：**冷启动后的后台进程在部分 ROM 上没有网络**，所以预翻译要在目标应用至少打开过一次之后再跑；模块遇到无网会显示「等待网络」并自动续跑。

## F-Droid 专属说明（0.x 时期）

## 用法

1. 安装 `FDTrans-0.3.apk`
2. LSPosed 里启用模块，作用域勾选 **F-Droid**
3. 打开「F-Droid 实时翻译」图标进设置页，确认「启用实时翻译」是开的
4. 打开 F-Droid —— 应用名、摘要会变中文

设置页能做的事：开关实时翻译（会立刻拉起 F-Droid 并广播新状态，目标进程不在时广播会丢）、清空翻译缓存、直接打开 F-Droid。

## 开关是怎么跨进程生效的

模块进程和 F-Droid 进程 uid 不同，跨进程读模块私有 prefs 会被权限挡（文件 660），用 ContentResolver 查自定义 provider 又被 Android 11+ 的 package visibility 挡住（`IllegalArgumentException: Unknown authority`）。最终方案：

- 权威值存在 **F-Droid 自己的** SharedPreferences（`fdtrans-hook.xml`）
- 设置页改动后先 `startActivity` 拉起 F-Droid，1.8 秒后发显式广播 `com.kxxy.fdtrans.CFG`（`setPackage("org.fdroid.fdroid")`）
- F-Droid 侧在 `Application.onCreate` 读 prefs，并注册动态 receiver 接收后续变更，收到即落盘

## 实测证据

关机重启后完整复跑一遍（`127.0.0.1:5555`，PJZ110 / Android 16）：

```
I/FDTrans: module loaded in org.fdroid.fdroid
I/FDTrans: AnnotatedString found: class androidx.compose.ui.text.AnnotatedString
I/FDTrans: prefs loaded enabled=true clearAt=0
I/FDTrans: cfg receiver registered
I/FDTrans: cache loaded: 9
I/FDTrans: cache dir = /data/user/0/org.fdroid.fdroid/files enabled=true
I/FDTrans: REPLACED: [Librera Reader] -> [Librera 阅读器]
```

界面 dump（详情页）：

```
text="Librera 阅读器"        ← 原文 "Librera Reader"
text="书籍和 PDF 阅读器"
```

关掉开关后同一页面：`text="Librera Reader"`，`prefs loaded enabled=false`，`REPLACED=0`。

稳定性：F-Droid 连续前台 35 秒，`FATAL EXCEPTION` 计数 0，进程 PID 不变，REPLACED 21 条。

## 未做 / 已知限制

- 长描述要展开后才加载，展开那一下会看到英文，下次进入才中文
- 每次新译文到达会 `recreate()` 一次界面（限流 2.5 秒 / 窗口 180 秒），视觉上闪一下
- 专有名词（Buddy→巴迪、Tuta Mail 等）会被直译，有意保留原名的可在 `Main.DICT` 里加映射
- 未做多语言目标（固定 zh-CN）

## 重建

```bash
cd fdtrans/mod && bash build.sh FDTrans-0.3.apk
```

javac → d8（必须 `grep -v '/de/robv/'` 剔除 Xposed API 类，否则 LSPosed 拒绝加载）→ aapt2 link → 塞 dex → zipalign → apksigner。

装完必须更新 LSPosed 的 apk_path 并重启 lspd，否则注入的是旧 APK：

```bash
P=$(pm path com.kxxy.fdtrans | sed 's/package://')
sqlite3 /data/adb/lspd/config/modules_config.db \
  "UPDATE modules SET apk_path='$P' WHERE module_pkg_name='com.kxxy.fdtrans';"
kill -9 $(pidof lspd); rm -f /data/adb/lspd/monitor
setsid sh /data/local/tmp/runlspd.sh >/data/local/tmp/lspd.log 2>&1 < /dev/null &
```

`runlspd.sh` 必须带上 shell 的整套环境变量（`BOOTCLASSPATH`/`ANDROID_ART_ROOT` 等），并先 `cd /data/adb/modules/zygisk_lsposed`，否则 lspd 静默 `exit(0)`。
