# ViaBedrock_NetEase patch log

Protocol truth: `decompiled/nukkit-mot` encode/decode, then `decompiled/nukkitmaster` for PyRpc / ModUI. Do not treat international Bedrock wiki or Geyser palettes as MOT 860.

## 2026-08-24 — Consume leftover CAMERA_INSTRUCTION after fade

- **Goal:** MOT 860 `CameraInstructionPacket.decode()` still reads target (712), FOV (827) and spline/attach (859) after fade. ViaBedrock stopped at fade, so leftover bytes could abort the join batch.
- **Change:** `CameraInterface.skipLeftoverCameraInstruction` consumes those optional fields. Java `becamera:data` still only forwards set/clear/fade; spline roll is dropped because BECamera `CameraPathManager` ignores it.
- **Refs:** `decompiled/nukkit-mot/cn/nukkit/network/protocol/CameraInstructionPacket.java`.
- **Risk:** Spline/FOV/target still have no JE payload. This only prevents leftover-byte disconnects.

## 2026-08-24 — Retry ClientLoadAddonsFinishedFromGac until sent

- **Goal:** `scheduleClientLoadAddonsFinished` stored a one-shot flag even when the Netty channel was inactive, so later PLAY retries never emitted the Master HUD gate.
- **Change:** Track `sent` / `scheduled` / `attempts` on the connection. Reschedule up to 8 times until the payload leaves. Detect MOT `ModEventS2C` as MessagePack bin8 **or** str (fixstr/str8).
- **Refs:** `decompiled/nukkit-mot/cn/nukkit/network/protocol/netease/pyrpc/io/PyRpcWriter.java` (`writeBinaryString` → `0xC4`); `decompiled/nukkitmaster/.../ClientEventListener.java`.
- **Risk:** If the backend never becomes active, Via logs a warning after 8 attempts instead of silently skipping HUD.

## 2026-08-24 — Pin NetEase protocol tuple

- **Goal:** `netease.enabled=true` still accepted international protocol / GameVersion / RakNet values and could send Java 1.21.0 + RakNet 11 to MOT 860.
- **Change:** When NetEase emulation is enabled, pin protocol 860, GameVersion `1.21.124_NetEase` and RakNet 8. Warn and ignore incompatible YAML.
- **Refs:** `decompiled/nukkit-mot/cn/nukkit/GameVersion.java` (`V1_21_124_NETEASE`), `cn/nukkit/network/session/RakNetPlayerSession.java` (RakNet 8).
- **Risk:** International Bedrock sessions must keep `netease.enabled: false`. Runtime configs that already set 860/8 are unchanged.

## 2026-08-24 — CAMERA_PRESETS experimental override

- **Goal:** Runtime log `Packet type CAMERA_PRESETS already registered` aborted experimental camera translation. `UnhandledPackets` cancels the packet so leftover bytes cannot kick Java when experimental features are off; `CameraInterface.register` then used `registerClientbound`.
- **Change:** Use `replaceClientbound` so experimental camera can decode MOT presets into `becamera:data` without a second registration.
- **Refs:** `UnhandledPackets.java` CAMERA_PRESETS cancel; `CameraInterface.java`; runtime `artifacts/runtime/logs/latest.log`.
- **Risk:** Experimental camera still requires `becamera:confirm` from VBU. Without VBU the packet is consumed and not forwarded as Java.

## 2026-08-24 — ClientLoadAddonsFinishedFromGac

- **Goal:** Java clients never emit NukkitMaster's engine-call gate, so HUD / player-info stayed empty after join.
- **Change:** After PLAY_STATUS PlayerSpawn sends `SET_LOCAL_PLAYER_AS_INITIALIZED`, schedule `ClientLoadAddonsFinishedFromGac` (msgId `98247598`) 250ms later.
- **Refs:**
  - `decompiled/nukkitmaster/com/neteasemc/nukkitmaster/eventListener/ClientEventListener.java` (`listenForClientEngineCall("ClientLoadAddonsFinishedFromGac")`)
  - `decompiled/nukkitmaster/com/neteasemc/nukkitmaster/pyrpc/PyRpcMessageListener.java` (C2S msgId `98247598`, engine callback vs ModEvent)
  - `decompiled/nukkit-mot/cn/nukkit/network/process/processor/v282/SetLocalPlayerAsInitializedProcessor_v282.java` (`doFirstSpawn` → `PlayerJoinEvent`)
  - `decompiled/nukkit-mot/cn/nukkit/network/protocol/netease/PyRpcPacket.java`
