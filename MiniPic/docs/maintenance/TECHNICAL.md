# MiniPic 技术说明

> 更新：2026-09-25
> 当前版本：0.0.6（versionCode 6）
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
- EXIF 方向使用统一 raw/oriented 坐标映射；API 23-27 的预览额外应用方向变换。GIF/动画 WebP 检测为 Animatable 后保留动画预览，不叠静态区域，避免 tile 与动图帧错位。
- 表冠 VSCROLL/SCROLL/HSCROLL 读到原始 delta 后统一乘 `CROWN_SENSITIVITY=0.4`；普通页面和单图查看复用该值，列表滚动累积小数像素余量。
- 漫画模式为独立 `MangaView` 连续画布：相册内图片按自然数字文件名排序，`1, 2, 10` 不会发生字典序错位；每张图独立按屏幕等宽绘制，相邻图片零间距首尾拼接。
- 漫画页高先异步读取并建立前缀表；绘制/解码只处理当前视口附近页面，页面 Bitmap 按目标宽度采样并回收远离视口超过 3 页的缓存。单页解码使用约 2MP 像素预算，避免长章节多页高分辨率 Bitmap 同时驻留。
- 漫画模式初始 zoom=1（屏幕等宽），手指拖动支持任意停留和减速惯性；表冠“滚动缩放”开时缩放画布，关时纵向滚动画布。
- 外部 `ACTION_VIEW` 不进入漫画模式；漫画操作页针对当前可见页工作，隐藏对整个章节语义不明确的旋转操作。
- 复制通过流式 I/O 写入 MediaStore `IS_PENDING` 项，使用完整 `RELATIVE_PATH`，处理重名后才提交。失败时清理目标项并保留源图。
- 移动按“复制并提交目标 → 删除源文件”执行。无法删除源 URI 时不谎报移动成功，提示复制已完成但移动未完成。
- 删除失败不会截断或覆盖源文件。MediaStore/URI 授权失败会返回失败状态；可恢复的系统删除授权流程仍需设备端实测后补齐。
- 文件操作、图库扫描、封面解码使用不同执行器；Activity 销毁时关闭执行器并使过期图片解码回调失效。

## 权限

Manifest 声明 `READ_MEDIA_IMAGES`、限制到旧系统的 `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` 和 `MANAGE_EXTERNAL_STORAGE`。Android 11+ 首选所有文件访问；同时提供仅图片读取权限回退。执行复制、移动、删除和新建相册前再次检查写访问能力。实机 OWW211 上已观察到 `MANAGE_EXTERNAL_STORAGE=allow`，但本轮未能完成权限拒绝交互验收。

`MANAGE_EXTERNAL_STORAGE` 有显著分发限制，尤其是 Google Play。若要上架，需另行设计以 MediaStore/用户明确授权为主的访问方案。

## 测试与构建

```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_SDK_ROOT='E:\Data\Android'
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug assembleRelease
```

最近一次全量验证：12 项 JVM 单测通过，Debug、Lint、签名 Release 构建成功。Lint 当前 0 errors、6 warnings；警告主要是权限商店政策、target SDK 提示、固定竖屏、备份属性和重复图标密度资源。

签名 Release 输出：

- `android/app/build/outputs/apk/release/app-universal-release.apk`
- `android/app/build/outputs/apk/release/app-armeabi-v7a-release.apk`
- `android/app/build/outputs/apk/release/app-arm64-v8a-release.apk`

真实密钥和 keystore 位于仓库外 `E:\Vibe Coding\MiniPic-signing\`，配置模板和密钥验证流程见 `README.md`、`docs/maintenance/RELEASING.md`。32 位专用包可减小 ABI 包内容；签名前用 apksigner 确认证书指纹。

自动化测试覆盖扫描分组/隐藏目录/签名目录、同数量内容替换、嵌套目录、重名规则、自然数字文件名排序、表冠 0.4 换算、漫画 Bitmap 采样预算、区域像素预算与 EXIF 映射。12 项 JVM 测试全部通过。
0.0.6 Signed Release fresh install 已在 OWW211/API 30 稳定启动并完成首扫，主页可显示真实相册，约 31 MB PSS / 60 MB RSS。该 API 30 ROM 不识别 `READ_MEDIA_IMAGES`，测试通过 AppOps 临时授予 MANAGE_EXTERNAL_STORAGE；代码在无该权限时改用 MediaStore 只读 fallback。ColorOS 不可导出的文件访问设置 Activity 会被捕获并提示手动授权，不再让 SecurityException 使 Release 进程崩溃。
## 尚需验收与后续工程工作

1. 手动验证 OWW211 实体表冠约 40% 灵敏度，以及漫画模式下“表冠缩放开/关”两种映射。
2. 长章节快速拖动、惯性滑行、缩放后平移和边缘回收，记录 GC、稳态帧时和峰值内存。
3. 核对带 EXIF 旋转图片、GIF/动画 WebP 的方向和预览策略。
4. 完成权限拒绝/URI 写授权和删除流程的端到端测试。
5. 按图库压力数据评估增量索引、回收选择器列表和 Activity 模块拆分。
