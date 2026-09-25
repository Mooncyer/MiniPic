# MiniPic 阶段性开发总结

更新时间：2026-07-24 01:38（Asia/Shanghai）

## 1. 项目定位

MiniPic 是面向 Android 手表小屏的原生图库应用。

- 中文名：图库
- 英文名：MiniPic
- 包名：`mini.pic`
- 当前版本：`0.0.2`（`versionCode 2`）
- 工程目录：`E:\Data\MiniPic\android`
- 设计与需求：
  - `E:\Data\MiniPic\MiniPic_Elementary_Design.md`
  - `E:\Data\MiniPic\OPPOWatch_Crown.md`
  - `E:\Data\MiniPic\相册界面UI设计稿.png`
- 应用图标：`E:\Data\MiniPic\icon.png`
- 相册签名图片：`E:\Data\MiniPic\.MiniPicGallerySignature.png`

## 2. 当前技术基线

- Kotlin 原生开发，未使用 H5、Electron 或 Flutter。
- UI：Android 原生 View + Material Components。
- Material 组件：`com.google.android.material:material:1.12.0`。
- Kotlin：`1.9.20`。
- Java/JVM：17。
- Android Gradle Plugin：`8.1.0`。
- Gradle Wrapper：`8.13`。
- `compileSdk 35`。
- `targetSdk 30`（Android 11）。
- `minSdk 23`（Android 6.0）。
- ABI：`armeabi-v7a`、`arm64-v8a`，并生成 Universal APK。
- 主要源码目前集中在：
  - `android/app/src/main/java/mini/pic/MainActivity.kt`
- Manifest：
  - `android/app/src/main/AndroidManifest.xml`

> 当前仍是快速原型结构，后续应拆分 Activity、图库扫描、图片查看器、设置和文件操作模块，避免继续膨胀单文件。

## 3. 已实现功能

### 3.1 图库扫描

- 启动主应用时扫描 `/storage/emulated/0`。
- 支持扩展名：
  - `.jpg`
  - `.jpeg`
  - `.png`
  - `.webp`
- 文件夹作为相册。
- 相册内按文件修改时间降序排列。
- 默认使用最新图片作为封面。
- 不展示 `.MiniPicGallerySignature.png`。
- 仅有 `.MiniPicGallerySignature.png` 的目录仍被识别为空相册。
- 支持“扫描隐藏文件”设置并持久保存。
- 扫描在单线程后台执行，不阻塞主线程。
- 防止同一时间重复启动扫描。

### 3.2 相册页面

- 两列紧凑相册网格。
- 已删除旧版占空间的“相册/设置”按钮栏。
- 顶部仅保留紧凑的“相册”标题。
- 相册卡片采用 Material 圆角卡片。
- 封面后台解码，避免直接在主线程读取图片。
- 右滑进入设置页。
- 支持触摸上下滚动。
- 已接入表冠滚动分发：相册页表冠事件映射到 `ScrollView.scrollBy()`。

### 3.3 设置页面

- Material 风格圆角设置卡片。
- 包含以下入口/控件：
  - 主页相册选择入口（尚未实现实际选择逻辑）。
  - 新建相册。
  - 扫描隐藏文件开关。
  - 深色/浅色模式二选一控件。
  - 立即重新扫描。
- 深色/浅色设置使用 `SharedPreferences` 持久保存。
- 主题切换后通过 `recreate()` 立即重建界面。
- 左滑返回相册页。
- 支持表冠滚动。

### 3.4 新建相册

- 在 `/storage/emulated/0/Pictures/{相册名}` 创建目录。
- 检查空名称和 Windows/Android 文件名危险字符。
- 检查目录重复和创建失败。
- 创建成功后写入 `.MiniPicGallerySignature.png`。
- 随后触发图库重新扫描。

### 3.5 看图页面

- 页面为全屏纯图片，无标题栏、返回按钮、图片计数或菜单按钮。
- 初始缩放遵循完整显示原则：
  - `fitScale = min(viewWidth / imageWidth, viewHeight / imageHeight)`。
- 当前图片最长边完整显示在屏幕内，不裁切。
- 支持双指缩放。
- 支持表冠缩放。
- 用户缩放范围：10%～2000%。
- 表冠缩放使用指数曲线，避免高低倍率手感差异过大。
- 图片放大后支持单指拖动，并限制在图片边界内。
- 初始适应屏幕状态下：
  - 上滑：下一张。
  - 下滑：上一张。
  - 右滑：进入图片操作页。
  - 左滑：返回相册；外部查看时退出应用。
- 图片放大后优先拖动，不触发页面切换。
- 页面切换时临时旋转角度复位。

### 3.6 图片操作页

