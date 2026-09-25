# MiniPic 技术文档

> 更新时间：2026-07-25 23:29（Asia/Shanghai）
> 版本：0.0.2（versionCode 2）
> 本文档供其他 AI Agent 按此审查项目、理解架构、定位问题。

---

## 1. 项目全貌

| 属性 | 值 |
|------|-----|
| 应用名 | 图库（中文）/ MiniPic（English） |
| 包名 | `mini.pic` |
| 工程目录 | `E:\Data\MiniPic\android` |
| 代码入口 | `MainActivity.kt`（单文件 ~1300 行） |
| Kotlin | 1.9.20 |
| Java/JVM | 17 |
| AGP | 8.1.0 |
| Gradle | 8.13 |
| compileSdk | 35 |
| targetSdk | 30（Android 11） |
| minSdk | 23（Android 6.0） |
| Material | com.google.android.material:1.12.0 |
| ABI | armeabi-v7a, arm64-v8a（Universal APK） |
| 目标设备 | OPPO Watch OWW211（378×496, 320dpi） |
| 包体 | ~6 MB（debug） |
| 运行内存 | ~34 MB PSS / ~54 MB RSS |

### 核心文件

```
E:\Data\MiniPic\
├── MiniPic_Elementary_Design.md  # 原始需求文档
├── OPPOWatch_Crown.md            # 表冠适配技术调研
├── DEVELOPMENT_STATUS.md         # 开发状态与待办
├── TECHINICAL.md                 # 本文档
├── 相册界面UI设计稿.png           # UI 设计稿
├── icon.png                      # 应用图标
├── .MiniPicGallerySignature.png   # 相册标识图
├── MiniPic_v0.0.2-20260725.apk   # 导出安装包
└── android/
    ├── build.gradle
    ├── settings.gradle
    ├── gradle.properties
    └── app/
        ├── build.gradle
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/mini/pic/MainActivity.kt   ← 全部代码
            └── res/
                ├── values/styles.xml
                ├── values-night/styles.xml
                ├── drawable/minipic_gallery_signature.png
                └── mipmap-*/ic_launcher.png
```

---

## 2. 架构总览

当前为**单 Activity + 纯代码布局**架构，无 Fragment、无 XML 布局、无数据层分离。

### 2.1 页面状态机

```
enum class Page {
    ALBUMS,       // 相册主页（两列网格）
    SETTINGS,     // 设置页
    VIEWER,       // 看图页（全屏）
    ACTIONS,      // 图片操作页
    PICK_ALBUM,   // 目标相册选择（复制/移动）
    SELECT_ALBUMS // 主页相册筛选
}
```

`page` 变量追踪当前页面，手势逻辑、表冠逻辑均根据 `page` 分发。

### 2.2 页面切换机制

- `replaceContent(view)` → `stableRoot.removeAllViews()` + `addView(newView)` → 无 Fragment 事务
- 进入设置/操作页有**平移动画**：`animateSlideInFromX()` / `animateSlideOutToRight()`
- 看图上下切图有**垂直滑动动画**：`slideToImage(direction)`
- 动画期间 `animating = true` 防止连击

### 2.3 数据流

```
文件系统 → scan() → albums: Map<folder, List<Pic>>
                ↓
         album_cache.json (JSON 序列化持久)
                ↓
         buildAlbumsPage() → GridLayout
                ↓
         albumCard() → 异步 decodeSampled() 设封面
```

### 2.4 手势系统

```
GestureFrameLayout (FrameLayout 子类)
├── onInterceptTouchEvent()
│   ├── 左边缘 → 右滑 → 返回（全局）
│   ├── 右边缘 → 左滑 → 进入子页
│   └── 其他 → 传给子 View
├── touchObserver → observeGestures()
│   └── Page.VIEWER + atFitScale → 垂直滑切图
└── swipeObserver → onEdgeSwipe()
```

### 2.5 表冠系统

```
dispatchGenericMotionEvent(Activity)
├── ACTION_SCROLL → 多轴读取 (VSCROLL/SCROLL/HSCROLL)
├── Page.VIEWER + crownZoom=true → zoomBy(-delta)
├── Page.VIEWER + crownZoom=false → 累计切换 (crownDelta + 时间门)
├── Page.ALBUMS → albumScroll.scrollBy
├── Page.SETTINGS → settingsScroll.scrollBy
├── Page.PICK_ALBUM → pickerScroll.scrollBy
└── Page.SELECT_ALBUMS → selectScroll.scrollBy
```

---

## 3. 核心模块详解

### 3.1 图库扫描 (scan / walk)

