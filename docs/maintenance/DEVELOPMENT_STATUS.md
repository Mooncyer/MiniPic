# MiniPic 开发状态

最后更新：2026-09-25
当前开发版本：0.0.6（versionCode 6）
工作区：`E:\Vibe Coding\Programs\MiniPic`

## 项目定位

MiniPic 是面向 Android 手表、优先适配 OPPO Watch OWW211 的原生图库。原始需求基线见 [MiniPic_Elementary_Design.md](../requirements/MiniPic_Elementary_Design.md)；表冠输入调研见 [OPPOWatch_Crown.md](../hardware/OPPOWatch_Crown.md)。

## 当前技术基线

- Kotlin 原生 Android View，单 Activity；本轮没有替换 UI 框架。
- Kotlin 1.9.20、Java 17、AGP 8.1.0、Gradle 8.13。
- compileSdk 35、targetSdk 35、minSdk 23。
- Material Components 1.12.0、RecyclerView 1.3.2。
- 支持 armeabi-v7a、arm64-v8a 和 Universal APK。
- 活跃工程位于 `android/`；维护文档在 `docs/maintenance/`，需求/硬件文档在 `docs/requirements/`、`docs/hardware/`，设计素材在 `design/`，历史资产在 `archive/`。
- 源码入口 `android/app/src/main/java/mini/pic/MainActivity.kt`；扫描/排序在 `GalleryModel.kt`，漫画画布在 `MangaView.kt`。

## 本轮已完成

### 数据完整性

- 图片复制改为流式读写，不再将整张图片 `readBytes()` 到内存。
- MediaStore 写入使用完整的相对目录和 `IS_PENDING`；目标流无法打开、复制失败或提交失败时删除待提交目标，且保留源文件。
- 目标名称会查询并避开重名；移动只在目标写入并提交成功后才尝试删除源文件。
- 如果目标复制成功但源文件因 URI 权限无法删除，明确提示“移动未完成”，保留原图。
- 删除流程移除了“先截断再删除”的破坏性回退。权限或 provider 拒绝时报告失败，不修改原图。
- 外部 `content://` 和 `file://` 图片保持 URI 读取，不再在主线程复制到缓存；同一前台 Activity 通过 `singleTop` 复用。

### 性能与生命周期

- 主相册网格改为 `RecyclerView + GridLayoutManager + DiffUtil`，不再一次创建所有主页卡片。
- 封面解码使用独立线程池和 24 项 LRU；修复缓存命中后仍重复解码的问题。
- 扫描比较完整的相册与图片元数据（路径、修改时间、大小），避免只比较数量导致同数量内容变化不刷新。
- JSON 缓存读取、扫描和封面/文件 I/O 不在 UI 线程执行；缓存保存包含文件大小。
- 看图保留约屏幕尺寸的低分辨率预览作底图；静态图放大后用 `BitmapRegionDecoder` 只解码当前视口并叠加高精度区域，包含 25% overscan 和约 4MP 区域像素预算。平移/缩放请求经 90ms 去抖和单线程合并，避免预览期间黑洞或多个大 Bitmap 任务堆积。
- 区域坐标按 EXIF 原始方向映射，API 23-27 的预览也显式应用 EXIF 方向。GIF/动画 WebP 保留动画预览但不叠加冻结的静态 tile。
- 全部表冠输入统一乘 0.4；滚动保留小数像素余量，避免低幅 REL_WHEEL 刻度因整数取整被吞。
- 修复 PhotoView 首帧先使用过期 `fitScale` 的问题；Activity 销毁时关闭扫描、文件、封面和查看器执行器。
- 新增漫画模式设置。内部相册开启后按自然数字文件名排序（`1, 2, 10`，而不是字典序），使用连续纵向画布而不是单图分页；图片按屏幕等宽，默认可以停留在任意垂直位置。
- 漫画画布只异步读取尺寸和可见页/邻近页 Bitmap，离屏页回收；默认缩放为 1x 等宽，支持触摸连续滚动和缩放。
- 漫画模式下表冠遵循“表冠滚动缩放”：开启时缩放连续画布，关闭时滚动漫画内容；外部 `ACTION_VIEW` 仍保持普通单图查看。

### 权限与资源

- targetSdk 提升到 35；声明 Android 13+ `READ_MEDIA_IMAGES`，旧读取/写入权限设置了 SDK 上限。
- Android 11+ 检查所有文件访问权限；同时提供仅授予图片读取权限的回退。用户从系统设置返回后继续待处理操作。
- 图片签名资源移入 `res/raw`，修复资源类型使用错误。
- Release 配置补齐 ProGuard rules 文件。

### 签名与交付

