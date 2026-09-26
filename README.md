# MiniPic

MiniPic | 一款专为 Android 手表设计的轻量化图库

## 基础信息

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

## 权限和发布限制

Android 11+ 图库扫描/管理优先使用 `MANAGE_EXTERNAL_STORAGE`，同时保留只读图片权限回退。该权限受 Google Play 政策严格限制；若目标发布渠道是 Play，需要重新设计为 MediaStore 和用户授权为主的存储方案。
