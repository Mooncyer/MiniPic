# MiniPic 发布与签名流程

## 当前签名配置

开发机上的真实配置位于仓库外：

- Keystore：`E:\Vibe Coding\MiniPic-signing\minipic-release.jks`
- 属性文件：`E:\Vibe Coding\MiniPic-signing\keystore.properties`
- 真实属性文件应放在仓库外 `E:\Vibe Coding\MiniPic-signing\keystore.properties`；也可以设置 `MINIPIC_KEYSTORE_PROPERTIES` 指向其他安全位置。不要把真实 `.jks` 或属性文件复制到仓库根目录。

- 算法：RSA 4096，证书有效期 10,000 天

仓库根目录的 `keystore.properties.example` 不含有效凭据。不要把真实 `.jks` 或 `keystore.properties` 提交到 Git、压入公开 ZIP 或发送到工单。

## 构建签名包

从仓库根目录：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:ANDROID_SDK_ROOT='E:\Data\Android'
cd android
.\gradlew.bat clean testDebugUnitTest lintDebug assembleRelease
```

成功时 `android/app/build/outputs/apk/release/` 下包含 universal、armeabi-v7a 和 arm64-v8a APK。当前 `splits.abi` 会生成三者；按要求交付较小的 32 位包时选择 `app-armeabi-v7a-release.apk`。

若构建机的签名文件路径不同，设置环境变量覆盖 Gradle 默认路径：

```powershell
$env:MINIPIC_KEYSTORE_PROPERTIES='D:\secure\minipic\keystore.properties'
```

属性文件格式：

```properties
storeFile=D:/secure/minipic/minipic-release.jks
storePassword=<本机密钥库密码>
keyAlias=minipic-release
keyPassword=<本机私钥密码>
```

Windows 属性文件路径建议使用 `/`。密码仅保存在本机安全位置。

## 验证签名

使用 Android SDK Build Tools：

```powershell
& "$env:ANDROID_SDK_ROOT\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs `
  .\app\build\outputs\apk\release\app-universal-release.apk

& "$env:ANDROID_SDK_ROOT\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs `
  .\app\build\outputs\apk\release\app-armeabi-v7a-release.apk
```

确认证书 SHA-256 与本文件开头记录的一致后再分发。不同 build-tools 版本可调整目录名。

## 密钥维护

- 独立备份 `.jks` 与密码，至少保存两份离线副本。
- 更新必须继续使用同一个 keystore/alias，否则 Android 会拒绝覆盖安装。
- 不要在 `android/app/build.gradle` 中硬编码密码。
- 不要把 keystore、密码、签名 properties 或解密后的备份加入 Git。
- 若怀疑密钥泄露，按目标应用分发渠道的密钥轮换流程处理；本地自持密钥不能通过改文件名“轮换”。

## 0.0.6 已签名产物

- Universal APK：`android/app/build/outputs/apk/release/app-universal-release.apk`
- 32-bit APK：`android/app/build/outputs/apk/release/app-armeabi-v7a-release.apk`
- 64-bit APK：`android/app/build/outputs/apk/release/app-arm64-v8a-release.apk`
