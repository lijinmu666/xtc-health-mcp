# 给 APK 开发 Agent 的实现要求

请基于已经验证能读取 XTC 厂商传感器的 Probe，制作正式 Android APK。

## 固定标识

- applicationId：`com.lijinmu.xtchealth`
- 前台服务：`com.lijinmu.xtchealth.collector.HealthCollectorService`
- minSdk 以 Z8A 实机 Android 版本为准；ABI 兼容 `armeabi-v7a`
- 不申请 `android:sharedUserId="android.uid.system"`，不要求平台签名或 priv-app

## 设置页（禁止硬编码）

必须有可从主界面进入的设置页，字段如下：

- Server Base URL：只接受 `https://`，去除末尾 `/`
- Device ID：默认 `xtc-z8a`，允许修改
- Device API Key：密码输入框，支持显示/隐藏、替换和清空
- Auto collection interval：默认 30 分钟，可关闭
- “测试连接”按钮：调用 `/api/device/heartbeat`，明确显示成功、HTTP 错误或网络错误

URL 可以放普通 SharedPreferences；API Key 不得写进源码、BuildConfig、资源 XML 或日志。优先用 Android Keystore 生成 AES/GCM 密钥后加密保存；若旧系统 Keystore 存在兼容问题，应提示配置失效并要求重新输入，不能退回明文日志。

## 传感器规则

| 数据 | type | 最终值 | 有效性 |
|---|---:|---|---|
| 心率 | 88620080 | `values[0]` | 20–250 |
| 体温 | 88620081 | `values[0]` | 20–45 |
| 血氧 | 88620082 | `values[0]` | 50–100；0 是未完成 |
| 佩戴状态 | 88620083 | `values[0]` | 原样整数记录 |

- 原始 PPG 88620084 暂不使用。
- 每次测量最长 120 秒；血氧由 0 变为有效值后才保存。
- 不把每秒事件都上传；一次会话只形成一条合并记录。
- 心率/血氧 `values[1]` 暂作为 confidence 原样上传，但 UI 不解释其医学意义。
- 测量结束必须 `unregisterListener` 并释放局部 wake lock。

## 网络协议

- 所有 HTTP/WebSocket 请求加 `Authorization: Bearer <Device API Key>`。
- WebSocket：`wss://.../api/device/ws?device_id=...`
- 收到 `collect` 后立即测量，上报时 `source=manual` 且带回 `command_id`。
- 自动计划上报 `source=scheduled`、`command_id=null`。
- 上传失败先存 Room/SQLite 本地队列，联网后重试；每条记录使用稳定本地 UUID 防止重复（服务端 v2 可增加幂等键）。
- WebSocket 重连退避建议：5、10、30、60、300 秒，成功后清零；发送文本 `ping` 可收到 `pong`。

## 保活

- 前台服务 + 低优先级持续通知；
- `BOOT_COMPLETED` 恢复；
- `AlarmManager.setAndAllowWhileIdle` / 系统支持时 exact alarm，用一次性 alarm 滚动安排，不依赖进程内 Timer；
- 被任务列表划掉后，在系统允许范围恢复，不做无限高频重启；
- 仅测量期间持有最多 120 秒的 `PARTIAL_WAKE_LOCK`；
- 提供“服务状态、上次成功连接、上次上传、下次计划时间”诊断页；
- 对系统杀后台再使用附带的 Magisk watchdog 兜底。

Manifest 至少需要：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.BODY_SENSORS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

如目标系统/API 要求，应补充与健康/前台服务类型对应的新权限；但不要虚构 XTC 私有权限。`BODY_SENSORS` 仍在运行时申请。

## 验收

1. 清空应用数据后，APK 内看不到预置服务器地址或真实 Key；
2. 设置后重启手表，前台服务恢复；
3. 自动测量产生一条 D1 记录；
4. MCP 手动采集收到 command_id，随后能查到完成结果；
5. 断网测量进入本地队列，恢复网络后补传；
6. 日志里不存在 URL 查询参数以外的密钥、Authorization 或 OAuth token。
