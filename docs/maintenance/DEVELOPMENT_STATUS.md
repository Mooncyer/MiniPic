# MiniPic 开发状态

最后验证：2026-10-01；0.0.7 构建、签名和 OWW211 回归完成。
当前开发版本：0.0.7（versionCode 7）
工程目录：仓库中的 `android/`。

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
- 表冠灵敏度滑块范围 10%–200%，默认 40%；`validCrownSensitivity` 校验边界与非有限值，普通页面、单图和漫画模式共用设置。列表滚动保留小数像素余量，分页累积器保留慢速碎片和跨页余量，不做 250ms 清零或固定限流；反向输入和显式 reset 清除余量。
- 点击主页“相册”标题退出应用。
- 普通模式切图动画改为 150ms。上滑前进时新图从底部上移进入，下滑返回时从顶部下移进入。
- 表冠在灵敏度换算后的增量累计达到 100 时，立即调用与触摸相同的切图请求和上下动画；已回滚表冠专用最小间隔/排队调度。
- GIF/动画 WebP 以 Drawable intrinsic 尺寸设 bounds，再经 Canvas 映射到原图坐标和屏幕 fit 尺寸。Drawable 绑定 View 回调，按附着、窗口可见性及 Activity 播放状态启停；更换图片/移除 View 时停止旧动画并清除回调。
- 应用图标使用用户修改后的 `design/branding/icon.png`，源图不变；launcher 为 mdpi 48、hdpi 72、xhdpi 96、xxhdpi 144、xxxhdpi 192 像素五档真实 PNG。
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

- 项目已有长期发布证书和仓库外签名配置；通过 `MINIPIC_KEYSTORE_PROPERTIES` 指向外部属性文件，属性中的 `storeFile` 使用绝对路径。实际密钥、密码和配置不得进入仓库。
- Release 输出包括 Universal、armeabi-v7a 和 arm64-v8a；当前没有 native `.so`，ABI 分包不保证体积更小。
- 0.0.7 三类 APK 均通过 `apksigner verify`，证书与 0.0.6 相同；最终在 OWW211 留下 armeabi-v7a signed Release（versionCode 7）。三包均为 2,137,374 bytes；签名凭据留在仓库外。
- 0.0.6 的漫画导入、设置和设备验收记录见下方历史章节。

### 自动化测试

新增 `GalleryModelTest.kt`，覆盖：

- 普通/隐藏目录扫描及只有签名图的空相册。
- 同数量图片替换和文件元数据变化检测。
- 嵌套目标相对路径与存储根目录外路径拒绝。
- 重名图片的安全后缀规则。
- 默认及可配置表冠灵敏度、上下界、NaN/Infinity 回退。
- 分页碎片余量、多页大增量、慢速累计、同时间戳不限流、反向/reset/非有限输入。
- 索引负值、空列表、大步和 Int 溢出钳制，以及连续反向导航。
- 大横竖图 fit、小图不放大解码、极端宽高比和至少 1 像素的安全尺寸。
- 视口区域采样像素预算、EXIF 八方向点映射和区域映回原图边界。
- 未补零自然数字顺序（1, 2, 10）和混合文本数字文件名。
- 漫画单页目标尺寸/2MP Bitmap 预算。

36 项 JVM 测试全部通过。`ViewerRegressionTest` 在 OWW211/API 30 上运行 12 项 instrumentation 测试，全部通过，覆盖标题退出、离页取消、快速目标/反向/边界、上下滑动方向及动画中断接续、表冠100阈值与达到后立即切图、触摸和表冠共用切图流程、失败加载的显示/URI 一致性、GIF fit/换帧/暂停恢复、三滚动轴、取消触摸及滑块保存。`fit-small.gif`（32×16）和 `fit-large.gif`（1200×800）为两帧红/绿循环素材，每帧 180ms。测试仅创建唯一 cache 目录，结束后清理并恢复设置；临时 runner 已从手表移除。

## 0.0.7 验证状态