- 全屏 Material 圆角操作按钮。
- 当前入口：
  - 复制
  - 移动
  - 删除
  - 旋转
  - 设置为相册封面（仅图库内部查看时显示）
- 外部打开图片时隐藏“设置为相册封面”。
- 删除有二次确认。
- 删除成功后重新扫描图库。
- 旋转仅修改当前查看状态，不修改原始文件。
- 操作页左滑返回看图页。

### 3.7 外部图片打开

- Manifest 注册 `ACTION_VIEW` + `image/*`。
- 识别条件：`intent.action == ACTION_VIEW && intent.data != null`。
- 外部图片打开时直接进入全屏看图页。
- 外部打开路径不会：
  - 展示相册页；
  - 请求图库扫描权限；
  - 执行全盘扫描。
- 支持读取 `content://` URI。
- 已通过 ADB `ACTION_VIEW image/jpeg` 启动路径验证应用可直接进入并保持运行。

### 3.8 OPPO Watch 表冠

Manifest 已声明：

```xml
<uses-library
    android:name="com.google.android.wearable"
    android:required="false" />
```

应用在 Activity 层统一处理 `ACTION_SCROLL`，依次读取：

1. `AXIS_VSCROLL`
2. `AXIS_SCROLL`
3. `AXIS_HSCROLL`

不强制要求 `SOURCE_ROTARY_ENCODER`，兼容 OPPO Watch `pixart_pat9125 / REL_WHEEL` 映射。

页面行为：

- 相册页：滚动相册列表。
- 设置页：滚动设置列表。
- 看图页：缩放图片。
- 成功处理后返回 `true`，阻止事件继续进入不兼容的 WearVision RSB 路径。

## 4. 已修复的首版问题

0.0.1 存在的主要问题及当前处理：

- 外部打开误入相册页并执行扫描：已改为独立快速入口。
- 设置页无手表返回方式：已加入左滑返回。
- 看图页无法切换图片：已加入上下滑切图。
- 相册页表冠无效果：已将表冠增量映射至相册 `ScrollView`。
- 无真正主题控件：已加入深色/浅色二选一控件和持久化。
- 看图页顶部工具栏浪费空间：已全部删除并改成全屏图片。
- 相册页按钮栏浪费空间：已删除，改为手势切页。
- UI 缺乏 Material 设计：已引入官方 Material 3 主题与 MaterialCardView/SwitchMaterial/MaterialAlertDialog。
- 根布局手势容易被子 View 截断：已增加 `GestureFrameLayout.dispatchTouchEvent()` 观察手势，不依赖根 View 独占触摸事件。
- Kotlin 2.0 与旧版 D8/R8 元数据不匹配警告：已降为 Kotlin 1.9.20，clean build 后警告消失。

## 5. 尚未完成/需要继续开发

### P0：功能闭环

1. **复制图片**
   - 需要实现目标相册选择页。
   - 后台复制文件。
   - 处理重名、空间不足、权限失败。
   - 更新图库数据。

2. **移动图片**
   - 需要实现目标相册选择页。
   - 优先原子重命名，跨存储位置时复制后删除。
   - 失败时保证源文件不丢失。

3. **设置为相册封面**
   - 当前只有提示。
   - 需要持久保存用户封面选择。
   - 封面被删除后回退到最新图片。

4. **选择展示在主页的相册**
   - 当前只有入口。
   - 需要多选页面和持久化。

### P0：实机交互验收

1. 使用实体表冠验证：
   - 相册页慢速/快速滚动。
   - 设置页滚动。
   - 看图缩放方向和灵敏度。
   - 快速连续旋转后不崩溃。
2. 使用实体触摸验证：
   - 相册右滑进入设置。
   - 设置左滑返回相册。
   - 看图上下切换。
   - 看图右滑进入操作页。
   - 看图左滑返回。
   - 放大后拖图不误触页面切换。
3. 通过 `adb logcat -b crash` 确认没有：
   - `Could not find wearable shared library classes`
   - `IllegalStateException`
   - `OutOfMemoryError`

### P1：架构与性能

1. 当前扫描仍为每次主入口完整递归扫描，应改为：
   - 数据库缓存；
   - 首屏先读缓存；
   - 后台增量扫描；
   - path + size + modifiedTime 判断变化。
2. 当前代码集中在 `MainActivity.kt`，建议拆分：
   - `GalleryScanner`
   - `AlbumRepository`
   - `AlbumPage`
   - `SettingsPage`
   - `ViewerPage`
   - `PhotoView`
   - `RotaryDispatcher`
   - `FileOperations`
3. 当前相册列表是 `ScrollView + GridLayout`，图片多时会一次性创建所有卡片。应改为：
   - `RecyclerView + GridLayoutManager`；
   - 回收 item；
   - 只加载可见封面。
