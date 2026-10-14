# ProxyConnect 代理连接

一个极简的 Android 全局代理工具：通过 VPN（VpnService）把**整机流量**转发到
HTTP / HTTPS / SOCKS5 代理服务器，并在通知栏显示连接状态。

## 功能

- 支持 **HTTP / HTTPS / SOCKS5**（免认证）三种上游代理，输入框左侧下拉选择
- **Material 3** 原生控件 + Material You 动态取色，界面仅保留居中的协议下拉框、`IP:端口` 输入框和开始 / 停止按钮
- 首次连接会弹出 **VPN 授权对话框**，授权后建立 TUN 网卡代理整机流量
- 通知栏显示：**连接时间**（含已连接时长计时）、**服务器 IP:端口**，并提供**「断开连接」**按钮
- 隧道断开自动重连（3s 起指数退避，上限 30s）
- 低内存占用：C 语言 hev-socks5-tunnel 用户态 TCP/IP 栈 + 单线程本地 SOCKS5 中转，release 构建启用 R8

## 工作原理

```
手机应用流量 → TUN 网卡(VpnService) → hev-socks5-tunnel(C 库)
    → 本地 SOCKS5 中转(127.0.0.1:10808) → 你配置的 HTTP/HTTPS/SOCKS5 上游代理
```

- DNS：劫持 53 端口 UDP 查询，经上游代理用 **DoH(1.1.1.1)** 解析，不泄露域名
- 本应用自身流量通过 `addDisallowedApplication` 绕过 VPN，避免回环

## 获取 APK（无需本地编译环境）

1. 把本项目推送到 GitHub 仓库（`main` 或 `master` 分支）
2. 打开仓库的 **Actions** 页签，`Build APK` 会自动运行（也可手动 Run workflow）
   - CI 会自动用 NDK 编译 hev-socks5-tunnel（armeabi-v7a / arm64-v8a / x86 / x86_64）
3. 构建完成后在该次运行的 **Artifacts** 中下载 `app-debug` / `app-release`
4. 推送 `v*` 格式的 tag（如 `v1.0.0`）时会自动创建 GitHub Release 并附上 APK

## 本地构建（可选）

需要 JDK 17、Android SDK 与 NDK：

```bash
mkdir -p build-native/jni
git clone --recursive --depth 1 https://github.com/heiher/hev-socks5-tunnel build-native/jni
cp native/Android.mk native/Application.mk native/tunnel_jni.c build-native/jni/
(cd build-native && ndk-build)
mkdir -p app/src/main/jniLibs/{armeabi-v7a,arm64-v8a,x86,x86_64}
cp build-native/libs/*/libhev-socks5-tunnel.so app/src/main/jniLibs/*/  # 按目录对应
gradle wrapper --gradle-version 8.9
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

## 使用说明

- Android 13+：首次连接会请求**通知权限**；Android 12 及部分国产 ROM 默认关闭通知，应用会弹窗引导去系统设置开启（否则通知栏看不到连接状态）
- 连接前需授予 **VPN 权限**（系统对话框）
- 若其他 VPN 应用处于「始终开启」，需先关闭或允许本应用共存

## 故障排查

- 连接失败时，应用状态区域和通知栏会显示**具体原因**：hev 隧道退出码、异常信息及内部日志末尾（`cacheDir/hev.log`）
- 常见原因：上游代理地址/协议不匹配、HTTP 代理不允许 CONNECT、代理要求账号密码
- 若一直显示「hev 隧道退出（代码 X）」，把界面上的完整错误文本发出来即可定位

## 已知限制

- 仅转发 IPv4 流量（未给 VPN 配置 IPv6 地址）
- 仅代理 DNS 查询（53 端口 UDP），其他 UDP（如 QUIC、游戏、语音通话）不转发——浏览器会自动回落到 TCP，不影响网页
- 上游 SOCKS5 仅支持免认证；HTTP 代理需允许 CONNECT 到任意 443 端口
- HTTPS 上游代理的证书需被系统信任（自签名证书会握手失败并在状态中提示）

## 技术栈

Kotlin 2.0.21 / AGP 8.7.3 / Gradle 8.9（CI 自动配置，无需本地环境）/
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)（MIT，CI 内从源码编译）
minSdk 26（Android 8.0），targetSdk 35