- **Risk:** 250ms delay assumes MOT finishes `PlayerJoinEvent` / `PlayerInfo` before the engine call. If Master still logs a missing PlayerInfo, raise the delay rather than sending on the same tick.

## 2026-08-24 — Java USE_ITEM air-click → MOT CLICK_BLOCK

- **Goal:** NetEase MOT only runs `Item.onActivate` from CLICK_BLOCK. Java empty/filled buckets, glass bottles, boats, lily pads and frog spawn send USE_ITEM (air click) and previously did nothing.
- **Change:** `ItemUseAirClickTarget` raytraces fluids / placeable surfaces; `ExperimentalFeatures` converts those air clicks. Same-tick duplicate USE_ITEM_ON is dropped. Kelp / custom consumables that MOT cannot auto-complete send a second CLICK_AIR.
- **Risk:** Requires `enable-experimental-features`. Food/potion/bow/shield/ride AABB now depend on GanAC `JavaClientCompatModule` (NukkitMOTJE is retired). Offhand promotion can swap MOT hands without rewriting the Java inventory (`tryHandleSwapHands(user, false)`).

## 2026-08-24 — MOT 860 sequential palette overlay + leftover IDs

- **Goal:** Hashed `network_id` already matches MOT 860 (FNV-1a of LE `{name,states}`). Sequential `runtimeId` does not: ViaBedrock used hashed-name order, MOT stores sequential ids in `runtime_block_states_netease_860.dat`. `minecraft:micro_block` exists only in the MOT dump. JWT `GameVersion` also defaulted to international `1.21.124`.
- **Change:**
  - Bundle `data/bedrock/netease_860_block_runtime_ids.json` (15829 unique hashed → sequential pairs after dropping 410 MOT overload rows that share the same hash and runtimeId; extra `micro_block`).
  - Sequential NetEase sessions resolve runtime ids from that overlay; hashed sessions stay on `network_id`.
  - Default `netease.game-version` to MOT enum `1.21.124_NetEase`.
  - Register/cancel leftover MOT IDs 305 / 340 so unknown packets cannot abort a RakNet batch.
- **Refs:**
  - `decompiled/nukkit-mot/cn/nukkit/level/BlockPalette.java` (`runtimeId` / hashed FNV-1a)
  - `decompiled/nukkit-mot/cn/nukkit/utils/Hash.java`
  - `decompiled/nukkit-mot/cn/nukkit/GameVersion.java` (`V1_21_124_NETEASE`)
  - `decompiled/nukkit-mot/cn/nukkit/network/protocol/ProtocolInfo.java` (305 / 340)
- **Risk:** Overlay is vanilla MOT 860 only. Custom blocks still come from START_GAME `blockProperties` and are assigned ids after the MOT sequential max. Live `ITEM_REGISTRY` still overrides static item ids.

## 2026-08-25 — Experimental metadata, riding, inventory, items, and block interaction

