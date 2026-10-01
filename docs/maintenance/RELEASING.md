# MiniPic 发布与签名流程

当前版本：0.0.7（versionCode 7）。2026-10-01 已完成构建、签名验证和 OWW211 覆盖安装；实体表冠手感仍需人工确认。

## 仓库外签名配置

项目已有长期发布签名；真实 keystore 与属性文件必须保存在仓库外的安全目录。通过环境变量 `MINIPIC_KEYSTORE_PROPERTIES` 指向外部 `keystore.properties`，不要把实际文件复制到仓库根目录。

仓库根目录的 `keystore.properties.example` 仅为占位模板。可复制模板到仓库外，填写本机密钥信息；`storeFile` 使用 keystore 的绝对路径，不依赖 Gradle 相对路径。Windows 属性值建议用 `/` 路径分隔符。

```properties
storeFile=/absolute/path/to/minipic-release.jks
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=minipic-release
keyPassword=YOUR_KEY_PASSWORD
```

不要提交真实 `.jks`、密码或属性文件，不要压入公开 ZIP 或发送到工单。

## 构建签名包

先配置 JDK 17 和 Android SDK 35，再从仓库根目录进入 `android/`。

Windows PowerShell：

```powershell
$env:MINIPIC_KEYSTORE_PROPERTIES = Join-Path $env:USERPROFILE '.config\minipic\keystore.properties'
cd android
.\gradlew.bat clean testDebugUnitTest lintDebug assembleRelease
```

macOS / Linux：

```bash
export MINIPIC_KEYSTORE_PROPERTIES="$HOME/.config/minipic/keystore.properties"
cd android
./gradlew clean testDebugUnitTest lintDebug assembleRelease
```

确认环境变量确实指向有效外部配置。没有可用配置时生成的 Release 不可当作已签名交付物。当前 JVM 测试共 36 项，测试和 Lint 的实际结果必须记录，不能以历史构建代替。

输出目录为 `android/app/build/outputs/apk/release/`：

- `app-universal-release.apk`
- `app-armeabi-v7a-release.apk`
- `app-arm64-v8a-release.apk`

当前没有 native `.so`，三类 ABI 包体积可能相同；按设备与分发需求选包，不承诺 32 位包更小。

## 验证签名与版本

以下命令从 `android/` 运行，Build Tools 版本可按本机已安装版本调整：

```powershell
& "$env:ANDROID_SDK_ROOT\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs `
  .\app\build\outputs\apk\release\app-universal-release.apk

& "$env:ANDROID_SDK_ROOT\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs `
  .\app\build\outputs\apk\release\app-armeabi-v7a-release.apk

& "$env:ANDROID_SDK_ROOT\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs `
  .\app\build\outputs\apk\release\app-arm64-v8a-release.apk
```

将证书 SHA-256 与此前已确认的正式 APK 或长期发布证书记录核对。确认 applicationId 为 `mini.pic`、versionCode 为 7、versionName 为 0.0.7，并用同证书执行覆盖安装验收后再分发。构建成功不代表签名或设备验收已通过。

## 密钥维护

- 独立备份 keystore 与密码，至少保存两份离线副本。
- 更新必须继续使用同一长期发布证书，否则 Android 会拒绝覆盖安装。
- 不在 Gradle 文件中硬编码密码。
- 不把 keystore、密码、签名 properties 或解密后的备份加入 Git。
- 若怀疑密钥泄露，按目标分发渠道的密钥轮换流程处理；本地自持密钥不能通过改文件名“轮换”。

## 验收记录

当前最终产物位于 `android/app/build/outputs/apk/release/`：

- `app-universal-release.apk`
- `app-armeabi-v7a-release.apk`
- `app-arm64-v8a-release.apk`

三包大小均为 2,137,374 bytes。OWW211 最终安装 `app-armeabi-v7a-release.apk`，版本为 0.0.7，原有应用数据保留。

- 0.0.6：已签名与安装记录属于历史验收，见开发状态文档。
- 0.0.7：36 项 JVM、12 项 OWW211 instrumentation 测试通过，含上下滑动方向、150ms动画、100阈值立即切图及触摸共用路径断言；Debug/Release/测试包构建成功。Lint 0 errors / 16 warnings。Universal、armeabi-v7a、arm64-v8a 均通过 `apksigner verify`，同一长期证书，均为 2,137,374 bytes。OWW211 已从 0.0.6 无损覆盖更新，最终留下正式 armeabi-v7a signed Release；临时测试包已移除。实体表冠手感和全面权限拒绝流程不属于本次自动验证的结论。

## GitHub Release 清单

本地交付 staging 位于仓库根目录的 `dist/MiniPic-v0.0.7-release/`，该目录及压缩包由 `.gitignore` 忽略，不应作为源码提交内容。创建 GitHub Release 时，手工附加以下文件：

- 三个 ABI APK：Universal、`armeabi-v7a`、`arm64-v8a`。
- `SHA256SUMS.txt`，用于下载后校验。
- `RELEASE_NOTES.md`，或将其内容粘贴到 GitHub Release 描述。

建议使用 tag `v0.0.7`。上传前再次确认 APK 的 `applicationId`、版本号、证书指纹和 SHA-256；不要上传 keystore、签名 properties、Gradle `build/` 目录或包含测试图片/个人数据的归档。
