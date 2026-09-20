# Healthxtc Server

面向小天才 Z8A 的健康传感器采集与 MCP 上报客户端。

## 功能

- 读取 XTC 厂商心率传感器 `88620080` 和体温传感器 `88620081`。
- 自动上传：定时触发后测量，20 秒内得到有效数据就立即上传；测量失败或完全没有数据则结束本次任务。
- 不建立上传队列，不保存待上传任务，也不自动重试失败的数据。
- 通过 WebSocket 监听 MCP 远程采集指令，兼容 `collect`、`collect-health-now` 和 `collect_health_now`。
- 自动任务和 MCP 远程任务结束后可发送成功/失败系统通知。
- 测试工具支持连接测试、只测量、测量并上传、自定义数据上传和日志查看。
- 使用 Conscrypt 与 DNS-over-HTTPS，以改善旧版 Android 的 TLS 和 DNS 兼容性。
- 后台服务持有 `PARTIAL_WAKE_LOCK`，用于降低设备休眠造成的 WebSocket 断开概率。
- 独立连接看门狗每 60 秒唤醒服务并发送 WebSocket 心跳；超过 90 秒没有响应会强制重连。
- 连接看门狗不依赖自动上传开关，关闭自动上传后仍会维持 MCP 指令连接。

## 配置

首次打开应用后进入“设置”：

1. MCP 服务器地址填写 Worker 根地址，例如 `https://example.workers.dev`，不要填写 `/health`。
2. 设置自动上传间隔（至少 1 分钟）。
3. 按需启用自动上传和结果通知。
4. 按需启用 WakeLock；开启后会增加耗电。
5. 保存后在“测试工具”中先运行“测试服务器连接”。

设备 ID 固定为 `xtc-z8a`，应用内不包含 API Key。当前服务端协议也不要求 API Key。

## 编译

1. 使用 Android Studio 打开本目录。
2. 等待 Gradle 同步和依赖下载完成。
3. 选择 `Build > Build APK(s)`。
4. 调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

项目要求 JDK 17、Gradle 8.7 左右和 Android SDK 35。最低 Android 版本为 6.0（API 23）。

## 替换应用图标

编译前直接用你自己的标准 PNG 覆盖下面这一张文件，文件名必须保持为 `app_icon.png`：

`app/src/main/res/drawable-nodpi/app_icon.png`

建议使用正方形的 512×512 PNG，不需要另外修改 Manifest。

## 权限与说明

- 首次打开需要授予“身体传感器”权限。
- Android 8.0 及以上为了持续监听 MCP 指令会运行前台服务，因此系统可能固定显示一条“正在监听 MCP 指令”的服务通知；设置中的通知开关控制的是每次采集结果通知。
- 持续 WakeLock 会增加耗电，并不能阻止厂商系统直接杀死进程；进程被杀后，系统会自动释放 WakeLock。
- 本项目只读取健康传感器数据，不应作为医疗设备或医疗诊断依据。
