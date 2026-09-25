# MiniPic 开发状态

最后更新：2026-09-25
当前开发版本：0.0.3（versionCode 3）
工作区：`E:\Vibe Coding\Programs\MiniPic`

## 项目定位

MiniPic 是面向 Android 手表、优先适配 OPPO Watch OWW211 的原生图库。原始需求和交互基线见 [MiniPic_Elementary_Design.md](MiniPic_Elementary_Design.md)；表冠输入调研见 [OPPOWatch_Crown.md](OPPOWatch_Crown.md)。

## 当前技术基线

- Kotlin 原生 Android View，单 Activity；本轮没有替换 UI 框架。
- Kotlin 1.9.20、Java 17、AGP 8.1.0、Gradle 8.13。
- compileSdk 35、targetSdk 35、minSdk 23。
- Material Components 1.12.0、RecyclerView 1.3.2。
- 支持 armeabi-v7a、arm64-v8a 和 Universal APK。
- 主要入口仍在 `android/app/src/main/java/mini/pic/MainActivity.kt`；图库纯数据、递归扫描和文件名/路径规则已抽到 `GalleryModel.kt`。

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
- 看图使用 ImageDecoder 目标尺寸；旧 API 使用 bounds 探测和采样解码。解码目标限制在屏幕物理尺寸约 2 倍内，以控制峰值内存。
- 修复 PhotoView 首帧先使用过期 `fitScale` 的问题；Activity 销毁时关闭扫描、文件和封面执行器。

### 权限与资源

- targetSdk 提升到 35；声明 Android 13+ `READ_MEDIA_IMAGES`，旧读取/写入权限设置了 SDK 上限。
- Android 11+ 检查所有文件访问权限；同时提供仅授予图片读取权限的回退。用户从系统设置返回后继续待处理操作。
- 图片签名资源移入 `res/raw`，修复资源类型使用错误。
- Release 配置补齐 ProGuard rules 文件。

### 自动化测试

新增 `GalleryModelTest.kt`，覆盖：

- 普通/隐藏目录扫描及只有签名图的空相册。
- 同数量图片替换和文件元数据变化检测。
- 嵌套目标相对路径与存储根目录外路径拒绝。
- 重名图片的安全后缀规则。

## 验证结果

最近一次完整命令：

```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_SDK_ROOT='E:\Data\Android'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease
```

结果：**BUILD SUCCESSFUL**。JVM 单测、Debug 构建、Android Lint、Release 构建均通过。Lint 有 6 条非阻断警告，主要涉及 MANAGE_EXTERNAL_STORAGE 的商店政策、target SDK 提示、固定竖屏、旧备份属性和重复的图标密度资源。

本轮最终验收的 OWW211 设备（API 30、378×496）已成功安装 versionCode 3 / targetSdk 35。验证了图库主页真实图片、主页右滑进入设置、设置左滑返回、相册图片查看、看图右滑操作页、外部 `file://` ACTION_VIEW，以及复制/移动目标选择流程。隔离测试中复制文件源和目标均为 73,257 字节、SHA-256 相同且源保留；移动后源文件消失、目标 SHA-256 与原始文件相同。删除流程未做真实确认删除，以免触碰用户图库。

最终运行时观测：查看真实图库时 PSS/RSS 约 47.1/73.7 MB；在查看器/选择器与测试操作过程中峰值观测约 62.7/83.8 MB，仍低于 100 MB 目标。一次 `dumpsys gfxinfo` 在自动化冷启动/扫描和输入注入期间记录到 54 帧中 38 帧 janky、P95 650ms；该数据混有启动、全盘扫描和 ADB 注入开销，不是有效的稳态滚动基准，也说明后续必须用实体滚动做独立帧时测量。ADB `input roll` 不等价于 OWW211 的 REL_WHEEL，实体表冠的方向、慢速/快速滚动和看图缩放尚待用户手动验收。

本轮测试创建的 `MiniPicReviewTest`、`MiniPicReviewTarget` 两个设备目录已删除，原 `screen_off_timeout=999000`、`sys_charge_ui_showing=1` 设置已恢复；应用最后回到系统启动器。测试过程中曾触发一次陈旧封面路径导致的异步解码崩溃，已修复并在更新后的安装包重复进入选择器验证无 crash。

## 尚未完成及限制

### 手动验收与后续性能工作

1. 检查启动权限提示、拒绝权限降级和返回设置页流程。
2. 实体表冠方向、灵敏度、慢速/快速滚动和看图缩放仍需手动验收。
3. 用真实图片完整验证删除；复制/移动已在隔离目录通过 SHA-256 验证，但尚未覆盖 URI 写权限拒绝/可恢复授权。
4. 在大图与 GIF 下记录解码耗时、稳态帧时、峰值 PSS/RSS；对 100/500/1000 个相册做滚动验收。

### 后续工程工作

- 扫描仍然是全共享存储递归遍历；JSON 缓存用于快速呈现和变化比较，还不是增量索引或数据库。若实测扫描耗时明显，下一阶段再引入可验证的增量索引/SQLite。
- 相册选择页和主页筛选页仍一次性构建列表项，后续也应改为可回收列表。
- `MainActivity.kt` 仍承担权限、导航、文件操作和多个页面构建；应在设备验收后继续抽取 `FileOperations`、图片查看器和表冠分发模块。
- Release 可构建，但尚未配置正式签名；当前 Release APK 不能视作可发布签名包。
- MANAGE_EXTERNAL_STORAGE 受 Google Play 政策严格限制；若目标发布渠道是 Play，需要评估改为 MediaStore/用户授权的文件访问方案。

## 推荐执行顺序

1. 手动完成 OWW211 实体表冠和权限拒绝/恢复验收。
2. 记录冷启动首屏、大图/GIF 解码、稳态滚动帧时与内存数据；针对实测瓶颈做下一轮改进。
3. 根据压力数据决定是否上数据库/增量扫描；随后回收选择器/筛选列表并拆分 Activity 模块。
4. 最后完成正式签名发布、语言资源和格式扩展。
