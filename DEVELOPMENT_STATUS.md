# MiniPic 开发状态

最后更新：2026-09-25
当前开发版本：0.0.4（versionCode 4）
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
- 看图保留约屏幕尺寸的低分辨率预览作底图；静态图放大后用 `BitmapRegionDecoder` 只解码当前视口并叠加高精度区域，包含 25% overscan 和约 4MP 区域像素预算。平移/缩放请求经 90ms 去抖和单线程合并，避免预览期间黑洞或多个大 Bitmap 任务堆积。
- 区域坐标按 EXIF 原始方向映射，API 23-27 的预览也显式应用 EXIF 方向。GIF/动画 WebP 保留动画预览但不叠加冻结的静态 tile。
- 全部表冠输入统一乘 0.4；滚动保留小数像素余量，避免低幅 REL_WHEEL 刻度因整数取整被吞。
- 修复 PhotoView 首帧先使用过期 `fitScale` 的问题；Activity 销毁时关闭扫描、文件、封面和查看器执行器。

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
- 表冠输入统一乘以 0.4。
- 视口区域采样像素预算、EXIF 八方向点映射和区域映回原图边界。

## 验证结果

最近一次完整命令：

```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_SDK_ROOT='E:\Data\Android'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease
```

结果：**BUILD SUCCESSFUL**。JVM 单测、Debug 构建、Android Lint、Release 构建均通过。Lint 有 6 条非阻断警告，主要涉及 MANAGE_EXTERNAL_STORAGE 的商店政策、target SDK 提示、固定竖屏、旧备份属性和重复的图标密度资源。

本轮最终验收的 OWW211 设备（API 30、378×496）已安装 versionCode 4 / targetSdk 35。0.0.3 验证过主页导航、看图、操作页、外部图片打开及隔离目录复制/移动 SHA-256；0.0.4 在此基础上完成表冠统一灵敏度和区域查看器实现，Debug/Release 构建及 9 项 JVM 测试通过。设备可正常打开约 7.8 MB JPEG；最终 0.0.4 留在主页时测得约 46.6 MB PSS / 73.3 MB RSS，无 crash。0.0.4 查看器 idle 测得约 38.9 MB PSS / 65.3 MB RSS。

未经真实放大请求时的低内存读数不能证明高倍率区域的峰值；tile 解码单块上限约 4MP，但滚动/缩放高倍率时的清晰度、方向和稳态内存仍待设备手动检查。

## 尚未完成及限制

### 0.0.4 需要手动验收

1. 实体表冠：主页/设置滚动、正反方向、慢速/快速输入，并确认灵敏度为原来的约 40%。
2. 看图：真实双指/表冠放大后中心细节清晰度、平移到边缘、连续缩放时 tile 更新是否平滑。
3. 对带 EXIF 旋转的 JPEG 和 GIF/WebP 动图核对方向/动画显示；动画路径有意使用低分辨率预览，不做静态区域覆盖。
4. 记录高倍率时解码耗时、GC、稳态帧时和峰值 PSS/RSS；单 tile 解码预算限制为约 4MP。

### 后续工程工作

- 扫描仍然是全共享存储递归遍历；JSON 缓存用于快速呈现和变化比较，还不是增量索引或数据库。若实测扫描耗时明显，下一阶段再引入可验证的增量索引/SQLite。
- 相册选择页和主页筛选页仍一次性构建列表项，后续也应改为可回收列表。
- `MainActivity.kt` 仍承担权限、导航、文件操作和多个页面构建；应在设备验收后继续抽取 `FileOperations`、图片查看器和表冠分发模块。
- Release 可构建，但尚未配置正式签名；当前 Release APK 不能视作可发布签名包。
- MANAGE_EXTERNAL_STORAGE 受 Google Play 政策严格限制；若目标发布渠道是 Play，需要评估改为 MediaStore/用户授权的文件访问方案。

## 推荐执行顺序

1. 手动验收 OWW211 实体表冠 40% 灵敏度和高倍率 pinch/区域清晰度。
2. 记录高倍率大图/GIF 的 GC、稳态帧时和峰值内存，按数据调整 tile/overscan 预算。
3. 完成权限拒绝、URI 删除授权与真实图片删除确认流程测试。
4. 根据全盘扫描数据评估 SQLite/增量索引；随后回收选择器列表并拆分 Activity 模块。
5. 最后完成正式签名发布、语言资源和格式扩展。
