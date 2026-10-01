# Contributing to MiniPic

感谢你关注 MiniPic。项目当前优先适配 OPPO Watch OWW211，并以 Android 原生 View 和 Kotlin 为主要技术栈。

## 开始开发

环境要求：

- JDK 17
- Android SDK Platform 35
- Android Studio（可选）

从仓库根目录进入 Android 工程：

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows PowerShell 使用 `gradlew.bat`：

```powershell
cd android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

目标设备回归测试需要已连接的 Android 设备或模拟器：

```bash
./gradlew connectedDebugAndroidTest
```

Release 签名配置必须放在仓库外，流程见 [`docs/maintenance/RELEASING.md`](docs/maintenance/RELEASING.md)。不要提交 keystore、密码、`local.properties` 或构建输出。

## 提交变更

1. 先在 Issue 中说明问题、设备型号和复现步骤；小型修复也可以直接提交 Pull Request。
2. 保持改动聚焦，沿用现有 Android View、执行器和异步取消模式。
3. 为行为变化补充 JVM 或 instrumentation 测试，并更新相关维护文档。
4. 提交前运行 `testDebugUnitTest lintDebug assembleDebug`，并检查 `git diff --check`。
5. Pull Request 中说明测试命令、设备/系统版本和仍未覆盖的风险。

## 报告问题

请使用 GitHub Issue 模板，并提供 MiniPic 版本、手表型号、Android 版本、复现步骤和不含隐私的日志或截图。不要在 Issue、Pull Request 或日志中粘贴签名密码、keystore 内容或个人图片。

## 许可证

代码和仓库文档按根目录 [`LICENSE`](LICENSE) 中的 GNU Affero General Public License version 3.0 发布。第三方依赖及其资源仍受各自许可证约束。
