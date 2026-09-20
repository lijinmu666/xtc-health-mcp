# 小天才 Z8A 健康数据 MCP：项目记录

> 整理于 2026-09-20。本文记录项目约定、实际观察和排障经过；“用户实测”和“代码已确认”分别标出。健康数值仅是过去调试日志的示例，不代表医疗结论。项目维护：李瑾沐（`lijinmu666`）；代码协助：OpenAI Codex。

## 1. 目标与部署结构

以小天才电话手表 Z8A 为数据来源，定时测量心率和体温并直接上传；在 MCP 客户端查询历史、远程触发一次测量、按时间删除旧数据。当前仓库是服务端，部署到 Cloudflare Worker。手表端另有 Android 应用 **Healthxtc Server**，包名 `com.lijinmu.healthxtc`。

`手表传感器 → Healthxtc Server → Worker 设备 HTTP 接口 → D1 → MCP 工具`。远程采集使用 `MCP 工具 → Durable Object → WebSocket → 手表采集 → HTTP 上传 → D1`。Worker 使用 D1 记录测量和指令，Durable Object 维持每台设备的 WebSocket 连接。设备默认 ID 为 `xtc-z8a`。

当初也考虑过 Magisk `service.sh` 直接采集、priv-app/system UID、EdXposed 阻止系统回收，最后形成 APK 采集和 Cloudflare 服务端的方案。用户后来实测通过 Magisk 模块让 APK 取得 UID 1000；这属于其设备的安装状态，不是源码工程的普通安装条件，也不能单凭 UID 1000 推断后台任务一定会运行。

## 2. 手表硬件与传感器实测

用户设备为已 root 的 Z8A，可安装 APK/Magisk 模块，有 v7a 系统环境，用户称其使用 Wear 4100。最初计划逆向“健康监测”和“自检” APK 了解 XTC 专有接口；随后测试表明普通 Android `SensorManager` 能找到厂商传感器，测试 APK 的 `BODY_SENSORS` 状态为已授权。

| 厂商 type | 测试日志名称 | 实测结果 | 最终产品处理 |
| --- | --- | --- | --- |
| 88620080 | Heart_rate Sensor | 成功注册并收到事件，例如 `values[0]=75`，其它字段也有数值 | 使用心率；不要把未证实的其它数组字段误称为医学指标 |
| 88620081 | Temp_other Sensor | 成功注册并收到事件，例如 `values[0]=36.5` | 使用体温 |
| 88620082 | Spo2 Sensor | 成功注册并收到事件，早期 `values[0]=0`，后来出现 95–100 | 根据用户要求从产品数据模型移除 |
| 88620083 | Wear_status Sensor | 成功注册，收到 1、2、3 等状态码 | 根据用户要求移除，状态码含义未确认 |
| 88620084 | Ppg Sensor | 可枚举、可注册，测试 60 秒未收到事件 | 不作为产品测量结果 |

以上是传感器是否能获取的观察，不等于验证传感器精度。最后保留心率和体温。服务端旧 D1 表可能还有先前血氧、佩戴状态的列，当前代码不写入或返回这些字段，以保留数据库兼容性。

## 3. 当前 Worker 的接口与数据约定

当前 `src/index.ts` 和本仓库 README 已确认：

- `GET /health` 返回服务存活状态；设备测试连接应检查这个路径。
- `POST /api/device/measurements` 上传一条测量；`GET/POST /api/device/heartbeat` 为心跳接口。
- `/api/device/ws?device_id=...` 接收手表 WebSocket；Durable Object 自动回复应用层文本心跳 `ping → pong`。
- `/mcp` 提供 `get_health_history`、`collect_health_now`、`delete_health_data` 三个工具。
- 上传记录要求合法 `device_id`、可解析的 `measured_at`、`source=manual|scheduled`；心率允许 20–250，体温允许 20–45，可带 `heart_rate_confidence` 和 `command_id`。
- 查询工具按小时范围、数量和设备 ID 读 D1；删除工具默认先预览，只有 `confirm=true` 才删。
- 远程采集向在线设备发送带 `command_id` 的指令，在调用中最多等待 10 秒。超时可以携带这个 ID 查询后续结果；设备离线则返回离线状态。
- 自动清理每小时执行一次：当 D1 报告大小达到 1 MiB，删除超过 24 小时的测量及关联过期指令。该规则不会保证数据库总大小始终低于 1 MiB，也不删除最近 24 小时的记录。

**当前安全状态：** 根据仓库代码，MCP 无 OAuth，设备 HTTP/WebSocket 接口也无 API Key。此前用 OAuth + KV 部署时出现命名空间绑定错误，后来改为无 OAuth；设备密钥也在反复出现 401 后移除。私有 GitHub 仓库仅限制源码访问，**不会**给已部署 Worker 接口自动加访问控制。不要把手表测量、真实密钥或完整生产地址写进公开日志或截图；若未来要面向更多设备或外部用户，应重新设计接口认证。

## 4. 手表 APK 的确定需求与已交付行为

