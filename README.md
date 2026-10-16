<div align="center">
<img width="125" height="125" src="https://github.com/gedoor/legado/raw/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="legado"/>

# Legado（体积优化分支）

**一款免费的 Android 平台开源小说阅读器**

基于 [gedoor/legado](https://github.com/gedoor/legado) 的精简 / 体积优化分支：**release APK 19.25 MB → 7.13 MB（−63%）**，仅保留 arm64-v8a。
</div>

## 功能特点

- 📖 支持多种阅读格式：TXT、EPUB、MOBI、PDF、UMD 等
- 🌐 自定义书源，畅享全网小说
- 🎨 丰富的主题和排版设置
- 📑 智能目录解析
- 🔍 全文搜索
- 📤 阅读进度同步
- 📚 书架管理
- 🎧 听书功能

> **注意**：本应用不提供任何内容，需要自行添加书源。
> 与上游的差异见下方「本分支的改动」，动手前请先看「行为变化」。

## 本分支的改动

### 1. 体积优化（release APK 19.25 MB → 7.13 MB）

| 阶段 | APK | 做了什么 |
|---|---|---|
| 基线（上游配置） | 19.25 MB | 4 个 ABI 的原生库合计 9,759 KB |
| 仅保留 arm64-v8a | 12.09 MB | `defaultConfig.ndk.abiFilters`；同时删掉死文件与死配置 |
| 移除 sora-editor 代码编辑器 | 10.15 MB | 源码已无引用；连带去掉 jcodings 的 Unicode 表（2,951 KB）与 textmate 资源 |
| 只打包中文资源 + 关闭依赖元数据 | 9.08 MB | `androidResources.localeFilters`（`resources.arsc` 2,123,324 → 860,536 B） |
| 移除压缩包导入（libarchive） | **7.13 MB** | 去掉 `libarchive-jni.so`（1,941,592 B） |

配套清理：

- 删除 `modules/rhino/lib/rhino-1.7.14.jar`（1.4 MB，未被引用的死文件）、`ReadBookActivity.kt.bak`、`ic_launcher-web.png`
- 删除已不存在的库的 proguard 死规则（GSYVideoPlayer）、无用 keep（`org.eclipse.tm4e.**` / `org.joni.**`）、`-verbose`
- 删除 gradle.properties 里的 Cronet 死配置（项目并不依赖 cronet）
- 修复 CI 里无效的架构参数：`-Pandroid.injected.abifilters` 不是 AGP 支持的属性（AGP 只认 `android.injected.build.abi`），改为项目自定义的 `-Pabi`，否则四种架构选项产出的都是同一个 universal APK
- `.gitignore` 增加 `*.hprof` 等（构建期 JVM 曾在仓库根目录丢下一个 686 MB 的堆转储）

### 2. 行为变化（重要，升级前必读）

- **仅支持 arm64-v8a**：armeabi-v7a / x86 / x86_64 设备无法安装。需要其它架构时用 `-Pabi=` 覆盖（见「构建」）。
- **移除压缩包导入 / 解压（zip、rar、7z）**：
  - 本地导入界面选中压缩包会提示「不支持的压缩包」，批量导入会记为失败；
  - 依赖旧「压缩包书籍」的记录，本地文件缺失时按“文件不存在”处理，不再尝试重新解压；
  - JS 接口 `java.unArchiveFile(...)`、`getRarByteArrayContent(...)`、`get7zByteArrayContent(...)` 返回空值（依赖它们的书源会失效）；
  - **`java.util.zip` 相关能力保持不变**：配置文件读写、EPUB 解析、JS 的 `getZipByteArrayContent` 仍可用。
- **移除内置代码编辑器**（sora-editor）。
- **只打包中文资源**（`zh` / `zh-rTW` / `zh-rHK`）：其它语言用户看到默认（中文）文案。
- 关闭 APK/AAB 内的依赖元数据块（`dependenciesInfo`），若上架 Google Play 会失去「SDK 洞察」数据。

## 开始使用

从 [Releases](../../releases) 下载 `Legado-<tag>-arm64-v8a.apk` 安装即可。
若与已装版本签名不同，需要先卸载（本分支使用仓库内置密钥，见下）。

## 构建

### 环境要求

- JDK 17
- Android SDK：`compileSdk 36`、build-tools 36
- Gradle 8.14.4（仓库自带 wrapper）。若 `services.gradle.org` 不可达，可从镜像取发行版，例如
  `https://mirrors.cloud.tencent.com/gradle/gradle-8.14.4-bin.zip`；依赖仓库不可达时可在 `settings.gradle` 临时启用文件里注释掉的镜像仓库（**不要提交该改动**）。

### 命令

```bash
# 默认：只出 arm64-v8a，release/debug 都用内置密钥签名
./gradlew :app:assembleRelease
./gradlew :app:assembleDebug

# 指定架构（逗号分隔，会覆盖默认值）
./gradlew :app:assembleRelease -Pabi=arm64-v8a,armeabi-v7a
./gradlew :app:assembleRelease -Pabi=x86_64
```

产物：`app/build/outputs/apk/app/release/legado_app_<version>.apk`
（`<version>` 形如 `3.26.101613`，其中 `yy.MMddHH` 是构建时刻，**跨小时构建会换文件名**）

### 内置签名密钥（公开）

仓库内置 `keystore/legado-release.jks`，**故意公开提交**，让 CI 与任何人都能构建出可互相覆盖安装（`adb install -r`）的 APK：

| 项 | 值 |
|---|---|
| 文件 | `keystore/legado-release.jks`（JKS） |
| Alias | `legado` |
| Store / Key 密码 | `123456` |
| 证书 SHA-256 | `AF:D3:3C:F7:81:A7:D4:90:9D:2F:4A:25:98:98:5D:9C:13:88:C2:C7:49:11:1B:B2:28:64:E2:84:6A:77:F0:A4` |
| 有效期 | 10000 天 |

- 未提供自定义签名配置时，`release` 与 `debug` 都自动用它签名；
- 想用自己的密钥：在 `local.properties` 或 `~/.gradle/gradle.properties` 里设置
  `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` 即可**覆盖**内置密钥；
- ⚠️ 这是**公开密钥**，任何人都能签出同签名的安装包，因此**不要**用它做私有分发或商店上架。

### 手动签名（可选）

```bash
zipalign -p -f 4 app-release-unsigned.apk aligned.apk
apksigner sign --ks keystore/legado-release.jks --ks-key-alias legado \
  --ks-pass pass:123456 --key-pass pass:123456 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out signed.apk aligned.apk
```

## CI

`.github/workflows/build-legado.yml`：手动触发（需填 tag），可选目标架构（默认 `arm64-v8a`），
使用仓库内置密钥签名并创建 GitHub Release。
> 注意：`arch` 选项是通过 `-Pabi=` 生效的，产物也按所选架构命名，但**同一时间只发布一个架构**的包。

## 体积构成（实测，7.13 MB）

| 项 | 压缩后 | 说明 |
|---|---|---|
| dex + resources.arsc + manifest | 4,364 KB | 代码与资源表 |
| `assets/bg`（14 张背景图） | 1,004 KB | 未压缩存储；可继续用 WebP 优化 |
| `res` | 549 KB | 布局/矢量图 |
| `tc`（简繁转换词典） | 483 KB | `quick-chinese-transfer` 的 `s2t/t2s` 字典 |
| `lib/arm64-v8a` | 503 KB | `librenderscript-toolkit.so`（图片模糊/缩放） |
| `dtd` | 85 KB | epublib 解析 EPUB 用的 DTD |
| 其它 | ~180 KB | `org`（Rhino 语言包）、`META-INF`、`kotlin`、`assets/*.md` 等 |

未做的可选项：`assets/bg` 转 WebP（约 −0.6 MB）、`packaging.jniLibs.useLegacyPackaging=true`（约 −0.33 MB）、
okhttp/okio 的 keep 收紧（dex 约 −0.1~0.3 MB）。

---

# Grateful-感谢 [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-)

> * org.jsoup:jsoup
> * cn.wanghaomiao:JsoupXpath
> * com.jayway.jsonpath:json-path
> * org.mozilla:rhino
> * com.squareup.okhttp3:okhttp
> * com.github.bumptech.glide:glide
> * com.jaredrummler:colorpicker
> * org.apache.commons:commons-text
> * io.noties.markwon:core
> * com.caverock:androidsvg-aar
> * cn.hutool:hutool-crypto
> * com.github.liuyueyi.quick-chinese-transfer
> * com.github.TomasValenta:renderscript-intrinsics-replacement-toolkit
> * com.louiscad.splitties
> * androidx.* / com.google.android.material
> * 以及上游 [gedoor/legado](https://github.com/gedoor/legado) 的全部作者与贡献者

# 许可证

本项目遵循上游仓库的 [LICENSE](LICENSE)。