- **Goal:** README experimental gaps (entity metadata/mounting, CAI/SAI, item data, block break/place, item use) still mismatched MOT 860. SAI repeated crafts omitted `CraftRecipeAuto`, offhand F-swap treated request emission as success, item amounts 128–255 looked empty, placement ACKs ignored batched subchunk updates, and entity variants used Bedrock ordinals.
- **Change:**
  - Entity metadata uses semantic Java registry ids; unknown Bedrock properties stay raw. Copper flower is Java SADDLE poppy. Tropical fish is packed from VARIANT/MARK_VARIANT/COLOR_INDEX/COLOR_2_INDEX. Sniffer MOT flags 110–112 map to generated DEPRECATED_1/2/3.
  - Riding emits Java SET_PASSENGERS with controller first; boat paddle release zeros rowingTime.
  - SAI rollback is generation-gated. Offhand promotion stays PENDING until the matching ItemStackResponse OK; rejected ISR resyncs Java. Promotion OK flushes deferred ATTACK/INTERACT but does not restore until the promoted use ends.
  - Repeated SAI crafts emit MOT 860 `CraftRecipeAuto` DEFAULT descriptors from the matched grid. Extra-output recipes stay one craft. CAI QUICK_MOVE may aggregate counts.
  - Item shadows round-trip identifier/meta/blockRuntimeId/NBT/canPlace/canBreak; unsigned amounts 128–255 survive. Named HolderSet overlay only applies when the tag key is a concrete Bedrock block id.
  - Placement ACK target follows clicked-block replaceability. `powder_snow` is not generic replaceable; snow layers fail closed at height 7/missing; double plants only grass/fern. START break ACK is immediate except instant-break; NetEase completion is PredictDestroyBlock only.
- **Refs:**
  - `decompiled/nukkit-mot/cn/nukkit/utils/BinaryStream.java` (CraftRecipeAuto DEFAULT descriptors)
  - `decompiled/nukkit-mot/cn/nukkit/inventory/request/CraftRecipeAutoProcessor.java`
  - `decompiled/nukkit-mot/cn/nukkit/entity/Entity.java` (sniffer flags 110–112)
- **Risk:** README boxes stay unchecked. Full `gradlew test` still includes optional JFR/GC soak tests gated by env vars. Extra-output auto-craft with `times != 1` is rejected by MOT.

## 2026-08-27 — Fix creative click item disappearance (#1-1)

- **Goal:** Clicking an item in the Java creative inventory made it vanish. JE creative tabs are vanilla, not MOT `CREATIVE_CONTENT`; clicks go through `SET_CREATIVE_MODE_SLOT` → `CreativeSlotSemantics.plan` → `CreativeContentCache.findNetIdForJavaItem()`.
- **Root cause:** Mapped creative entries carry a private `viabedrock:bedrock_item` shadow, while JE clicks do not. `sameComponents()` compared whole `StructuredDataContainer`s, but that type has no `equals()`, so matches almost never succeeded. Identifier-less lookups returned `Plan.unsupported()`, and the handler force-resynced JE inventory/cursor from the Bedrock mirror, wiping the optimistic click.
- **Change:**
  - Prefer `ItemRewriter.bedrockItem(javaItem)` and exact/identity netId lookup before the JE identifier scan.
  - Replace container `equals` with `sameEffectiveComponents`, which ignores only the private shadow and still compares other custom data / components.
  - On `Plan.unsupported()` / encode unsupported, leave JE's optimistic creative prediction alone instead of clearing the cursor.
- **Refs:** `CreativeContentCache`, `ClientAuthInventoryModule.registerCreativeModeSlotHandler`, `TODO.md` #1-1.
- **Risk:** Items that still have no Bedrock creative netId remain unsupported and are no longer wiped; JE may keep a ghost cursor until a later authoritative update. Potion/tipped-arrow variant selection still depends on `bedrockItem()` restoring aux correctly (#1-4 / #1-15).

## Open risks (not patched here)

- Static `runtime_item_states.json` is still the international dump. MOT `runtime_item_states_netease_860.json` differs on 552 vanilla ids, but MOT always sends live `ITEM_REGISTRY`, so classification (`id <= 255`) is the remaining static risk.
- Geyser `block_palette.26_*.nbt` is Java↔Bedrock 1.21.x international, not NetEase 860. Do not replace ViaBedrock's hashed palette with it.
- EaseCation `upstream/main` is already at merge-base `fcf85f26`; no NetEase-only commits left to cherry-pick.
- Particle / VBU memory: custom entity payloads stay on `viabedrockutility:data`. Display-entity fallback can leak if VBU is missing and `enable-server-entity-animation` is on.
- NukkitMaster shop / urge callbacks (`UrgeShipEvent`, `StoreBuySuccServerEvent`) are still not synthesized from Java.
- Java clients do not emit C2S `SyncSkin(236)`; MOT only applies that path on protocol 860 for Bedrock-style skin changes after login.