用户确定的应用名称为 `Healthxtc Server`，包名 `com.lijinmu.healthxtc`。最后保存的源码包据先前交付记录显示 `versionName 1.2.0`、`versionCode 3`；该 ZIP 当前无法取回，因此这些是**交付记录**而不是本次源码复核结果。

应用有主界面、测试工具和设置页。设置包括服务器根地址、自动上传间隔（分钟）、是否启用自动上传、是否允许结果通知，以及后来加入的 WakeLock 开关；测试工具包括连接测试、只采集、采集并上传、自定义健康值上传与日志。用户要求保留现有图标，支持替换 PNG。最近结果和最近一次上传成功时间也是后续需求。

**自动上传约定：** 定时启动测量；成功获取有效数据就立刻上传。测量失败或 20 秒内完全拿不到数据，就跳过本次，等待下次。没有本地上传队列、待上传记录或自动重试。自动任务结束后按通知设置给系统成功/失败通知。

**远程采集约定：** APK 维持 WebSocket，收到 `collect-health-now`（兼容其它命名形式）后执行采集并上报，回传 `command_id` 供服务端配对；结果通知由设置控制。Worker 最多同步等待 10 秒，手表测量允许更久，因此工具返回超时不必然等于数据永远不会上传。

服务器地址在 APK 设置中填写 Worker **根地址**，不要附加 `/health` 或 `/mcp`。当前协议没有设备 API Key；旧版页面的 Key 如果仍存在不能作为服务端认证依据。

## 5. 关键故障与判断过程

1. **Cloudflare 部署与 OAuth。** 最初 `wrangler deploy` 因 KV namespace ID 错误出现 10042/10041；后来绑定正确时部署成功。OAuth 页面一度可以打开，但配置反复造成额外问题，最后采用仓库中当前无 OAuth 版本。D1 和 Durable Object 分别负责持久数据和实时设备连接。
2. **手表无法连接 Worker。** 手表 `curl` 对 `workers.dev` 曾显示网络不可达、超时；一次手动固定 IP 测试又出现 TLS 错误。手机和手表在“同一网络”不保证 DNS、IPv4/IPv6、系统证书、代理设置一致。用户使用法国代理后连接测试返回 HTTP 200。连接问题与 HTTP 401（认证）以及 400（数据校验）是不同层次，不能混为一谈。
3. **上传未成功。** 旧 APK 显示待上传数量，故后来明确取消队列，采集后立即发送。另一次自定义数据上传获得 401，促使用户移除设备 API Key；400 `invalid measurement` 则对应 Worker 对字段、类型和范围的校验，应看具体请求体。
4. **iOS 快捷指令尝试。** iPhone 浏览器可访问服务端，但快捷指令在 VPN 下对多个 URL 都报“网络连接已中断”，用户决定继续以手表 APK 为主；不能根据快捷指令失败断言 Worker 故障。
5. **后台进程与“假死”。** 用户用 Magisk 使进程开机即运行、长期不被杀，但熄屏后定时上传和远程 WebSocket 指令都停住；手动打开一次 Activity 会恢复。先前为 APK 源码设计/交付了与自动上传独立的 60 秒闹钟看门狗、`ping/pong` 心跳、超过 90 秒无响应强制重连、旧连接回调的代次保护。ZIP 曾通过压缩包完整性检查，但本次没有 APK 编译或真机长时间回归证据，**不能宣称此问题已在手表上最终解决**。

## 6. 当前已确认状态与后续验证

- 本次已核对：GitHub 仓库 `lijinmu666/xtc-health-mcp` 为 **private**；默认分支 `main` 保留 Cloudflare Worker、迁移脚本和文档，并新增 `android/HealthxtcServer/` 源码。
- 用户此前反馈：AI 端能连接 MCP 并调用工具；手表上经代理可得到连接 HTTP 200；曾正常采集心率；保活后曾出现后台“进程还在但任务不执行”。这些是用户的当时测试反馈，不能替代现版本完整联调。
- 建议联调顺序：先用 APK 测试页确认 `/health`，再采集并上传一条合法心率/体温数据，之后用 `get_health_history` 查看；最后熄屏观察跨数个自动上传周期，并调用 `collect_health_now`，检查 APK 日志中是否出现闹钟唤醒、`ping/pong`、断线重连和上传结果。
- 若再次出现假死：分别记录自动闹钟是否触发、进程是否被系统冻结、WebSocket 是否收到 `pong`、网络是否通、是否发出 HTTP 请求；把设备时间、通知与 Worker 日志对齐。单靠“进程存在”无法证明计时器及网络回调正在运行。

## 7. 源码保存情况

已经从资料库恢复 `HealthxtcServer-source-v1.2.0.zip`，核对 ZIP 完整性、包名 `com.lijinmu.healthxtc`、版本 `1.2.0 (3)`、图标及凭据后，将其中 30 个源码文件原样加入本私有仓库的 `android/HealthxtcServer/` 目录（提交 `6c6cf6fb3010000dbc2578eb476dea57a0d8dfeb`）。未在本次环境里编译 APK；仍需在 Android Studio 运行编译和手表真机回归。
