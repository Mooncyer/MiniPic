# OPPO Watch 表冠输入说明与配置方法

## 设备信息

本记录基于已连接的 OPPO Watch 设备实测：

- Model：`OWW211`
- ADB serial：`cf5c164e`
- Android 输入设备：`/dev/input/event2`
- 输入设备名称：`pixart_pat9125`

## 结论

该 OPPO Watch 的表冠不是标准的 Android Rotary Encoder 输入设备。它在 Linux 输入层表现为：

```text
EV_REL / REL_WHEEL
```

因此不能只按下面的标准方式处理：

```text
InputDevice.SOURCE_ROTARY_ENCODER
MotionEvent.AXIS_SCROLL
```

也不能用下面的 ADB 命令准确模拟真实表冠：

```text
adb shell input roll 0 8
```

`input roll` 使用的是通用 trackball/roll 注入路径，与该设备的 `pixart_pat9125 / REL_WHEEL` 事件并不等价。

## 底层输入设备配置

### 查看所有输入设备

```text
adb shell getevent -lp
```

本设备关键输出：

```text
add device 3: /dev/input/event2
  name:     "pixart_pat9125"
  events:
    REL (0002): REL_WHEEL
```

其他设备：

- `event0`：`qpnp_pon`，电源键
- `event1`：`fts_ts`，触摸屏
- `event2`：`pixart_pat9125`，表冠/旋转输入
- `event3`：`gpio-keys`，实体按键
- `event4`：`aw-haptic-hv`，马达触觉反馈

### 抓取真实表冠原始事件

在表冠处于可操作状态时执行：

```text
adb shell getevent -lt /dev/input/event2
```

然后实际旋转表冠。典型输出：

```text
EV_REL       REL_WHEEL            fffffffb
EV_SYN       SYN_REPORT           00000000
EV_REL       REL_WHEEL            0000000a
EV_SYN       SYN_REPORT           00000000
```

数值为有符号 32 位相对增量：

- `0x0000000a` = `+10`
- `0xfffffff6` = `-10`
- 具体数值随旋转速度和固件滤波变化
- 每个 `REL_WHEEL` 后通常跟一个 `SYN_REPORT`

停止抓取：

```text
Ctrl+C
```

或结束对应的 ADB shell 进程。

## Android 应用层映射

Linux 输入层的 `REL_WHEEL` 会由 Android 输入系统转换成 Generic MotionEvent。由于厂商固件实现不同，应用层不应只依赖一个 source 或一个 axis。

推荐按以下顺序读取滚动增量：

1. `MotionEvent.AXIS_VSCROLL`
2. `MotionEvent.AXIS_SCROLL`
3. `MotionEvent.AXIS_HSCROLL`

并且不强制要求：

```text
InputDevice.SOURCE_ROTARY_ENCODER
```

原因：本设备底层设备声明的是 `REL_WHEEL`，不保证 Android 最终事件带有标准旋转编码器 source。

推荐的应用层判断逻辑：

```kotlin
if (event.action != MotionEvent.ACTION_SCROLL) return false

var delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
if (delta == 0f) delta = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
if (delta == 0f) return false
```

如果固件把事件转成普通 `MotionEvent.ACTION_MOVE`，则需要进一步根据 `event.source`、`event.device` 或系统日志确认；目前本设备已观察到的致命崩溃链路来自 `onGenericMotion`，所以首先应覆盖 `ACTION_SCROLL` 的非标准 source 情况。

## OPPO Watch 特有崩溃风险

当前版本中，WearVision 的 RSB 支持链路会在表冠事件中抛出：

```text
java.lang.IllegalStateException:
Could not find wearable shared library classes.
Please add <uses-library android:name="com.google.android.wearable" android:required="false" />
```

调用链关键部分：

```text
c8.e.onGenericMotion
android.view.View.dispatchGenericMotionEvent
androidx.appcompat.view.k.dispatchGenericMotionEvent
android.app.Activity.dispatchGenericMotionEvent
```

这说明：

- 表冠事件确实进入了 Android Generic Motion 分发；
- WearVision 的 RSB 处理器被触发；
- OPPO 固件上共享库检查失败；
- 异常没有在组件内部被捕获，导致应用进程崩溃。

