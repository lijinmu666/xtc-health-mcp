import { createMcpHandler, McpServer } from "@modelcontextprotocol/server";
import { DurableObject } from "cloudflare:workers";
import { z } from "zod";

interface Env {
  DB: D1Database;
  DEVICE_HUB: DurableObjectNamespace;
  DEFAULT_DEVICE_ID: string;
  AUTO_CLEANUP_THRESHOLD_BYTES: string;
  AUTO_CLEANUP_RETENTION_HOURS: string;
}

type MeasurementInput = {
  device_id: string;
  measured_at: string;
  source: "scheduled" | "manual";
  heart_rate?: number | null;
  temperature?: number | null;
  heart_rate_confidence?: number | null;
  command_id?: string | null;
};

const jsonHeaders = { "content-type": "application/json; charset=utf-8" };

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: jsonHeaders });
}

function positiveInteger(value: string, fallback: number): number {
  const parsed = Number.parseInt(value, 10);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function autoCleanup(env: Env): Promise<void> {
  const thresholdBytes = positiveInteger(env.AUTO_CLEANUP_THRESHOLD_BYTES, 1024 * 1024);
  const retentionHours = positiveInteger(env.AUTO_CLEANUP_RETENTION_HOURS, 24);
  const probe = await env.DB.prepare("SELECT 1 AS ok").all();
  const sizeBefore = Number(probe.meta.size_after ?? 0);

  if (sizeBefore < thresholdBytes) {
    console.log(JSON.stringify({ event: "auto_cleanup_skipped", size_before: sizeBefore, threshold_bytes: thresholdBytes }));
    return;
  }

  const cutoff = new Date(Date.now() - retentionHours * 3600_000).toISOString();
  const [commands, measurements] = await env.DB.batch([
    env.DB.prepare(
      `DELETE FROM commands
       WHERE created_at < ? OR result_measurement_id IN
         (SELECT id FROM measurements WHERE measured_at < ?)`,
    ).bind(cutoff, cutoff),
    env.DB.prepare("DELETE FROM measurements WHERE measured_at < ?").bind(cutoff),
  ]);

  console.log(JSON.stringify({
    event: "auto_cleanup_completed",
    size_before: sizeBefore,
    threshold_bytes: thresholdBytes,
    retention_hours: retentionHours,
    cutoff,
    deleted_commands: commands.meta.changes,
    deleted_measurements: measurements.meta.changes,
    size_after: measurements.meta.size_after,
  }));
}

function validNumber(value: unknown, min: number, max: number): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= min && value <= max;
}

function validateMeasurement(body: unknown): MeasurementInput | null {
  if (!body || typeof body !== "object") return null;
  const m = body as Record<string, unknown>;
  if (typeof m.device_id !== "string" || !/^[A-Za-z0-9._-]{1,64}$/.test(m.device_id)) return null;
  if (typeof m.measured_at !== "string" || Number.isNaN(Date.parse(m.measured_at))) return null;
  if (m.source !== "scheduled" && m.source !== "manual") return null;
  if (m.heart_rate != null && !validNumber(m.heart_rate, 20, 250)) return null;
  if (m.temperature != null && !validNumber(m.temperature, 20, 45)) return null;
  if (m.command_id != null && (typeof m.command_id !== "string" || m.command_id.length > 100)) return null;
  return m as MeasurementInput;
}

