# XTC Health MCP（Cloudflare Worker）

这是小天才 Z8A 健康传感器项目的服务端 v1：

- D1 保存历史测量；
- Durable Object 保持手表 WebSocket，接收“立即采集”指令；
- Worker 提供带 Bearer 密钥的手表上报接口；
- `/mcp` 提供 3 个 MCP 工具，并用 OAuth 2.1 + 管理密码保护；
- APK 地址和密钥均由设置页填写，不硬编码。

> 传感器数值仅用于个人实验记录，不作为医疗诊断依据。

## 0. 要准备什么

- 一个 Cloudflare 账号；
- 云电脑里的 Node.js 20+；
- 已安装并登录 Wrangler：`npx wrangler login`。

## 1. 安装依赖

```bash
npm install
```

## 2. 创建 D1 与 KV

```bash
npx wrangler d1 create xtc-health
npx wrangler kv namespace create OAUTH_KV
```

把两条命令输出的 ID 分别填入 `wrangler.jsonc`：

- `REPLACE_WITH_D1_DATABASE_ID`
- `REPLACE_WITH_KV_NAMESPACE_ID`

## 3. 设置两个秘密

分别执行，按提示输入随机长密钥/密码。值不会写进代码或 `wrangler.jsonc`。

```bash
npx wrangler secret put DEVICE_API_KEY
npx wrangler secret put MCP_ADMIN_PASSWORD
```

- `DEVICE_API_KEY`：粘贴到手表 APK 设置页，建议随机 32 字节以上；
- `MCP_ADMIN_PASSWORD`：ChatGPT 第一次连接 MCP 时的授权页密码，不要与设备密钥相同。

可在云电脑生成随机值：

```bash
openssl rand -hex 32
```

## 4. 初始化数据库并部署

```bash
npm run typecheck
npm run db:remote
npm run deploy
```

部署完成后记下地址，例如：

```text
https://xtc-health-mcp.<你的子域>.workers.dev
```

检查：

```bash
curl https://xtc-health-mcp.<你的子域>.workers.dev/health
```

应返回 `{"ok":true,...}`。

## 5. APK 设置页填写

| 项目 | 示例 |
|---|---|
| 服务器地址 | `https://xtc-health-mcp.xxx.workers.dev` |
| 设备 ID | `xtc-z8a` |
| 设备 API Key | 第 3 步设置的 `DEVICE_API_KEY` |
| 自动采集间隔 | `30` 分钟 |

WebSocket 地址由 APK 从服务器地址生成：

```text
wss://xtc-health-mcp.xxx.workers.dev/api/device/ws?device_id=xtc-z8a
```

HTTP 请求均使用：

```text
Authorization: Bearer <DEVICE_API_KEY>
```

## 6. 先用 curl 验证服务器

```bash
export XTC_SERVER='https://xtc-health-mcp.xxx.workers.dev'
export XTC_KEY='刚才设置的设备密钥'

curl -X POST "$XTC_SERVER/api/device/heartbeat" \
  -H "Authorization: Bearer $XTC_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"device_id":"xtc-z8a","app_version":"test"}'

curl -X POST "$XTC_SERVER/api/device/measurements" \
  -H "Authorization: Bearer $XTC_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"device_id":"xtc-z8a","measured_at":"2026-09-04T12:00:00Z","source":"scheduled","heart_rate":75,"spo2":99,"temperature":36.5}'
```

## 7. 连接 ChatGPT/Codex

在支持自定义 MCP 的客户端添加：

```text
https://xtc-health-mcp.xxx.workers.dev/mcp
```

客户端会进行 OAuth 动态注册并打开授权页。输入 `MCP_ADMIN_PASSWORD` 后会出现工具：

- `get_health_history`
- `collect_health_now`
- `delete_health_data`

`collect_health_now` 采用两段式，避免 MCP 请求等待 60–120 秒超时：第一次发送指令并返回 `command_id`，约 75 秒后携带同一个 `command_id` 再调用即可查看结果。

删除工具默认 `confirm=false`，只显示将删除的数量；确认无误后才用 `confirm=true`。

## 8. 手表协议

### 上报测量

`POST /api/device/measurements`

```json
{
  "device_id": "xtc-z8a",
  "measured_at": "2026-09-04T12:00:00Z",
  "source": "scheduled",
  "heart_rate": 75,
  "spo2": 99,
  "temperature": 36.5,
  "wear_status": 2,
  "heart_rate_confidence": 92,
  "spo2_confidence": 54,
  "command_id": null
}
```

手动测量时 `source="manual"`，并原样带回 WebSocket 指令里的 `command_id`。

### WebSocket 下发

服务端发送：

```json
{"type":"collect","command_id":"UUID","timeout_seconds":120}
```

APK 收到后立即采集；成功后用上面的 HTTP 接口上报。

## 9. 保活设计

正式 APK 应先完成以下三层：

1. `HealthCollectorService` 前台服务，显示低优先级常驻通知；
2. `BOOT_COMPLETED` 接收器开机恢复服务和计划；
3. `AlarmManager` 每 30 分钟触发一次采集；WebSocket 断线指数退避重连。

`magisk-watchdog/` 是第四层兜底，每 3 分钟检查一次进程并尝试启动前台服务。它不提供 system UID，也不修改 `/system`，不应把循环缩短到几秒。

正式 APK 包名和服务类必须与脚本一致：

```text
com.lijinmu.xtchealth/.collector.HealthCollectorService
```

若 Agent 使用了别的名字，先改 `magisk-watchdog/service.sh`，再打包模块。

## 10. 注意事项

- 不要把真实密钥提交到 Git、聊天截图或日志；泄漏后重新执行 `wrangler secret put DEVICE_API_KEY`。
- Worker 日志不要输出 Authorization、OAuth token 或完整健康数据。
- APK 只在 120 秒测量窗口持有局部唤醒锁，平时不要永久持有。
- 原始 PPG 在当前测试中没有事件，v1 不采集；心率、血氧、体温取 `values[0]`。

官方资料：

- https://developers.cloudflare.com/d1/get-started/
- https://developers.cloudflare.com/durable-objects/best-practices/websockets/
- https://developers.openai.com/api/docs/mcp