- 0.0.6 Release 使用仓库外 keystore `E:\Vibe Coding\MiniPic-signing\minipic-release.jks`，alias `minipic-release`。
- Universal Release：`android/app/build/outputs/apk/release/app-universal-release.apk`。
- 32-bit armeabi-v7a Release：`android/app/build/outputs/apk/release/app-armeabi-v7a-release.apk`。
- 64-bit Release：`android/app/build/outputs/apk/release/app-arm64-v8a-release.apk`。
- 签名 SHA-256：`84:5E:04:9D:B3:DA:CF:66:99:2C:8D:D0:8B:C1:AF:26:86:BD:8A:94:0C:C3:86:B2:FD:BC:6F:6B:2F:15:88:58`。
- 漫画 ZIP 已导入 OWW211 的 `Pictures/MiniPicMangaTest`；漫画模式开关恢复为关闭以保留验收前设置。

### 自动化测试

新增 `GalleryModelTest.kt`，覆盖：

- 普通/隐藏目录扫描及只有签名图的空相册。
- 同数量图片替换和文件元数据变化检测。
- 嵌套目标相对路径与存储根目录外路径拒绝。
- 重名图片的安全后缀规则。
- 表冠输入统一乘以 0.4。
- 视口区域采样像素预算、EXIF 八方向点映射和区域映回原图边界。
- 未补零自然数字顺序（1, 2, 10）和混合文本数字文件名。
- 漫画单页目标尺寸/2MP Bitmap 预算和异步回收。

共 12 项 JVM 单测，全部通过。

## 验证结果

最近一次完整命令：

```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_SDK_ROOT='E:\Data\Android'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

结果：**BUILD SUCCESSFUL**。12 项 JVM 单测、Debug、Android Lint 和 Release 构建均通过。Lint 有 6 条非阻断警告，主要涉及 MANAGE_EXTERNAL_STORAGE 商店政策、target SDK 提示、固定竖屏、旧备份属性和重复图标密度资源。

本次最终构建为 versionCode 6 / versionName 0.0.6。Debug、12 项 JVM 单测、Lint 和 signed Release 构建均通过；Universal 与 armeabi-v7a 包均由长期发布证书签名并通过 `apksigner verify`。

Signed Release fresh install 在 OWW211 上已成功安装并稳定启动，首扫完成后主页可显示图片相册，约 31 MB PSS / 60 MB RSS。Android 11 的 OWW211 不识别 `READ_MEDIA_IMAGES`，本次由 ADB 临时授予 `MANAGE_EXTERNAL_STORAGE=allow` 完成全图库验收；代码已增加无所有文件权限时的 MediaStore 只读 fallback。首次验收期间的旧 Debug PID 曾因 ColorOS 不可导出设置 Activity 崩溃，已修复为捕获异常并显示手动授权提示；最新 signed Release PID 无新增 MiniPic crash。

漫画 ZIP 已导入手表 `Pictures/MiniPicMangaTest/`，签名包可继续使用；用户设置最终保持 `mangaMode=false`、`crownZoom=true`。

## 尚未完成及限制

1. 实体表冠 REL_WHEEL 的方向、灵敏度和漫画模式缩放/滚动开关组合需手动确认；ADB `input roll` 不等价。
2. Signed Release 已通过证书验证并安装，但尚未发布到应用商店。
3. 漫画相册已导入手表，漫画模式开关保持关闭；用户可在设置中开启后查看。
4. 若未来清理 `archive/` 历史文件，需考虑 ZIP/APK 被 `.gitignore` 忽略，不会自动进入 Git。

## 后续工程工作

- 扫描仍然是全共享存储递归遍历；JSON 缓存用于快速呈现和变化比较，还不是增量索引或数据库。若实测扫描耗时明显，下一阶段再引入可验证的增量索引/SQLite。
- 相册选择页和主页筛选页仍一次性构建列表项，后续也应改为可回收列表。
- `MainActivity.kt` 仍承担权限、导航、文件操作和多个页面构建；应在设备验收后继续抽取 `FileOperations`、图片查看器和表冠分发模块。
- Release 可构建，但尚未配置正式签名；当前 Release APK 不能视作可发布签名包。
- MANAGE_EXTERNAL_STORAGE 受 Google Play 政策严格限制；若目标发布渠道是 Play，需要评估改为 MediaStore/用户授权的文件访问方案。

## 推荐执行顺序

1. 手动验收 OWW211 实体表冠在普通/漫画模式下的 40% 灵敏度和开关行为。
2. 记录漫画长图与单图高倍率的 GC、稳态帧时和峰值内存。
3. 完成权限拒绝、URI 删除授权与真实图片删除确认流程测试。
4. 根据全盘扫描数据评估 SQLite/增量索引；随后回收选择器列表并拆分 Activity 模块。
5. 最后完成正式签名发布、语言资源和格式扩展。
