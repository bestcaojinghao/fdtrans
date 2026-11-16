# FDTrans — 通用实时机翻（LSPosed）

LSPosed 模块：把目标应用界面里的英文**直接替换成简体中文**，没有悬浮窗、没有悬浮球、不改变布局。
最初为 F-Droid 而写，1.0 起改成**通用**：任何加进作用域的应用都能翻（F-Droid、ShizuStore、GitHub Store 等已实测）。

## 成品

- 模块 APK：`fdtrans/mod/FDTrans-1.8.0.apk`
- 包名：`com.kxxy.fdtrans`，versionName 1.8.0
- 签名：v3，证书 DN `CN=FQ`

## 支持的界面类型

实测归纳出**四条文本通路**，模块四条全挂（同一进程只挂能挂上的那几条）：

| 通路 | hook 点 | 适用 |
|---|---|---|
| Compose 富文本 | `androidx.compose.ui.text.AnnotatedString` 全部构造器 | 带 span/标注的文本；改 `text` 字段并清 span |
| Compose 纯字符串 | `androidx.compose.foundation.text.modifiers.ParagraphLayoutCache` 构造器 | Compose 1.9 `BasicText(String)` **不造 AnnotatedString**，直接 `(String,TextStyle,...)` 进排版（Vanillify 实测只有这条中） |
| Compose 段落 | `androidx.compose.ui.text.platform.AndroidParagraphIntrinsics` 构造器 | 老版本 Compose 走这条；改 `args[0]` 并清 `args[2]/args[3]` 的 span 列表 |
| View（TextView 系） | `TextView.setText(CharSequence[, BufferType])` | 只替换纯 `String`，带 span 的放过 |

Compose 类被 R8 混淆（名字变成 `l1.g` 之类）时按名字找不到，模块会**解析目标 APK 的 dex**（`Dex.java` 真解析 string_ids/type_ids/proto_ids/method_ids），按「构造器签名形状」反查 AnnotatedString 等价类并 hook 之（Komi Store / GitHub Store 实测 `struct hooked: class l1.g`）。

## 原理

Compose 的每段文本最终变成 `AnnotatedString`，View 的每段文本最终走 `setText`。模块在目标进程里 hook 这两个点，拿到原文：

- 命中缓存 → 直接改写（Compose 改 `text` 字段，View 直接替换 `setText` 的实参），界面立刻是中文
- 未命中 → 丢后台线程翻译，完成后节流重建当前 Activity（打字时、键盘弹起时不重建）

**改 text 必须同时清 span**：`AnnotatedString.spanStyles`/`paragraphStyles` 的区间按原文长度算，译文变短后越界，Compose 抛
`IndexOutOfBoundsException: setSpan (0 ... 140) ends beyond length 54`（早期"一直在崩溃"的根因）。
`Main.dropSpans()` 把 `spanStylesOrNull`/`paragraphStylesOrNull` 置 null、`annotations` 置空解决。

## 检测：这个应用能不能翻

每个目标应用启动后，模块会把探测结果写进它自己的私有目录 `files/fdtrans-probe.txt`：

```
pkg=com.vanillify.app
named=1 struct=0 tv=true para=1 textcache=2
probe=Compose 类 793（标准命名） · WebView 类 0
```

- `named/struct`：按名字找到的 Compose 类数 / 按 dex 形状找到的等价类数
- `tv`：TextView 通路是否挂上
- `textcache`：ParagraphLayoutCache 通路是否挂上
- `probe`：dex 里 Compose/WebView 类数量、是否含 flutter/unity/react-native 原生库

模块界面里每行应用会显示 `✓ 可翻` / `⚠ 可能翻不到`，详情页有「重新检测能否翻译」按钮；
`debug` 开关打开后 logcat 里会有 `DIAG` / `skip#N (rule)` 级别的诊断。

实测结论：
- `com.vanillify.app`：只有 `ParagraphLayoutCache` 通路（Compose 1.9 纯字符串），补上后才翻得到
- `make.more.r2d2.cellular_pro`：无 Compose，纯 TextView 通道，`TVIEW=55` 正常
- `me.timschneeberger.shizustore` / `org.fdroid.fdroid`：标准命名 Compose，两条 Compose 通路都挂上
- `zed.rainxch.githubstore`：Compose 被混淆，靠 dex 形状匹配（`struct=1`）

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

## 更新日志

- **1.10.6** 修复详情页反复重建（每个界面类重建上限 4 次 / 90 秒、最小间隔 4 秒）；译文缓存去重；代翻服务分批失败对半重试
- **1.10.x** 新增后台「代翻服务」（Bridge）：没联网权限的应用也能翻；`su -mm` 读目标应用私有目录
- **1.9.x** 无网络权限的进程自动改为把待翻文本 spool 给模块 app 代翻
- **1.8.0** 四条文本通路（AnnotatedString / 结构匹配 / ParagraphLayoutCache / TextView）+ 应用「能不能翻」检测
- **1.3.1** 通用主界面 + 实时进度条
- **1.0.0** 从 F-Droid 专用改为通用（作用域内任意应用）
- **0.9.0** 首次发布：F-Droid 英文界面机翻
