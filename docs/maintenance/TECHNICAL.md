# MiniPic 技术说明

> 验证日期：2026-10-01；36 项 JVM 和 12 项 OWW211 instrumentation 测试通过。
> 当前版本：0.0.7（versionCode 7）
> 项目根目录：当前工作区；Android 工程位于 `android/`

## 技术基线

| 项目 | 当前值 |
|---|---|
| 应用 / 包名 | 图库 / `mini.pic` |
| Kotlin / Java | Kotlin 1.9.20 / Java 17 |
| AGP / Gradle | 8.1.0 / 8.13 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 23 |
| 主要设备 | OPPO Watch OWW211，Android 11，378×496 |
| UI | Android 原生 View、Material Components 1.12.0、RecyclerView 1.3.2 |
| ABI | armeabi-v7a、arm64-v8a、Universal APK |

## 源码结构

```text
android/app/src/main/
├── AndroidManifest.xml
├── java/mini/pic/
│   ├── MainActivity.kt       # 页面、权限、导航、文件 I/O 协调、查看器分发
│   ├── GalleryModel.kt       # Pic、GalleryScanner、排序和文件名/路径规则
│   └── MangaView.kt          # 漫画连续纵向画布和可见页 Bitmap 生命周期
└── res/
    ├── raw/minipic_gallery_signature.png
    ├── mipmap-*/ic_launcher.png
    └── values*/styles.xml

android/app/src/test/java/mini/pic/GalleryModelTest.kt
```

页面状态为 `ALBUMS`、`SETTINGS`、`VIEWER`、`ACTIONS`、`PICK_ALBUM`、`SELECT_ALBUMS`，由 `MainActivity` 分发。页面布局仍以代码构建；本轮未引入 Fragment 或 Navigation Component。

## 数据与扫描

启动主图库时，流程为：异步读取 `album_cache.json` 并验证缓存文件的大小/修改时间，随后在单线程扫描执行器上遍历 `/storage/emulated/0`，最后把扫描快照和 UI 更新分开执行。外部 `ACTION_VIEW` 入口直接显示图片，不先触发全盘扫描。

`GalleryScanner` 按目录聚合 jpg/jpeg/png/webp/gif；`.MiniPicGallerySignature.png` 本身不显示，但有签名图的目录仍作为空相册保留。隐藏目录和隐藏图片由设置控制。扫描结果按修改时间排序，并保存 path、folder、modified、size。

快照差异比较使用完整的 `Pic` 数据，而不是只比较相册数和图片数。主页使用 `RecyclerView + GridLayoutManager`，扫描后通过 `DiffUtil` 更新；封面采用独立固定线程池和最多 24 项的 LRU。当前每次明确扫描仍会递归读取全部目录；缓存用于快速呈现和内容差异比较，不是数据库或增量索引。

## 图片查看与文件操作

- 静态图预览限制在约一倍屏幕尺寸；放大超过阈值后，PhotoView 根据固定原图坐标计算可视区域，带 25% overscan 用 `BitmapRegionDecoder` 解码并把 tile 叠加回原图坐标。
- Region 采样使用 2 次幂倍率，按当前区域像素预算选择尽可能小的 sample；单 tile 上限约 4MP。缩放/拖动通过 90ms 去抖和单线程查看器队列只保留最新请求，预览/上一 tile 在新 tile 到达前继续绘制。
- EXIF 方向使用统一 raw/oriented 坐标映射；API 23–27 的预览额外应用方向变换。GIF/动画 WebP 为 Animatable 时不叠静态区域，避免冻结帧。预览 bounds 使用 Drawable intrinsic 尺寸，再由 Canvas 比例映射到原图坐标；低分辨率预览仍占完整 fit 区域，不因 bounds 缩放不生效而只占屏幕一角。
- `fittedPreviewSize` 在解码阶段限制目标尺寸不超过原图，不放大小图，极端宽高比和非法尺寸保证至少 1 像素；最终屏幕 fit 由画布完成。
- PhotoView 通过 `drawable.callback=this`、`verifyDrawable` 和 `invalidateDrawable` 接入 View 的重绘/调度，动画由 Activity 播放状态、View/窗口可见性和附着状态控制。更换图片停止旧 Animatable 并清回调，detach 时停止动画、取消调度、清回调和 tile。
- 表冠 VSCROLL/SCROLL/HSCROLL 原始 delta 由 `scaleCrownDelta` 按设置换算：10%–200%，默认40%；普通模式阈值为100，小增量/慢速碎片持续累积，不因空闲清零。达到阈值后立即通过 `requestImageStep` 请求切图，与触摸共用同一请求、解码和动画；不设表冠专用切换间隔。漫画模式仍按“表冠滚动缩放”设置决定缩放或滚动。
- 普通单图切换以 `pendingViewerIndex ?: viewerIndex` 为基础计算最新目标，经 `requestedPageIndex` 的 Long 运算钳制；取消旧 Future 并清理过期队列，以 generation 拒绝迟到结果。成功解码并绑定后才提交 `viewerIndex`，失败保留当前图片。前进时新 PhotoView 从底部上滑进入、旧图向上移出；返回方向相反，150ms Decelerate 过渡。表冠达到100阈值后和触摸滑动立即进入相同切图流程；两者都可在进行中被更新输入中断并接续最新请求。
- 相册主页“相册”标题点击调用 `finishAndRemoveTask()` 退出应用。
- 漫画模式为独立 `MangaView` 连续画布：相册内图片按自然数字文件名排序，`1, 2, 10` 不会发生字典序错位；每张图独立按屏幕等宽绘制，相邻图片零间距首尾拼接。
- 漫画页高先异步读取并建立前缀表；绘制/解码只处理当前视口附近页面，页面 Bitmap 按目标宽度采样并回收远离视口超过 3 页的缓存。单页解码使用约 2MP 像素预算，避免长章节多页高分辨率 Bitmap 同时驻留。
- 漫画模式初始 zoom=1（屏幕等宽），手指拖动支持任意停留和减速惯性；表冠“滚动缩放”开时缩放画布，关时纵向滚动画布。
- 外部 `ACTION_VIEW` 不进入漫画模式；漫画操作页针对当前可见页工作，隐藏对整个章节语义不明确的旋转操作。
- 复制通过流式 I/O 写入 MediaStore `IS_PENDING` 项，使用完整 `RELATIVE_PATH`，处理重名后才提交。失败时清理目标项并保留源图。
- 移动按“复制并提交目标 → 删除源文件”执行。无法删除源 URI 时不谎报移动成功，提示复制已完成但移动未完成。
- 删除失败不会截断或覆盖源文件。MediaStore/URI 授权失败会返回失败状态；可恢复的系统删除授权流程仍需设备端实测后补齐。
- 文件操作、图库扫描、封面解码使用不同执行器；Activity 销毁时关闭执行器并使过期图片解码回调失效。