```
scan()
├── 单线程 io.execute {}
├── walk() 递归遍历 /storage/emulated/0
├── 构建 LinkedHashMap<folderPath, List<Pic>>
├── 按修改时间降序排列
├── 清理失效封面
├── saveCache() → album_cache.json
├── 差异判断：数据未变则跳过 Grid 重建
└── 更新相册/选择器页面
```

- `Pic` 数据类：`path`, `folder`, `modified`
- `walk()` 跳过隐藏目录（除非设置开启）
- `scanning` 标志防止并发

### 3.2 相册页面 (buildAlbumsPage)

```
buildAlbumsPage()
├── ScrollView
│   └── LinearLayout (垂直)
│       ├── "相册" 标题
│       └── GridLayout (2列)
│           └── albumCard(folder, photos) × N
```

**albumCard：**
- MaterialCardView（圆角 12dp）
- ImageView 封面（180×180 采样解码，`RGB_565`）
- 文件夹名（单行截断）

**封面解码：**
- 优先取 `coverCache`（LRU，最大 24 项）
- 未命中则 `io.execute` → `decodeSampled()` → 回 UI 线程设图

**主页筛选：**
- `homeSelectedFolders()` 从 `SharedPreferences` 读 JSON 字符串
- `populateAlbumGrid()` 只渲染选中相册

### 3.3 看图页面 (buildViewerPage)

```
buildViewerPage()
├── FrameLayout (黑色背景)
│   └── PhotoView (自定义 View)
├── io.execute
│   └── decodeDrawable(uri) → Drawable
└── viewer?.setDrawable(drawable)
    └── (drawable as? Animatable)?.start()
```

**PhotoView（自定义 View）：**
- 缩放：`ScaleGestureDetector` + 双指 / 表冠
- 拖动：单指（放大后）
- 范围：10%～2000%
- 中心锚点缩放

### 3.4 图片操作页 (buildActionsPage)

```
buildActionsPage()
├── LinearLayout (垂直居中)
│   ├── 复制 → showAlbumPicker()
│   ├── 移动 → showAlbumPicker()
│   ├── 删除 → confirmDelete()
│   ├── 旋转 → rotation += 90°
│   └── 设为封面 → setAsCover() (仅内部)
```

**文件操作（MediaStore）：**
- 复制：`mediaStoreCopy()` → ContentValues + IS_PENDING + openOutputStream
- 移动：复制 + `mediaStoreDelete()`
- 删除：`mediaStoreDelete()` → 先查 MediaStore URI，再 contentResolver.delete
- 所有操作在 `io.execute` 后台执行

**外部图片（ACTION_VIEW）：**
- 优先解析 `content://` URI 为真实路径
- 真实路径 = 直接操作原图
- 解析失败 = 复制到 `cache/external/` 后操作副本

### 3.5 设置页面 (buildSettingsPage)

| 控件 | 功能 |
|------|------|
| 主页相册 | 打开 `SELECT_ALBUMS` 页面，开关控制显示 |
| 新建相册 | 输入名称 → `mkdirs()` → 写签名图片 → 扫描 |
| 表冠滚动缩放 | 开关，控制看图页表冠行为 |
| 扫描隐藏文件 | 开关，控制 walk() 是否遍历隐藏目录 |
| 外观 | 深色/浅色二选一 → `recreate()` |
| 重新扫描 | 手动触发 scan() |

### 3.6 手势框架 (GestureFrameLayout)

```
class GestureFrameLayout : FrameLayout
├── touchObserver: (MotionEvent) → Unit
│   └── 所有触摸事件（仅观察，不消费）
├── swipeObserver: (dx, fromLeft) → Unit
│   └── 边缘滑动手势确定后消费
├── onInterceptTouchEvent()
│   ├── 左边缘24dp内右滑 → 拦截 → 返回
│   ├── 右边缘24dp内左滑 → 拦截 → 进入子页
│   └── 其他 → 不拦截，向下传递
└── onTouchEvent()
    └── ACTION_UP → 触发 swipeObserver
```

### 3.7 外部图片打开

```
onCreate / onNewIntent
├── intent.action == ACTION_VIEW
├── showExternal(uri)
│   ├── content://media/external/ → 查 DATA 列得真实路径
│   ├── DocumentsContract → 解析 docId
│   ├── file:// → 直接取路径
│   └── 全失败 → 复制到 cache/external/
├── viewerPics = [Pic(localPath)]
└── buildViewerPage()
```

退出时 `finish()` 回到调用方，不触发全盘扫描。

---

## 4. 性能要点

### 4.1 已知瓶颈

| 问题 | 位置 | 影响 |
|------|------|------|
| GridLayout 无回收 | `buildAlbumsPage()` | 所有卡片同时创建，相册多时卡顿 |
| 单 IO 线程排队 | `io = Executors.newSingleThreadExecutor()` | 封面解码和扫描争抢线程 |
| 全量扫描 | `walk()` 递归所有目录 | 首次启动慢 |
| 无数据库缓存 | `albums` 纯内存 + JSON 快照 | 不支持增量更新 |
| 单文件架构 | `MainActivity.kt` 1300+ 行 | 维护困难，模块耦合 |

