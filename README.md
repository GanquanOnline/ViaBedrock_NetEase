# ViaBedrock_NetEase

[![Build](https://github.com/GanquanOnline/ViaBedrock_NetEase/actions/workflows/build.yml/badge.svg)](https://github.com/GanquanOnline/ViaBedrock_NetEase/actions/workflows/build.yml)
[![Build ViaProxy](https://github.com/GanquanOnline/ViaBedrock_NetEase/actions/workflows/build-viaproxy.yml/badge.svg)](https://github.com/GanquanOnline/ViaBedrock_NetEase/actions/workflows/build-viaproxy.yml)

ViaBedrock 的网易（中国版）分支：把 Java 版协议翻译成基岩版协议，模拟网易客户端的登录链与非标准包布局，让 Java 客户端能进入网易基岩服；同时保留上游的国际基岩多版本档案。

> **English.** A ViaBedrock fork that emulates a NetEase (China Edition) Bedrock client: NetEase login fields, non-standard packet layouts and RakNet 8 transport, alongside the regular international Bedrock translation. Licensed GPL-3.0-or-later.

**状态：仍在开发中。** 网易客户端的线上行为随版本变化，不建议直接用于生产环境；遇到问题请提 issue 或 PR。

## 上游链条

`RaphiMC/ViaBedrock` → `EaseCation/ViaBedrock` → **本仓库**

本仓库以 [EaseCation/ViaBedrock](https://github.com/EaseCation/ViaBedrock) 的 `main` 为基线，最近一次同步到 `908ae571`（2026-11-05），其上叠加网易仿真、多版本档案与协议修正。

## 支持的版本

基岩侧有两套互相独立的版本档案，都在 `viabedrock.yml` 里选。

### 国际基岩（`bedrock.protocol-version`）

| 协议号 | 游戏版本 |
|---|---|
| 2225 | 1.26.60（最新） |
| 2193 / 2192 | 1.26.50 / 1.26.50.27 |
| 2168 | 1.26.40 |
| 1001 | 1.26.30 |
| 975 / 974 | 1.26.20 / 1.26.20 (974) |
| 944 | 1.26.10 |
| 924 | 1.26.0 |
| 898 / 897 | 1.21.130 / 1.21.130.28 |
| 860 | 1.21.124（后向窗口最低） |

- 不配置时取 `975`（1.26.20）
- `0` 表示 auto，解析为最新档案（当前 `2225`）
- 填档案之外的正整数也接受，但布局按偏差区间推导（inferred），服务端可能拒绝握手

### 网易（`netease.protocol-version`）

| 协议号 | 版本串 | 说明 |
|---|---|---|
| 898 | `1.21.130_NetEase` | 默认，对应 Nukkit-MOT modpc 3.10 |
| 860 | `1.21.124_NetEase` | 早期线上版本 |

网易仿真固定用 RakNet 8；关掉 `netease.enabled` 则回到国际基岩链路（RakNet 11）。

## 配置

```yaml
bedrock:
  protocol-version: 975        # 国际基岩目标版本；0 = 最新档案

netease:
  enabled: true                # 模拟网易客户端
  protocol-version: 898        # 898 = 1.21.130_NetEase，860 = 1.21.124_NetEase
  raknet-protocol-version: 8   # 网易客户端固定 RakNet 8
```

`netease.game-version` 只用于校验：与所选档案不一致时会告警，实际仍用档案里的版本串。公开配置里不要写账号凭据。

## 构建

需要 JDK 21。

1. 先发布配套的 ViaVersion 分支（`GanquanOnline/ViaVersion`）到 mavenLocal
2. 再发布本模块：

```bash
./gradlew publishToMavenLocal
```

ViaProxy_NetEase 用 `includeInJar` 打包本模块，所以改完这里必须重编 ViaProxy 才会生效。

## 与上游的差异

- 网易登录字段与 `GameVersion`（`netease:` 配置段）
- 网易包布局：`START_GAME`、聊天、命令、背包、方块位置、动画尾部等
- Java 客户端进入 PLAY 之前的早期世界包先排队，到位后重放
- 出生后回 `NETWORK_STACK_LATENCY`，并起主动心跳
- 服务端权威背包模式下的点击编码为 `ITEM_STACK_REQUEST`
- ModUI / PyRpc 桥接（`experimental/modinterface`、`experimental/pyrpc`）
- `SET_LOCAL_PLAYER_AS_INITIALIZED` 之后补发 NukkitMaster 的引擎调用 `ClientLoadAddonsFinishedFromGac`（PyRpc msgId `98247598`），否则 HUD 与玩家信息会一直被卡住
- 多版本档案（`BedrockProtocolProfile`、`NetEaseProtocolProfile`）与偏差区间（`BedrockDeviation`）

## 客户端联动

| 通道 | 对端 | 用途 |
|---|---|---|
| `viabedrockutility:data` / `viabedrockutility:confirm` | [ViaBedrockUtility](https://github.com/GanquanOnline/ViaBedrockUtility) | 自定义实体、皮肤、动画 |
| `moduiclient:data` / `moduiclient:confirm` | ModUIClient | 基岩 FormUI / HUD |
| `netease_bridge:entity_mapping` | 网易 bridge 客户端 | 实体 ID 映射；未注册该通道的客户端收不到 |

## CI

- `.github/workflows/build.yml`：编译本模块
- `.github/workflows/build-viaproxy.yml`：push 与 PR 时把本仓库发布到 mavenLocal，检出 `ViaProxy_NetEase` 重编整包并上传产物；push 到 `main` 时按版本号新建 GitHub Release（不再复用固定 tag）

`ViaProxy_NetEase` 若是私有库，需要仓库密钥 `ORG_READ_TOKEN`（`contents: read`）。

## 许可与署名

本项目以 **GPL-3.0-or-later** 分发。

- `LICENSE` 是 GPLv3 原文，逐字保留：许可证正文本身禁止修改，也不能被替换成别的许可证。
- 上游版权：ViaBedrock 由 RK_01/RaphiMC 及贡献者持有（GPL-3.0）；EaseCation/ViaBedrock 由 EaseCation 及贡献者维护。
- 本仓库新增与修改部分的版权归 GanquanOnline 贡献者所有，同样按 GPL-3.0-or-later 授权。
- 按 GPL-3.0 §5(a) 声明：本仓库自 2026 年起修改了上游代码，修改内容见上文“与上游的差异”。分发二进制时必须同时提供对应源码。

分发本项目的衍生作品请保持 GPL-3.0-or-later，不要改成 MIT、Apache-2.0 等许可证。