async function deviceApi(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);

  if (url.pathname === "/api/device/ws") {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return json({ error: "websocket upgrade required" }, 426);
    }
    const deviceId = url.searchParams.get("device_id") ?? "";
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(deviceId)) return json({ error: "invalid device_id" }, 400);
    const stub = env.DEVICE_HUB.get(env.DEVICE_HUB.idFromName(deviceId));
    return stub.fetch(request);
  }

  if (url.pathname === "/api/device/heartbeat" && request.method === "GET") {
    return json({ ok: true, service: "xtc-health-mcp", device_auth: true });
  }

  if (url.pathname === "/api/device/heartbeat" && request.method === "POST") {
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null;
    const deviceId = typeof body?.device_id === "string" ? body.device_id : "";
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(deviceId)) return json({ error: "invalid device_id" }, 400);
    await env.DB.prepare(
      `INSERT INTO devices(device_id,last_seen_at,app_version)
       VALUES(?,strftime('%Y-%m-%dT%H:%M:%fZ','now'),?)
       ON CONFLICT(device_id) DO UPDATE SET last_seen_at=excluded.last_seen_at, app_version=excluded.app_version`,
    ).bind(deviceId, typeof body?.app_version === "string" ? body.app_version.slice(0, 40) : null).run();
    return json({ ok: true, server_time: new Date().toISOString() });
  }

  if (url.pathname === "/api/device/measurements" && request.method === "POST") {
    const raw = await request.json().catch(() => null);
    const m = validateMeasurement(raw);
    if (!m) return json({ error: "invalid measurement" }, 400);

    const result = await env.DB.prepare(
      `INSERT INTO measurements
       (device_id,measured_at,source,heart_rate,temperature,heart_rate_confidence,command_id,raw_json)
       VALUES(?,?,?,?,?,?,?,?)`,
    ).bind(
      m.device_id, new Date(m.measured_at).toISOString(), m.source,
      m.heart_rate ?? null, m.temperature ?? null,
      m.heart_rate_confidence ?? null, m.command_id ?? null,
      JSON.stringify({
        device_id: m.device_id,
        measured_at: new Date(m.measured_at).toISOString(),
        source: m.source,
        heart_rate: m.heart_rate ?? null,
        temperature: m.temperature ?? null,
        heart_rate_confidence: m.heart_rate_confidence ?? null,
        command_id: m.command_id ?? null,
      }),
    ).run();
    const measurementId = Number(result.meta.last_row_id);

    if (m.command_id) {
      await env.DB.prepare(
        `UPDATE commands SET status='completed', completed_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'), result_measurement_id=?
         WHERE id=? AND device_id=?`,
      ).bind(measurementId, m.command_id, m.device_id).run();
    }
    await env.DB.prepare(
      `INSERT INTO devices(device_id,last_seen_at) VALUES(?,strftime('%Y-%m-%dT%H:%M:%fZ','now'))
       ON CONFLICT(device_id) DO UPDATE SET last_seen_at=excluded.last_seen_at`,
    ).bind(m.device_id).run();
    return json({ ok: true, id: measurementId }, 201);
  }

  return json({ error: "not found" }, 404);
}

function mcpResult(value: unknown, isError = false) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(value, null, 2) }],
    isError,
  };
}