### 4.2 已做优化

- ✅ Bitmap LRU 缓存（`coverCache`，最大 24 项）
- ✅ 扫描结果差异判断（相册数+图片数无变化则跳过重建）
- ✅ `RGB_565` 配置（缩小 Bitmap 内存 50%）
- ✅ `inSampleSize` 采样解码（封面 180×180，看图 1000×1000）
- ✅ IO 线程 `onDestroy` 时关闭
- ✅ 动画硬件加速层（`LAYER_TYPE_HARDWARE`）
- ✅ 表冠事件消费拦截，防止 WearVision RSB 崩溃

### 4.3 建议优化方向

```
优先级 P0:
  1. GridLayout → RecyclerView + GridLayoutManager（最大收益）
  2. 全量扫描 → 增量扫描（FileObserver / path+size+mtime 对比）
  3. Page 状态 → Navigation Component 或 ViewFlipper
  4. 单文件 → 拆分模块

优先级 P1:
  5. 数据库缓存（Room / SQLite）
  6. 协程替换 SingleThreadExecutor
  7. 超大图分块解码（BitmapRegionDecoder）
  8. LRU 图片内存池
  9. 英语语言适配
```

---

## 5. 权限体系

| 权限 | 用途 | 获取方式 |
|------|------|----------|
| `READ_EXTERNAL_STORAGE` | 扫描读取图片 | `requestPermissions()` |
| `WRITE_EXTERNAL_STORAGE` | 删除/移动/复制 | `requestPermissions()` |
| `MANAGE_EXTERNAL_STORAGE` | Android 11+ 写权限 | `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` |

- `ensureWriteAccess(onGranted)` 统一检查，未授权时弹对话框引导
- ADB 授权：`adb shell appops set mini.pic MANAGE_EXTERNAL_STORAGE allow`

---

## 6. 表冠适配要点

参考 `OPPOWatch_Crown.md`，核心策略：

1. Manifest 声明 `<uses-library android:name="com.google.android.wearable" android:required="false" />`
2. `dispatchGenericMotionEvent` 统一拦截所有 `ACTION_SCROLL`
3. 按 `AXIS_VSCROLL → AXIS_SCROLL → AXIS_HSCROLL` 顺序读取
4. 不强制 `SOURCE_ROTARY_ENCODER`
5. 看图表冠：开启时缩放（指数曲线 `exp(delta * 0.055f)`），关闭时 Snap 切换（累计阈值 8 + 时间门 400ms）
6. 页面滚动：`-delta * dp(3)` 像素步进
7. 返回 `true` 消费事件，防止进入 WearVision RSB 死路

---

## 7. 构建与部署

```powershell
# 完整构建
cd E:\Data\MiniPic\android
$env:JAVA_HOME='E:\Data\Java\jdk-17.0.2'
$env:ANDROID_SDK_ROOT='E:\Data\Android'
.\gradlew.bat clean assembleDebug

# APK 位置
android\app\build\outputs\apk\debug\
├── app-universal-debug.apk     # 通用包
├── app-armeabi-v7a-debug.apk   # 32位
└── app-arm64-v8a-debug.apk     # 64位

# 安装到手表
adb -s cf5c164e install -r app-universal-debug.apk

# 启动
adb shell am start -n mini.pic/.MainActivity

# 外部图片打开测试
adb shell am start -a android.intent.action.VIEW `
  -d content://media/external/images/media/ID `
  -t image/jpeg

# 查看崩溃
adb logcat -b crash | Select-String "mini.pic"
```

---

## 8. 开发状态摘要

### 已完成
- [x] 图库全盘扫描 + 缓存
- [x] 相册网格页面
- [x] 看图页面（缩放/拖动/上下切图）
- [x] 图片操作（复制/移动/删除/旋转/设封面）
- [x] 设置页面
- [x] 外部图片打开
- [x] OPPO Watch 表冠适配
- [x] 深色/浅色模式
- [x] 主页相册筛选
- [x] 动画滑入/滑出
- [x] GIF 支持
- [x] 边缘手势（左滑返回、右滑进入）
- [x] Bitmap LRU 缓存
- [x] 扫描结果差异判断

### 未完成（P0）
- [ ] RecyclerView 替换 GridLayout
- [ ] 增量扫描（数据库）
- [ ] 代码模块拆分
- [ ] 英语语言适配
- [ ] Android 11 权限完整流程
- [ ] Release 混淆签名
- [ ] UI 设计稿实机验收
- [ ] 大屏适配