4. 当前外部 `content://` 图片使用 `decodeStream()`，可能解码原图。需增加：
   - URI bounds 读取；
   - `inSampleSize`；
   - 超大图分块或分级解码。
5. 当前线程池没有在 Activity 销毁时主动关闭；应改为生命周期安全的协程或明确释放。

### P1：权限与 Android 11

- Manifest 已声明 `MANAGE_EXTERNAL_STORAGE`，但尚未完整实现 Android 11 的“所有文件访问权限”检查与设置页跳转。
- 当前目标设备上可运行，但 Android 11 权限完整流程仍需实现：
  - `Environment.isExternalStorageManager()`；
  - `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`；
  - 权限拒绝时降级策略。
- 外部 URI 删除不一定有写权限，需要处理 `SecurityException` 和可恢复权限请求。

### P1：UI/UX

- 需要在手表保持亮屏时继续截图迭代，对照 `相册界面UI设计稿.png` 检查：
  - 标题高度；
  - 两列卡片尺寸；
  - 圆角；
  - 文字截断；
  - 状态栏和屏幕圆角安全区。
- 设置页需进一步压缩纵向间距并检查浅色模式对比度。
- 图片操作页需要图标和危险操作颜色层级。
- 手势切页应增加轻量动画/触觉反馈，但不能影响帧率。

### P2：后续格式

- GIF：尚未实现。
- MP4：尚未实现。
- 建议先完成静态图库功能闭环和性能优化，再加入媒体播放。

## 6. 当前构建结果

最近一次完整构建：

```powershell
cd E:\Data\MiniPic\android
$env:JAVA_HOME='E:\Data\Java\jdk-17.0.2'
$env:ANDROID_SDK_ROOT='E:\Data\Android'
.\gradlew.bat clean assembleDebug
```

结果：`BUILD SUCCESSFUL`。

APK：

```text
E:\Data\MiniPic\android\app\build\outputs\apk\debug\app-universal-debug.apk
E:\Data\MiniPic\android\app\build\outputs\apk\debug\app-armeabi-v7a-debug.apk
E:\Data\MiniPic\android\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

每个 Debug APK 当前约 5.94 MB。

Universal APK SHA-256：

```text
75434963EB36323473479C48BBE2C1F689FB13FCE851AFBA2CF92768239798C5
```

## 7. 已完成的实机验证

设备：

- OPPO Watch
- 型号：`OWW211`
- ADB serial：`cf5c164e`
- Android API：30
- 屏幕：378 × 496 px
- 密度：320 dpi（约 189 × 248 dp）

已验证：

- ADB 连接正常。
- APK 覆盖安装成功。
- `mini.pic/.MainActivity` 启动成功。
- 外部 `ACTION_VIEW image/jpeg` 可直接启动应用。
- 应用进程稳定，无 MiniPic Java crash。
- 外部查看测试内存：
  - Total PSS 约 34 MB；
  - Total RSS 约 54 MB；
  - 低于 100 MB 目标。

尚未完成的实机证据：

- 最终 Material 3 相册/设置页亮屏截图。
- 实体表冠完整交互录制/日志。

原因：最后一轮 ADB 检查时手表报告 `mWakefulness=Asleep`、`Display Power: state=OFF`，截图为黑屏。不能把黑屏测试冒充视觉验收。

## 8. 下一阶段推荐顺序

建议严格按以下顺序继续：

1. 唤醒手表并完成 0.0.2 UI、触摸、表冠实机验收。
2. 修复实机发现的手势方向、灵敏度、尺寸和遮挡问题。
3. 实现复制/移动目标相册选择及文件操作闭环。
4. 实现主页相册筛选和封面持久化。
5. 引入数据库与增量扫描。
6. 将相册页改为 RecyclerView，控制大量图片时的内存和启动时间。
7. 完善 Android 11 所有文件访问权限流程。
8. 做 Release 构建、混淆、签名和安装测试。
9. 最后再评估 GIF/MP4。

## 9. 已知注意事项

- 用户要求的手势语义当前为：
  - 相册页右滑进入设置；
  - 设置页左滑返回相册；
  - 看图页右滑进入操作；
  - 看图页左滑返回；
  - 看图页上下滑切图。
- 看图页不得加入常驻顶部/底部工具栏。
- 外部打开图片绝对不能触发全盘扫描。
- OPPO Watch 表冠不是标准 Rotary Encoder，不能只判断 `SOURCE_ROTARY_ENCODER`。
- `adb shell input roll` 不能替代实体表冠验收。
- 当前目录尚未确认建立独立 Git 仓库。后续继续大改前，应先建立版本基线并提交当前 0.0.2 状态。
