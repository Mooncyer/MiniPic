# MiniPic 技术说明

> 更新：2026-09-25
> 当前版本：0.0.4（versionCode 4）
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
│   ├── MainActivity.kt       # 页面、权限、导航、文件 I/O 协调、PhotoView/手势
│   └── GalleryModel.kt       # Pic、GalleryScanner、快照比较、目录与重名规则
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
- 表冠 VSCROLL/SCROLL/HSCROLL 读到原始 delta 后统一乘 `CROWN_SENSITIVITY=0.4`，页面滚动、图像缩放和翻图累计共用该值；列表滚动累积小数像素余量。
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

最近一次全量验证：JVM 单测通过，Debug、Lint、Release 构建成功。Lint 当前 0 errors、6 warnings；警告主要是权限商店政策、target SDK 提示、固定竖屏、备份属性和重复图标密度资源。Release 未配置正式签名；构建成功不代表可直接发布。

自动化测试覆盖扫描分组/隐藏目录/签名目录、相同数量下的内容替换、嵌套相对目录、重名规则、表冠 0.4 换算、区域解码像素预算和 EXIF 八方向映射。9 项 JVM 测试均通过。
0.0.4 在 OWW211/API 30 上可正常安装并打开约 7.8 MB JPEG；冷启动查看器约 38.9 MB PSS / 64.7 MB RSS，最终返回主页后约 46.6 MB PSS / 73.3 MB RSS，无 crash。该 JPEG 的内容本身是缩放后仍显得像素化的素材；ADB `input roll` 未触发标准 ACTION_SCROLL，且系统拒绝对触摸 event 节点执行 `sendevent`，因此本轮不能声称已实测 pinch 后区域细节。需用户在手表上实际放大并确认 tile 清晰度、EXIF 旋转和高倍率内存。

## 尚需验收与后续工程工作

1. 手动验证 OWW211 实体表冠约 40% 灵敏度、方向和快慢滚动。
2. 实际放大高清静态图，确认区域 tile 清晰度、平移覆盖与 EXIF 方向；记录峰值 PSS/RSS 和滚动帧时。
3. 核对 GIF/动画 WebP 继续动画但保持预览分辨率的预期行为。
4. 完成权限拒绝/URI 写授权和删除流程的端到端测试。
5. 按图库压力数据评估增量索引、回收选择器和 Activity 模块拆分。
