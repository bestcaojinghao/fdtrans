# FDTrans — F-Droid 界面中英实时翻译

一个 LSPosed/Xposed 模块：把 F-Droid 里的英文**就地**翻成简体中文。
不加悬浮球、不加悬浮窗、不改布局——直接改 Compose 文本层，译文出现在原文的位置。

```
Buddy                              →  (保持英文，专名不译)
Bahn-Vorhersage                    →  火车时刻表预测
CloudSound - SoundCloud facilitator for Podcini.A
                                   →  CloudSound - Podcini.A 的 SoundCloud 助手
```

## 原理

F-Droid 2.x 是 Jetpack Compose 应用，界面上所有文字最终都会变成一个
`androidx.compose.ui.text.AnnotatedString`。模块 hook 它的所有构造器，
在构造完成后把 `text` 字段替换成译文：

1. `AnnotatedString` 构造 → 取首个 `String` 参数作为原文
2. 命中缓存 → 直接写回 `text` 字段
3. 未命中 → 后台线程送翻译，完成后 `Activity.recreate()` 让界面重排版
4. 替换时必须清掉 `spanStyles` / `paragraphStyles` / `annotations`——
   这些 Range 是按原文长度算的，长度一变 Compose 建布局就会抛
   `IndexOutOfBoundsException: setSpan (0 ... 140) ends beyond length 54`

刷新不是每次翻译完都重建：译文安静 0.7 秒后才重建一次（debounce，最长等 6 秒），
检测到输入框获得焦点或输入法弹出时跳过重建，避免打断打字。

## 翻译引擎

默认真实引擎是 **Bing 免费网页接口**（`cn.bing.com/ttranslatev3`）：
从 `/translator` 页面抓 `IG` / `key` / `token`，POST 表单，token 55 分钟刷新一次，
失效时重取。失败自动回退 MyMemory。

批量吞吐：多条短文本用 ` ||| ` 拼成一次请求（单次可吃 4000 字符），
译文再按分隔符切回；分段数不匹配则回退逐条翻译。

## 全量预热

F-Droid 本地索引 `fdroid_db` 里 `localizedSummary` / `localizedName` 是设备语言
解析后的文本。模块跑在 F-Droid 进程内，所以能直接只读打开这个库，把英文条目
批量翻好写进缓存（`files/fdtrans-cache.tsv`）。

实测（约 5000 应用的真实索引）：

| 项 | 数量 | 字符量 |
|---|---|---|
| 索引应用 | 5008 | — |
| 应用名仍英文 | 4788 | 63,391 |
| 摘要仍英文 | 3543 | 197,763 |
| 预热结果 | 5612 条 / 221 秒 | 缓存 5048 条 |

设置页两个按钮：

- **预翻译整个仓库（名称+摘要）** —— 几分钟，跑完列表页全中文
- **预翻译详细描述（慢）** —— 描述量级大，为详情页备货

## 安装

前提：Magisk + LSPosed（Zygisk），已安装 F-Droid 2.x。

```bash
# 1. 装模块
adb install -r FDTrans-0.9.apk     # 或 root: pm install -r -t FDTrans-0.9.apk

# 2. 在 LSPosed 管理器里启用模块，作用域勾选 org.fdroid.fdroid

# 3. 重启 F-Droid（模块只在目标进程启动时注入）
```

命令行注册（LSPosed 管理器不方便时）：

```bash
P=$(pm path com.kxxy.fdtrans | sed 's/package://')
sqlite3 /data/adb/lspd/config/modules_config.db "
  INSERT OR REPLACE INTO modules(module_pkg_name,apk_path) VALUES('com.kxxy.fdtrans','$P');
  INSERT OR REPLACE INTO modules_state(module_pkg_name,user_id,enabled,scope_request_blocked)
    VALUES('com.kxxy.fdtrans',0,1,0);
  INSERT OR REPLACE INTO scope(module_pkg_name,app_pkg_name,user_id)
    VALUES('com.kxxy.fdtrans','org.fdroid.fdroid',0);"
kill -9 $(pidof lspd)   # 需要时手动拉起见下
```

验证注入：

```bash
logcat -s FDTrans:*
# I FDTrans: module loaded in org.fdroid.fdroid
# I FDTrans: AnnotatedString found: class androidx.compose.ui.text.AnnotatedString
# I FDTrans: REPLACED: [Librera Reader] -> [Librera 阅读器]
```

## 从源码构建（不需要 Android Studio）

`build.sh` 用 `javac + d8 + aapt2 + zipalign + apksigner` 手工打包：

```bash
export ANDROID_JAR=/path/to/android.jar        # platforms/android-35/android.jar
export BUILD_TOOLS=/path/to/build-tools
bash build.sh FDTrans.apk
```

两个必须注意的点：

1. **d8 输入要排除 `de/robv/**`**（Xposed API 的编译期 stub）。
   打进去的话 LSPosed 会拒绝加载：
   `The Xposed API classes are compiled into the module's APK.`
2. `stubs/` 只在编译期参与，不进 dex。

## 已知限制

- 只对 `org.fdroid.fdroid` 生效，别的 Compose 应用换个包名就能复用，但要自己加作用域
- 单字应用名默认不翻（`skipNames`）——`Buddy` → `巴迪` 这种没必要；开关可关掉
- 译文长度变化后 Compose 的 span 信息会被丢弃，纯文本替换，富文本样式（粗体/链接）
  在译文里会丢失
- 描述文本量大（千万字符级），默认按需翻；全量预热要跑几小时
- LSPosed 的配置服务 `lspd` 挂了模块就不会注入，表现为"装了没反应"

## 许可

MIT