## 推荐应用配置

### Manifest

显式保留可选 Wearable shared library：

```xml
<uses-library
    android:name="com.google.android.wearable"
    android:required="false" />
```

注意：这个声明只能保证缺少共享库时应用可以安装/加载，不能保证 WearVision 的 RSB 代码不会主动抛异常。因此还必须在应用层绕开不兼容的 RSB 路径。

### 页面滚动

推荐在 Activity 最外层统一拦截 Generic Motion：

```kotlin
override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
    if (rotaryDispatcher.dispatch(window.decorView, event)) {
        return true
    }
    return super.dispatchGenericMotionEvent(event)
}
```

处理器应当：

1. 检查 `ACTION_SCROLL`；
2. 读取 VSCROLL/SCROLL/HSCROLL；
3. 查找当前可见的 RecyclerView 或其他可滚动 View；
4. 将增量转换为像素；
5. 调用 `scrollBy(0, -pixels)`；
6. 成功消费事件后返回 `true`，阻止事件进入不兼容的 RSB 组件。

不要简单丢弃事件，否则表冠不闪退但也无法滚动。

### 增量换算

表冠增量通常很小且事件频率较高，建议做累积换算：

```kotlin
accumulated += delta
val pixels = (accumulated * 48f).roundToInt()
if (pixels != 0) {
    accumulated -= pixels / 48f
    target.scrollBy(0, -pixels)
}
```

`48f` 是起始灵敏度，不是设备固定值。建议现场调节范围：

对于一般的纯粹的滚动动作来说

- `2f`：慢，精细
- `4f`：默认起点
- `8f`：快速滚动

对于将表冠滚动映射为放大/缩小的事件的灵敏度需额外讨论。

### 目标 View 查找

优先级建议：

1. 当前显示页面中的 `RecyclerView`；
2. `NestedScrollView`；
3. 其他实现滚动能力的 View；
4. 主页 `ViewPager2` 不应直接调用 `fakeDrag`，除非明确在页面切换场景下启用。

当前图库的主要目标是列表中的 RecyclerView，包括：

- 本地图库列表/网格
- 手机图库文件夹列表
- 图片选择列表

### 调试日志

临时调试时记录：

```kotlin
Timber.tag("Rotary").d(
    "action=%d source=0x%x device=%s v=%f s=%f h=%f",
    event.action,
    event.source,
    event.device?.name,
    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
    event.getAxisValue(MotionEvent.AXIS_SCROLL),
    event.getAxisValue(MotionEvent.AXIS_HSCROLL),
)
```

正式版本不建议每个事件都打印日志，以免高频 I/O 影响滚动性能。

## 验证方法

### 检查当前应用进程

```text
adb shell dumpsys activity activities | grep -E "ResumedActivity|MainAty"
```

Windows PowerShell：

```powershell
adb shell dumpsys activity activities | Select-String "ResumedActivity|MainAty"
```

### 检查崩溃

```text
adb logcat -b crash -t 200
```

重点确认不再出现：

```text
Could not find wearable shared library classes
```

### 验证表冠

1. 启动腕间图库；
2. 进入本地图库；
3. 上下旋转表冠；
4. 确认列表滚动；
5. 连续快速旋转；
6. 切换网格/文件夹模式；
7. 进入图片详情后返回；
8. 再次旋转表冠；
9. 检查 `logcat -b crash`。

### 不可靠的模拟方式

下面命令只能验证应用是否能承受其他输入事件，不能证明 OPPO 表冠兼容：

```text
adb shell input roll 0 8
adb shell input roll 0 -8
```

最终验收必须使用实体表冠，并最好同时运行：

```text
adb shell getevent -lt /dev/input/event2
adb logcat
```

## 当前开发结论

OPPO Watch 表冠适配的核心不是“打开标准 rotary encoder 支持”，而是：

```text
REL_WHEEL 原始输入
→ Android Generic Motion 映射
→ 应用层多轴兼容读取
→ 当前 RecyclerView scrollBy
→ 消费事件，绕开不兼容 RSB
```

这是当前设备上最可靠的适配路径。
