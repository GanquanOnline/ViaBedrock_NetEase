# ViaBedrock_NetEase

Public fork of [EaseCation/ViaBedrock](https://github.com/EaseCation/ViaBedrock) (`main`, based on [`fcf85f2`](https://github.com/EaseCation/ViaBedrock/commit/fcf85f26309eaed7fa3a070c5916a7d9492e2a91)).
EaseCation/ViaBedrock itself is a fork of [RaphiMC/ViaBedrock](https://github.com/RaphiMC/ViaBedrock).

This repository adds **NetEase / China Edition protocol 860** client emulation on top of ViaBedrock. It translates Minecraft Java Edition packets to Bedrock Edition so a Java client can join NetEase Bedrock servers.

## Fork lineage

| This repository | Pulled from | Branch | Upstream commit |
|---|---|---|---|
| **ViaBedrock_NetEase** | [EaseCation/ViaBedrock](https://github.com/EaseCation/ViaBedrock) | `main` | `fcf85f26309eaed7fa3a070c5916a7d9492e2a91` |

## Related repositories

```
Java Fabric 1.21.11 client
  ViaBedrockUtility  --custom payload-->  ViaProxy_NetEase
  (optional ModUI / camera / particle mods)     |
                                                v
                                         ViaBedrock_NetEase
                                                |
                                                v
                                         ViaVersion shims
                                                |
                                                v
                                      NetEase Bedrock (protocol 860)
```

| Repository | Upstream | What it does in this stack |
|---|---|---|
| ViaVersion | [ViaVersion/ViaVersion](https://github.com/ViaVersion/ViaVersion) `master` @ `e8bbb5d` | Protocol base library plus send-interceptor / custom-registry shims that ViaBedrock_NetEase expects |
| **ViaBedrock_NetEase (this repo)** | EaseCation/ViaBedrock `main` @ `fcf85f2` | Java <-> Bedrock translation, NetEase login, protocol 860 layouts, join replay, ModUI PY_RPC bridge |
| ViaProxy_NetEase | [ViaVersion/ViaProxy](https://github.com/ViaVersion/ViaProxy) `main` @ `846646b` | Standalone proxy. Bundles ViaBedrock_NetEase and uses RakNet 8 when NetEase emulation is enabled |
| ViaBedrockUtility | [EaseCation/ViaBedrockUtility](https://github.com/EaseCation/ViaBedrockUtility) `master` @ `ebfbe10` | Fabric client mod. Renders custom entities/skins/animations from ViaBedrock_NetEase payloads |

Payload channels must stay in sync across repos:

- Custom entities / skins / animations: `viabedrockutility:data` and `viabedrockutility:confirm` (ViaBedrock_NetEase <-> ViaBedrockUtility)
- ModUI / PY_RPC: `moduiclient:confirm` and `moduiclient:data` (ViaBedrock_NetEase `experimental/modinterface` and `experimental/pyrpc` <-> ModUIClient)

## NetEase emulation

Enable it in `viabedrock.yml` (keep secrets empty in public configs):

```yaml
netease:
  enabled: true
  protocol-version: 860
  game-version: "1.21.124_NetEase"
  raknet-protocol-version: 8
```

Local changes on top of EaseCation/ViaBedrock:

- NetEase login JWT claims and `GameVersion`
- Protocol 860 packet layouts (START_GAME, chat, commands, inventory, block positions, animation trailers, ...)
- Queue early world packets until the Java client reaches PLAY, then replay them
- `NETWORK_STACK_LATENCY` echo plus an active heartbeat after spawn
- Server-authoritative inventory clicks encoded as `ITEM_STACK_REQUEST`
- ModUI PY_RPC bridging on `moduiclient:*`
- After `SET_LOCAL_PLAYER_AS_INITIALIZED`, synthesize NukkitMaster engine-call `ClientLoadAddonsFinishedFromGac` (msgId `98247598`) so HUD / player-info is not gated forever. The one-shot is only marked sent after the payload leaves; inactive channels retry instead of dropping the gate.

Build this module with `./gradlew publishToMavenLocal` (Java 21) after publishing the matching ViaVersion fork to mavenLocal. Then build ViaProxy_NetEase.

CI: pushes and PRs run `.github/workflows/build-viaproxy.yml`. That job publishes this checkout to mavenLocal, clones `ViaProxy_NetEase` from the same GitHub org, rebuilds ViaProxy with this ViaBedrock packed in, uploads the jars, and on `main` refreshes the `viaproxy-latest` GitHub Release. If `ViaProxy_NetEase` is private, set repository secret `ORG_READ_TOKEN` (`contents:read`).

## License

GPL-3.0-or-later, same as upstream ViaBedrock. See [LICENSE](LICENSE).

---

# ViaBedrock
ViaVersion addon to add support for Minecraft: Bedrock Edition servers.

ViaBedrock aims to be as compatible and accurate as possible with the Minecraft: Bedrock Edition protocol.

## Usage
**ViaBedrock is in very early stages of development and NOT intended for regular use yet.**

**Do not report any bugs yet. There are still a lot of things which are not implemented yet.**

If you want to talk about ViaBedrock or learn more about it you can join my [Discord](https://raphimc.net/discord).

### Standalone proxy (Serverside / Clientside)
To use ViaBedrock independently of any server or client software, you can download the latest [ViaProxy dev build](https://build.lenni0451.net/job/ViaProxy/) (Click on the **ViaProxy-x.x.x.jar** file).

### Fabric mod (Clientside)
To use ViaBedrock as a Fabric mod, you can download the latest [ViaFabricPlus dev build](https://ci.viaversion.com/view/Platforms/job/ViaFabricPlus/).

## Features
Here is an overview of the current and planned features in ViaBedrock.

- [x] Pinging
- [x] Joining
- [x] Xbox Live Auth
- [x] Chat / Commands
- [x] Chunks
- [x] Chunk caching
- [x] Block updates
- [x] Block entities
- [x] Biomes
- [x] Player spawning
- [x] Entity spawning
- [x] Entity interactions
- [x] Entity metadata
- [x] Entity attributes
- [x] Entity mounting
- [x] Player abilities
- [x] Movement
- [x] Client-Authoritative Inventory
- [x] Server-Authoritative Inventory
- [x] Item data
- [x] Block breaking
- [x] Block placing
- [x] Item use
- [x] Respawning and dimension switching
- [x] Form GUIs
- [x] Scoreboard
- [x] Titles
- [x] Bossbar
- [x] Player list
- [x] Command suggestions
- [x] Sounds (No mob sounds yet)
- [x] Particles
- [x] Basic resource pack conversion (Contributions are welcome)

### Experimental
Some features are experimental, which means they are almost certainly not fully stable/tested and may cause unexpected issues. To enable those features set `enable-experimental-features` to `true` in the config file.

* Block placing
* Item use (NetEase 860: food/potion/milk/ominous, bow/crossbow/trident/spear/spyglass, shield-as-sneak, projectiles, empty/filled buckets, boats, lily pads, frog spawn, custom consumable finish)
* Entity metadata
* Some item data

## Optional clientside mods
Below is a list of mods which can be used in combination with ViaBedrock to enhance certain aspects, which would not be possible without client modification:
- [ViaBedrockUtility](https://github.com/Oryxel/ViaBedrockUtility): Adds support for some custom player skins and improves custom entity rendering
- [BedrockSkinUtility](https://github.com/Camotoy/BedrockSkinUtility): Adds support for some custom player skins

## Useful resources
ViaBedrock would not have been possible without the following projects:
- [ViaVersion](https://github.com/ViaVersion/ViaVersion): Provides the base for translating packets
- [CloudburstMC Protocol](https://github.com/CloudburstMC/Protocol): Documentation of the Bedrock Edition protocol
- [PMMP BedrockProtocol](https://github.com/pmmp/BedrockProtocol): Documentation of the Bedrock Edition protocol
- [Mojang Protocol Docs](https://github.com/Mojang/bedrock-protocol-docs): Documentation of the Bedrock Edition protocol
- [CloudburstMC Protocol Docs](https://github.com/CloudburstMC/protocol-docs): Documentation of the Bedrock Edition protocol
- [wiki.vg](https://minecraft.wiki/w/Minecraft_Wiki:Projects/wiki.vg_merge/Bedrock_Protocol): Documentation of the Bedrock Edition protocol
- [mcrputil](https://github.com/valaphee/mcrputil): Documentation of Bedrock Edition resource pack encryption
- [wiki.bedrock.dev](https://wiki.bedrock.dev): Documentation of various technical aspects of Bedrock Edition

Additionally ViaBedrock uses assets and data dumps from other projects: See the `Data Asset Sources.md` file for more information.
