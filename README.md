# ProxyConnect 代理连接

一个极简的 Android 代理连接工具：连接 HTTP / HTTPS / SOCKS5 代理服务器，并在通知栏显示连接状态。

## 功能

- 支持 **HTTP / HTTPS / SOCKS5**（免认证）三种代理协议，输入框左侧下拉选择
- **Material 3** 原生控件 + Material You 动态取色，界面仅保留居中的协议下拉框、`IP:端口` 输入框和开始 / 停止按钮
- 前台服务保持长连接，对端断开或握手失败时**自动重连**（3s 起指数退避，上限 30s）
- 通知栏显示：**连接时间**（含已连接时长计时）、**服务器 IP:端口**，并提供**「断开连接」**按钮
- 低内存占用：单工作线程 + 原生 Socket，无任何网络库；release 构建启用 R8 压缩混淆

## 获取 APK（无需本地编译环境）

1. 把本项目推送到 GitHub 仓库（`main` 或 `master` 分支）
2. 打开仓库的 **Actions** 页签，`Build APK` 会自动运行（也可手动 Run workflow）
3. 构建完成后在该次运行的 **Artifacts** 中下载 `app-debug` / `app-release`
4. 推送 `v*` 格式的 tag（如 `v1.0.0`）时会自动创建 GitHub Release 并附上 APK

## 本地构建（可选）

需要 JDK 17 与 Android SDK：

```bash
gradle wrapper --gradle-version 8.9   # 首次生成 gradlew
./gradlew assembleDebug
```

## 可选：正式签名

默认 release APK 使用 debug 签名（可直接安装）。如需正式签名，在仓库
Settings → Secrets and variables → Actions 添加：

| Secret | 说明 |
| --- | --- |
| `KEYSTORE_BASE64` | keystore 文件的 base64 内容（`base64 -w0 release.jks`） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |

## 连接行为说明

- **HTTP**：与代理端口建立 TCP 连接并保持
- **HTTPS**：TCP + TLS 握手（证书需被系统信任，自签名证书会握手失败并在状态中提示）
- **SOCKS5**：完成 RFC 1928 免认证（NO-AUTH）方式协商；服务器要求账号密码时状态栏会给出提示
- 连接期间通过阻塞读取探测存活，服务器主动断开后自动重连

## 其他说明

- Android 13+ 首次点击「开始连接」会请求通知权限，拒绝后服务仍可运行，只是通知栏不显示
- 前台服务使用 `specialUse` 类型（Android 14+ 要求），无 `dataSync` 类型的 6 小时运行限制
- minSdk 26（Android 8.0），targetSdk 35，compileSdk 35
- 技术栈：Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.9（CI 自动配置，无需本地环境）