function buildMcpServer(env: Env): McpServer {
  const server = new McpServer({ name: "XTC Health MCP", version: "0.1.0" });

  server.registerTool(
    "get_health_history",
    {
      title: "查看 XTC 健康历史",
      description: "查询指定小时范围内已经由手表上传的历史健康测量。",
      inputSchema: {
        hours: z.number().int().min(1).max(24 * 365).default(24),
        limit: z.number().int().min(1).max(500).default(100),
        device_id: z.string().min(1).max(64).optional(),
      },
      annotations: { readOnlyHint: true, openWorldHint: false },
    },
    async ({ hours, limit, device_id }) => {
      const deviceId = device_id ?? env.DEFAULT_DEVICE_ID;
      const cutoff = new Date(Date.now() - hours * 3600_000).toISOString();
      const rows = await env.DB.prepare(
        `SELECT id,device_id,measured_at,received_at,source,heart_rate,temperature,
                heart_rate_confidence,command_id
         FROM measurements WHERE device_id=? AND measured_at>=? ORDER BY measured_at DESC LIMIT ?`,
      ).bind(deviceId, cutoff, limit).all();
      return mcpResult({ device_id: deviceId, hours, count: rows.results.length, measurements: rows.results });
    },
  );

  server.registerTool(
    "collect_health_now",
    {
      title: "立即采集 XTC 健康数据",
      description: "向在线手表发送一次采集指令，并在本次调用中等待最多 10 秒；成功时直接返回健康数据，超时后可用 command_id 再次查询。",
      inputSchema: {
        device_id: z.string().min(1).max(64).optional(),
        command_id: z.string().uuid().optional(),
      },
      annotations: { readOnlyHint: false, idempotentHint: false, openWorldHint: false },
    },
    async ({ device_id, command_id }) => {
      const deviceId = device_id ?? env.DEFAULT_DEVICE_ID;
      if (command_id) {
        const row = await env.DB.prepare(
          `SELECT c.id,c.device_id,c.created_at,c.completed_at,c.status,
                  m.measured_at,m.heart_rate,m.temperature
           FROM commands c LEFT JOIN measurements m ON m.id=c.result_measurement_id
           WHERE c.id=? AND c.device_id=?`,
        ).bind(command_id, deviceId).first();
        return row ? mcpResult(row) : mcpResult({ error: "command not found" }, true);
      }

      const id = crypto.randomUUID();
      await env.DB.prepare("INSERT INTO commands(id,device_id,status) VALUES(?,?,'pending')").bind(id, deviceId).run();
      const stub = env.DEVICE_HUB.get(env.DEVICE_HUB.idFromName(deviceId));
      const sent = await stub.fetch("https://device-hub/send", {
        method: "POST",
        headers: jsonHeaders,
        body: JSON.stringify({ type: "collect-health-now", command_id: id, timeout_seconds: 10 }),
      });
      const info = await sent.json() as { connections?: number };
      if (!sent.ok || !info.connections) {
        return mcpResult({ status: "offline", command_id: id, message: "手表当前没有 WebSocket 连接；应用重连后请重新发起。" }, true);
      }
      await env.DB.prepare("UPDATE commands SET status='sent' WHERE id=?").bind(id).run();

      const deadline = Date.now() + 10_000;
      while (Date.now() < deadline) {
        const result = await env.DB.prepare(
          `SELECT c.id,c.device_id,c.created_at,c.completed_at,c.status,
                  m.measured_at,m.heart_rate,m.temperature
           FROM commands c LEFT JOIN measurements m ON m.id=c.result_measurement_id
           WHERE c.id=? AND c.device_id=?`,
        ).bind(id, deviceId).first<Record<string, unknown>>();
        if (result?.status === "completed") {
          return mcpResult({ ...result, waited_seconds: Math.ceil((10_000 - (deadline - Date.now())) / 1000) });
        }
        const remaining = deadline - Date.now();
        if (remaining > 0) await sleep(Math.min(1000, remaining));
      }

      await env.DB.prepare("UPDATE commands SET status='timeout' WHERE id=? AND status='sent'").bind(id).run();
      return mcpResult({
        status: "timeout",
        command_id: id,
        device_id: deviceId,
        timeout_seconds: 10,
        message: "10 秒内没有收到手表上传的数据；可用 command_id 再次查询。",
      }, true);
    },
  );

  server.registerTool(
    "delete_health_data",
    {
      title: "删除过期 XTC 健康数据",
      description: "删除指定小时数以前的数据。confirm=false 只预览数量；只有 confirm=true 才执行删除。",
      inputSchema: {
        older_than_hours: z.number().int().min(1).max(24 * 3650),
        confirm: z.boolean().default(false),
        device_id: z.string().min(1).max(64).optional(),
      },
      annotations: { destructiveHint: true, idempotentHint: true, openWorldHint: false },
    },
    async ({ older_than_hours, confirm, device_id }) => {
      const deviceId = device_id ?? env.DEFAULT_DEVICE_ID;
      const cutoff = new Date(Date.now() - older_than_hours * 3600_000).toISOString();
      const countRow = await env.DB.prepare(
        "SELECT COUNT(*) AS count FROM measurements WHERE device_id=? AND measured_at<?",
      ).bind(deviceId, cutoff).first<{ count: number }>();
      const count = Number(countRow?.count ?? 0);
      if (!confirm) return mcpResult({ preview: true, would_delete: count, cutoff, device_id: deviceId });
      const result = await env.DB.prepare(
        "DELETE FROM measurements WHERE device_id=? AND measured_at<?",
      ).bind(deviceId, cutoff).run();
      return mcpResult({ deleted: result.meta.changes, cutoff, device_id: deviceId });
    },
  );

  return server;
}

const mcpHandler = {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const handler = createMcpHandler(() => buildMcpServer(env), { legacy: "stateless" });
    return handler.fetch(request);
  },
};

const publicHandler = {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/device/")) return deviceApi(request, env);
    if (url.pathname === "/health") return json({ ok: true, service: "xtc-health-mcp" });

    if (url.pathname === "/") {
      return new Response("XTC Health MCP is running. MCP endpoint: /mcp (no OAuth)", { headers: { "content-type": "text/plain; charset=utf-8" } });
    }
    return json({ error: "not found" }, 404);
  },
};

export class DeviceHub extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/api/device/ws") {
      const pair = new WebSocketPair();
      this.ctx.acceptWebSocket(pair[1]);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    if (url.pathname === "/send" && request.method === "POST") {
      const payload = await request.text();
      const sockets = this.ctx.getWebSockets();
      for (const socket of sockets) {
        try { socket.send(payload); } catch { /* stale sockets are ignored */ }
      }
      return json({ connections: sockets.length });
    }
    return json({ error: "not found" }, 404);
  }
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    if (new URL(request.url).pathname === "/mcp") return mcpHandler.fetch(request, env, ctx);
    return publicHandler.fetch(request, env);
  },
  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(autoCleanup(env));
  },
} satisfies ExportedHandler<Env>;