## 图标资源

0.0.7 launcher 来自用户修改后的 `design/branding/icon.png`，源图不得覆盖。`res/mipmap-*/ic_launcher.png` 为真正 PNG：mdpi 48×48、hdpi 72×72、xhdpi 96×96、xxhdpi 144×144、xxxhdpi 192×192，按 LANCZOS 从源图生成并核验格式/尺寸/内容。

## 权限

Manifest 声明 `READ_MEDIA_IMAGES`、限制到旧系统的 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` 和 `MANAGE_EXTERNAL_STORAGE`。Android 11+ 首选所有文件访问；同时提供仅图片读取权限回退。执行复制、移动、删除和新建相册前再次检查写访问能力。实机 OWW211 上已观察到 `MANAGE_EXTERNAL_STORAGE=allow`，但本轮未能完成权限拒绝交互验收。

`MANAGE_EXTERNAL_STORAGE` 有显著分发限制，尤其是 Google Play。若要上架，需另行设计以 MediaStore/用户明确授权为主的访问方案。

## 测试与构建

```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_SDK_ROOT='E:\Data\Android'
$env:MINIPIC_KEYSTORE_PROPERTIES='D:\secure\minipic\keystore.properties'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease
```

当前版本包含 36 项 JVM 测试，覆盖扫描/分组、文件元数据变化、自然排序、表冠灵敏度及100阈值分页累计、索引钳制、fit 尺寸、采样预算、EXIF 映射和漫画 Bitmap 预算。12 项 Android instrumentation 测试已在 OWW211/API 30 通过，覆盖快速/反向目标、上下动画方向与中断接续、表冠100阈值和立即响应、触摸共用切图流程、失败/离页取消、三轴输入、触摸取消、GIF fit/换帧/暂停恢复、滑块持久化和标题退出。0.0.7 Debug/Release、Lint 和三类 APK 签名校验均成功。

Release 输出：

- `android/app/build/outputs/apk/release/app-universal-release.apk`
- `android/app/build/outputs/apk/release/app-armeabi-v7a-release.apk`
- `android/app/build/outputs/apk/release/app-arm64-v8a-release.apk`

真实密钥和 properties 必须位于仓库外；用 `MINIPIC_KEYSTORE_PROPERTIES` 指向属性文件，属性中的 `storeFile` 使用绝对路径。当前工程没有 native `.so`，ABI 分包是兼容性/分发选择，不保证体积更小。`apksigner verify --print-certs` 的结果和证书指纹应只从受保护的发布记录核对，不写入仓库文档。

## 设备回归测试

`android/app/src/androidTest/java/mini/pic/ViewerRegressionTest.kt` 使用 AndroidJUnitRunner，测试前保存设置，测试后恢复；只创建并删除 UUID 命名的应用 cache 目录，不修改共享存储图片。GIF 测试用 `UiAutomation.takeScreenshot()` 校验颜色变化和 fit 尺寸。

在专用测试设备或模拟器上，从 `android/` 执行：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

若设备已有正式签名 MiniPic，普通 Debug 证书不同，不能直接覆盖。为保留用户数据，本轮将 Debug APK 和 androidTest APK 用相同长期发布证书重签后执行 `adb install -r`，再运行：

```text
adb shell am instrument -w -r -e class mini.pic.ViewerRegressionTest mini.pic.test/androidx.test.runner.AndroidJUnitRunner
```

测试结束卸载 `mini.pic.test`，覆盖回正式 Release；不得为测试直接卸载用户主应用。设备需已唤醒。自动输入验证的是应用层事件处理，不替代实体表冠的机械刻度与手感验收。

## 0.0.6 历史验证

0.0.6 历史记录：当时 12 项 JVM 测试、Debug、Lint 和 signed Release 构建通过，并在 OWW211/API 30 完成安装启动及首扫观察。以上只作为历史背景，不代表 0.0.7 已完成相同验证。
## 尚需验收与后续工程工作

1. 手动验证 OWW211 实体表冠默认 40% 和 10%–200% 滑块范围、慢速碎片累计/快速连续多页/反向输入，以及漫画缩放开/关映射。
2. 长章节快速拖动、惯性滑行、缩放后平移和边缘回收，记录 GC、稳态帧时和峰值内存。
3. 核对 EXIF 图方向、小/大 GIF 的 fit 宽度和连续帧，后台/前台及 detach 生命周期；核对取消解码和失败加载后的显示/索引一致性。
4. 完成权限拒绝/URI 写授权和删除流程的端到端测试。
5. 按图库压力数据评估增量索引、回收选择器列表和 Activity 模块拆分。