- `testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest`：BUILD SUCCESSFUL；36 项 JVM 测试通过，Lint 0 errors / 16 warnings（含旧权限政策、SDK、依赖版本与绘图分配提示）。
- 12 项 OWW211 instrumentation 测试通过，包含上滑前进、下滑返回、150ms动画中断接续、100阈值、表冠达到阈值立即切图及触摸共用切图流程的断言。
- Universal、armeabi-v7a、arm64-v8a 的签名及版本验证通过；使用用户提供的仓库外 keystore，同证书无损覆盖 0.0.6，未卸载主应用。三包均为 2,137,374 bytes；签名凭据留在仓库外。
- OWW211 GIF 屏幕截图确认小图为 378×189、大图为 378×252，居中完整显示，中心红/绿像素跨帧变化；手动点击相册标题返回桌面，滑块保存 200% 后恢复 40%。
- 原主页相册选择与 21 页漫画保留；测试后最终设置为测试前状态，保留 signed armeabi-v7a Release（versionCode 7）。测试包已移除，屏幕超时恢复为测试前的 10000ms，未修改充电覆盖设置。crash log 无本轮 MiniPic 崩溃。
- 三轴/非标准 source 与快速混合输入已自动验证；实体表冠的真实刻度、方向和手感仍需人工验收，未将 ADB `input roll` 当作等价测试。

建议从 `android/` 执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

配置 JDK 17、Android SDK 35 及仓库外 `MINIPIC_KEYSTORE_PROPERTIES` 后运行；成功产物与证书须独立检查，不能用旧验收代替。

## 0.0.6 历史验收

以下记录仅描述 0.0.6，不证明 0.0.7 已验收。

历史构建为 versionCode 6 / versionName 0.0.6。Debug、当时的 12 项 JVM 单测、Lint 和 signed Release 构建均通过；Universal 与 armeabi-v7a 包由长期发布证书签名并通过 `apksigner verify`。历史 Lint 为 6 条非阻断警告，包含权限政策、target SDK、竖屏、备份属性和重复图标密度资源。

Signed Release fresh install 在 OWW211 上已成功安装并稳定启动，首扫完成后主页可显示图片相册，约 31 MB PSS / 60 MB RSS。Android 11 的 OWW211 不识别 `READ_MEDIA_IMAGES`，本次由 ADB 临时授予 `MANAGE_EXTERNAL_STORAGE=allow` 完成全图库验收；代码已增加无所有文件权限时的 MediaStore 只读 fallback。首次验收期间的旧 Debug PID 曾因 ColorOS 不可导出设置 Activity 崩溃，已修复为捕获异常并显示手动授权提示；最新 signed Release PID 无新增 MiniPic crash。

漫画 ZIP 已导入手表 `Pictures/MiniPicMangaTest/`，签名包可继续使用；用户设置最终保持 `mangaMode=false`、`crownZoom=true`。

## 尚未完成及限制

1. 实体表冠 REL_WHEEL 的方向、灵敏度和漫画模式缩放/滚动开关组合需手动确认；ADB `input roll` 不等价。
2. 0.0.7 已通过证书验证和 OWW211 覆盖安装，尚未发布到应用商店。
3. 原 21 页漫画相册仍保留；当前漫画模式关闭，可在设置中开启。
4. 若未来清理 `archive/` 历史文件，需考虑 ZIP/APK 被 `.gitignore` 忽略，不会自动进入 Git。

## 后续工程工作

- 扫描仍然是全共享存储递归遍历；JSON 缓存用于快速呈现和变化比较，还不是增量索引或数据库。若实测扫描耗时明显，下一阶段再引入可验证的增量索引/SQLite。
- 相册选择页和主页筛选页仍一次性构建列表项，后续也应改为可回收列表。
- `MainActivity.kt` 仍承担权限、导航、文件操作和多个页面构建；应在设备验收后继续抽取 `FileOperations`、图片查看器和表冠分发模块。
- 已有仓库外正式签名配置；每次 Release 仍须校验签名证书与版本信息，0.0.7 已通过验证。
- MANAGE_EXTERNAL_STORAGE 受 Google Play 政策严格限制；若目标发布渠道是 Play，需要评估改为 MediaStore/用户授权的文件访问方案。

## 推荐执行顺序

1. 手动验收 OWW211 标题退出、五档图标、10%–200% 表冠滑块及默认 40%、普通/漫画模式映射。
2. 验证快速/慢速/反向翻页、取消旧解码、加载失败后的文件操作索引一致性，以及小/大 GIF fit 和持续播放。
3. 记录漫画长图与单图高倍率的 GC、稳态帧时和峰值内存。
4. 完成权限拒绝、URI 删除授权与真实图片删除确认流程测试。
5. 根据全盘扫描数据评估 SQLite/增量索引；随后回收选择器列表并拆分 Activity 模块。
6. 校验长期签名证书和分发产物，之后再推进商店发布、语言资源和格式扩展。
