# MiniPic 技术说明

> 更新：2026-09-25
> 当前版本：0.0.3（versionCode 3）
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

- API 28+ 使用 `ImageDecoder`；在解码回调中把目标图像限制在显示尺寸约 2 倍内。API 23–27 先读 bounds，再用 `inSampleSize` 解码静态 Bitmap。
- `content://` URI 直接异步解码，不会在主线程整图复制到缓存。`file://` 和本地路径都走对应的文件输入流。
- PhotoView 负责 fit-center、缩放、平移和边界限制；表冠由 Activity 的 `dispatchGenericMotionEvent` 读取 VSCROLL/SCROLL/HSCROLL 后分发。
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

自动化测试覆盖扫描分组/隐藏目录/签名目录、相同数量下的内容替换、嵌套相对目录和重名规则。OWW211 实机已通过主页、设置/返回、相册查看、操作页和外部 file URI 查看；隔离目录复制与移动前后 SHA-256 验证一致，移动后源删除成功。曾由缓存中的已删除测试截图触发封面解码崩溃，已增加不存在文件/URI 的安全解码处理并重复验收选择页无 crash。删除没有对用户图片执行实机确认。

设备测得真实图库首页约 47.1 MB PSS / 73.7 MB RSS；选择器和测试操作过程观测到约 62.7 MB PSS / 83.8 MB RSS。ADB 注入期间的 gfxinfo 记录为 54 帧中 38 帧 janky、P95 650ms，该样本混入冷启动、全盘扫描和自动化注入，不能代表稳态性能。实体 REL_WHEEL 表冠、权限拒绝回退、大图/GIF 压力和稳态帧时仍需手动/压力验收。

## 尚需验收与后续工程工作

1. 手动验证 OWW211 实体表冠方向/灵敏度和权限拒绝回退。
2. 在大图/GIF 与稳态滚动下记录耗时、帧时及峰值 PSS/RSS。
3. 按图库规模测量全盘扫描后，再决定是否引入 SQLite/增量索引。
4. 将相册选择器/主页筛选从一次性 View 列表改为可回收列表。
5. 按职责继续从 MainActivity 抽出文件操作、图片查看器和表冠分发模块。
