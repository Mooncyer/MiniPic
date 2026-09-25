# MiniPic

MiniPic 是一款面向 Android 手表、优先适配 OPPO Watch OWW211 的原生图库应用。

- 包名：`mini.pic`
- 当前版本：`0.0.6` / `versionCode 6`
- 技术栈：Kotlin、Android View、Material Components、RecyclerView
- 目标设备：OWW211，Android 11，378×496
- ABI：`armeabi-v7a`、`arm64-v8a`、Universal

## 当前功能

- 相册扫描、缓存、隐藏目录开关和主页相册筛选。
- 单图查看：缩放、平移、EXIF 方向、高倍率 viewport 区域解码。
- 漫画模式：自然数字文件名排序、屏幕等宽连续拼接、连续触摸/惯性滚动、可见页异步加载和离屏回收。
- OPPO Watch 表冠：全局灵敏度约为原来的 40%；普通页面滚动、单图缩放/切图和漫画模式滚动/缩放均有适配。
- 图片复制、移动、删除、临时旋转和封面设置。
- 支持作为 `image/*` 外部打开方式，不会因外部打开图片而启动全盘扫描。

## 工程结构

```text
android/                         Android 工程和活跃源码
├── app/src/main/java/mini/pic/
│   ├── MainActivity.kt           页面、权限、导航、文件操作协调
│   ├── GalleryModel.kt           扫描、自然排序、路径和数据规则
│   └── MangaView.kt              漫画连续阅读画布
└── app/src/test/                 JVM 单元测试

docs/                             维护、需求和硬件文档
design/                           设计源、参考图和品牌素材
archive/                          历史 APK、旧快照、设备 dump、测试 fixture（原文件保留；ZIP/APK 被 Git 忽略，不代表当前发布包）
```

## 构建环境

需要：

- JDK 17
- Android SDK，compile SDK 35
- Windows 下推荐使用仓库自带 Gradle Wrapper

从仓库根目录执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_SDK_ROOT='E:\Data\Android'
cd android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

Debug APK 输出到 `android/app/build/outputs/apk/debug/`。Release 输出到 `android/app/build/outputs/apk/release/`。

Release 构建使用仓库外的签名文件，不把密码或 keystore 放进 Git。[`keystore.properties.example`](keystore.properties.example) 是配置格式模板；请在仓库外 `MiniPic-signing/keystore.properties` 创建真实文件，或设置 `MINIPIC_KEYSTORE_PROPERTIES` 环境变量。完整签名流程见 [`docs/maintenance/RELEASING.md`](docs/maintenance/RELEASING.md)。

## 签名和发布

本机长期签名密钥位于仓库外的 `E:\Vibe Coding\MiniPic-signing\minipic-release.jks`，alias 为 `minipic-release`。证书 SHA-256：

```text
84:5E:04:9D:B3:DA:CF:66:99:2C:8D:D0:8B:C1:AF:26:86:BD:8A:94:0C:C3:86:B2:FD:BC:6F:6B:2F:15:88:58
```

密钥密码不会写入项目文档。务必独立备份 keystore 和密码；丢失后无法发布可覆盖安装的后续版本。

当前签名构建会生成：

- `app-universal-release.apk`
- `app-armeabi-v7a-release.apk`
- `app-arm64-v8a-release.apk`

Release 构建如果找不到签名配置会回退为 unsigned；交付前必须用 `apksigner verify --verbose --print-certs` 检查证书指纹。

漫画测试素材 `142347535-[Dai3] 测谎仪检查.zip` 已导入 OWW211 到 `Pictures/MiniPicMangaTest/`，便于直接查看。旧 APK/快照 ZIP 和测试 ZIP 位于 `archive/`，仅为本机归档，不代表当前发布包；`.gitignore` 默认忽略 APK/ZIP，因此不会自动进入 Git。

## 权限和发布限制

Android 11+ 图库扫描/管理优先使用 `MANAGE_EXTERNAL_STORAGE`，同时保留只读图片权限回退。该权限受 Google Play 政策严格限制；若目标发布渠道是 Play，需要重新设计为 MediaStore 和用户授权为主的存储方案。

## 实机验收边界

OWW211 表冠底层是厂商 `pixart_pat9125 / REL_WHEEL`，`adb input roll` 不能等价模拟实体表冠。代码和自动化输入测试不能替代真实表冠的方向、灵敏度和连续滚动验收。

漫画模式高内存、真实表冠和真实双指高倍率查看仍应在实体手表上确认。历史 APK、ZIP 和截图不代表当前发布包。

## 文档索引

- [开发状态](docs/maintenance/DEVELOPMENT_STATUS.md)
- [技术说明](docs/maintenance/TECHNICAL.md)
- [发布签名流程](docs/maintenance/RELEASING.md)
- [需求基线](docs/requirements/MiniPic_Elementary_Design.md)
- [OPPO Watch 表冠调研](docs/hardware/OPPOWatch_Crown.md)
- [设计源和参考素材](design/)
- [历史归档](archive/)
