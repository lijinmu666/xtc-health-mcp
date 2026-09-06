# XTC Health MCP

面向小天才 Z8A（XTC）的个人健康传感器实验项目。手表端采集数据，Cloudflare Worker 接收并保存到 D1，同时通过 MCP 工具查询历史、发起立即采集和清理旧数据。

> 本项目仅用于个人实验和数据记录，不能用于医疗诊断。

## 当前状态

- 数据类型：心率、体温；
- 已移除：血氧（`spo2`）、血氧置信度（`spo2_confidence`）和佩戴状态（`wear_status`）；
- `/mcp`：无需 OAuth；
- 设备 HTTP/WebSocket 接口：无需 API Key；
- Magisk 保活模块：已移除，目前不需要；
- APK 的服务器地址和设备 ID 仍由设置页配置，不应硬编码。

## 项目信息

- 项目维护：李瑾沐（GitHub：`lijinmu666`）
- 代码协助：OpenAI Codex

## Cloudflare 资源

- Worker：`xtc-health-mcp`
- D1 绑定：`DB`
- Durable Object 绑定：`DEVICE_HUB`
- 默认设备 ID：`xtc-z8a`

安装依赖并检查：

```bash
npm install
npm run typecheck
```

初始化新数据库并部署：

```bash
npm run db:remote
npm run deploy
```

已经初始化过的 D1 不要重复执行建表脚本。

## 服务地址

健康检查：

```text
GET https://xtc-health-mcp.<你的子域>.workers.dev/health
```

设备心跳：

```text
GET  /api/device/heartbeat
POST /api/device/heartbeat
```

上传测量：

```text
POST /api/device/measurements
Content-Type: application/json
```

请求示例：

```json
{
  "device_id": "xtc-z8a",
  "measured_at": "2026-09-06T12:00:00Z",
  "source": "manual",
  "heart_rate": 75,
  "temperature": 36.5,
  "heart_rate_confidence": 92,
  "command_id": null
}
```

字段要求：

- `device_id`：1–64 个字母、数字、点、下划线或连字符；
- `measured_at`：可解析的 ISO 8601 时间；
- `source`：只能是 `manual` 或 `scheduled`；
- `heart_rate`：可选，范围 20–250；
- `temperature`：可选，范围 20–45；
- `heart_rate_confidence`：可选；
- `command_id`：手动远程采集时原样返回。

旧 D1 数据库中可能仍保留 `spo2`、`spo2_confidence`、`wear_status` 列。为避免破坏已有数据库，服务端不会删除这些列，但已经不再写入或查询它们。

## WebSocket

```text
wss://xtc-health-mcp.<你的子域>.workers.dev/api/device/ws?device_id=xtc-z8a
```

服务端下发：

```json
{"type":"collect","command_id":"UUID","timeout_seconds":120}
```

APK 收到后采集心率和体温，再通过测量接口上传。

## MCP

连接地址：

```text
https://xtc-health-mcp.<你的子域>.workers.dev/mcp
```

提供三个工具：

- `get_health_history`：查询历史数据；
- `collect_health_now`：向在线手表发送立即采集指令；
- `delete_health_data`：预览或删除指定时间以前的数据。

## 安全说明

当前版本没有 OAuth 和设备 API Key。任何知道 Worker 地址的人都可以调用设备接口，因此不要公开完整地址，也不要在日志、截图或公开仓库中写入个人健康数据。

## 官方资料

- https://developers.cloudflare.com/d1/get-started/
- https://developers.cloudflare.com/durable-objects/best-practices/websockets/
- https://developers.openai.com/api/docs/mcp
