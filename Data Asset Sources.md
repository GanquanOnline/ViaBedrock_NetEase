# ViaBedrock Data Asset Sources

This file lists all the sources/code to obtain the data assets used in ViaBedrock.  
If a file is not listed here, it has been created manually and is updated manually.

### data/bedrock/biome_definitions.nbt
This file has been dumped from a BDS server using [CloudburstMC/ProxyPass](https://github.com/CloudburstMC/ProxyPass).

### data/bedrock/biomes.json
[PMMP/BedrockData](https://github.com/pmmp/BedrockData/blob/5dc2e96d4adb1e96b30b57e782ec2c59bdad4317/biome_id_map.json)

### data/bedrock/block_id_meta_to_1_12_0_nbt.bin
[PMMP/BedrockBlockUpgradeSchema](https://github.com/pmmp/BedrockBlockUpgradeSchema/blob/8b72c47109e174ac7f17c3ac546748f8e49a5fdf/id_meta_to_nbt/1.12.0.bin)

### data/bedrock/block_legacy_id_map.json
[PMMP/BedrockBlockUpgradeSchema](https://github.com/pmmp/BedrockBlockUpgradeSchema/blob/79bb3ad542ef19e828fdf1fa6adc54f1fa4b3bb5/block_legacy_id_map.json)

### data/bedrock/block_palette.nbt
[GeyserMC/Geyser](https://github.com/GeyserMC/Geyser/blob/ba17cf2f4b030603bea017e2c30902e2187df340/core/src/main/resources/bedrock/block_palette.1_26_20.nbt)

Hashed `network_id` values match Nukkit-MOT `runtime_block_states_netease_860.dat` (FNV-1a of little-endian `{name, states}`). Sequential MOT runtime ids are **not** this file's hashed-name order.

### data/bedrock/netease_860_block_runtime_ids.json
Generated from Nukkit-MOT jar resource `runtime_block_states_netease_860.dat` (local dump: `decompiled/jars/Nukkit-MOT-SNAPSHOT.jar`). Maps hashed `network_id` → MOT sequential `runtimeId`, and adds MOT-only `minecraft:micro_block`.

### data/versions/<protocol>/block_palette.nbt
### data/versions/<protocol>/runtime_item_states.json
### data/versions/<protocol>/biome_definitions.nbt (975 only)
Generated from the Nukkit-MOT jar (`decompiled/nukkit/jars/Nukkit-MOT-SNAPSHOT.jar`) with

```
./gradlew importBedrockDataTables --args="import <motJar> src/main/resources/assets/viabedrock/data/versions <protocol>... --base src/main/resources/assets/viabedrock/data/bedrock/block_palette.nbt"
```

- `versions/975/` is a byte-for-byte copy of the shared tables above and stays the golden reference.
- MOT's dumps are **subsets**: measured against the shared 975 palette, `runtime_block_states_*` adds 0 new
  states for 1001/2168, omits 387 states (education/editor blocks plus some stem, frame, jigsaw, vault and
  trial_spawner variants), and `runtime_item_states_*` lacks the `minecraft:item.*` aliases the Java side
  mapping needs. The importer therefore uses the shared tables as a base and only appends what MOT adds,
  with `--base` naming that base palette (the item file is read from the same directory).
- `network_id`, `name_hash` and `version` are recomputed from each MOT block state with `BlockStateHasher`
  and `FNV1`, not taken from MOT's sequential `runtimeId`; `BedrockDataTableContentTest` re-derives them
  for every shipped entry and checks that every shared block state has a palette entry, which is the lookup
  `BedrockMappingData` performs at startup.
- `block_id` keeps MOT's numeric block id on MOT-sourced entries. ViaBedrock never reads that field (only
  `network_id` is used in `BlockStateRewriter`), so base entries keep Geyser's legacy id and `verify` reports
  those differences separately from wire-field mismatches.
- MOT only ships `biome_definitions_<protocol>.dat` for old protocols, so 1001/2168 have no biome file and
  fall back to `data/bedrock/biome_definitions.nbt` (see `BedrockDataTables`).
- Per-version item ids do differ: 975 has 1941 items, 1001 1943 and 2168 1986 after the merge.
- `verify` against the shared 975 palette reports 16512 generated states vs 16899 golden states, **0**
  wire-field mismatches, and the 387 golden-only states listed above.

### data/bedrock/block_sounds.json
This file has been generated using `BedrockSoundListGenerator` using data from the official bedrock client's assets folder.

### data/bedrock/block_traits.json
[microsoft.com](https://learn.microsoft.com/en-us/minecraft/creator/documents/intro-block-traits?view=minecraft-bedrock-stable)  
[Mojang/bedrock-samples](https://github.com/Mojang/bedrock-samples/blob/84aa19df535fcf6d044f28bf76f236029bd8407a/metadata/doc_modules/addons.json)  
[wiki.bedrock.dev](https://wiki.bedrock.dev/blocks/block-traits.html)

### data/bedrock/effects.json
[minecraft.wiki](https://minecraft.wiki/w/Effect)

### data/bedrock/entity_identifiers.nbt
This file has been dumped from a BDS server using [CloudburstMC/ProxyPass](https://github.com/CloudburstMC/ProxyPass).

### data/bedrock/game_rules.json
This file has been dumped from a BDS server using [CloudburstMC/ProxyPass](https://github.com/CloudburstMC/ProxyPass).

### data/bedrock/item_tags.json
[PMMP/BedrockData](https://github.com/pmmp/BedrockData/blob/e5a25957497a4520f4672a379e2a3f6b87602e37/item_tags.json)

### data/bedrock/level_sound_event_mappings.json
This file has been generated using `BedrockSoundListGenerator` using data from the official bedrock client's assets folder.

### data/bedrock/particles.json
This file has been generated using `BedrockParticleListGenerator` using data from the official bedrock client's assets folder.

### data/bedrock/runtime_item_states.json
This file has been dumped from a BDS server using [CloudburstMC/ProxyPass](https://github.com/CloudburstMC/ProxyPass).

### data/bedrock/sounds.json
This file has been generated using `BedrockSoundListGenerator` using data from the official bedrock client's assets folder.

### data/java/effects.json
This file has been dumped using a fabric mod.

### data/java/entity_data_fields.json
This file has been dumped using a fabric mod.

### data/java/heightmap_blockstates.nbt
This file has been dumped using a fabric mod.

### data/java/item_max_stack_sizes.json
[misode/mcmeta Java 26.1 item components report](https://github.com/misode/mcmeta/blob/e7068326fb23dcdff9fff17c7eabcd59f46e7460/item_components/data.json)

### data/java/registries.nbt
This file has been dumped from a vanilla server using a fabric mod.

### data/java/tags.nbt
This file has been dumped from a vanilla server using a fabric mod.

### data/java/via_mappings.json
[ViaVersion/Mappings](https://github.com/ViaVersion/Mappings/blob/2b78dc148ea0164f10efd716bdc48aeb3ad8494b/mappings/mapping-26.1.json)

### block_state_upgrade_schema/*
[PMMP/BedrockBlockUpgradeSchema](https://github.com/pmmp/BedrockBlockUpgradeSchema/tree/master/nbt_upgrade_schema)

### item_upgrade_schema/*
[PMMP/BedrockItemUpgradeSchema](https://github.com/pmmp/BedrockItemUpgradeSchema/tree/master/id_meta_upgrade_schema)

### resource_packs/*
These files have been generated using `BedrockPacksGenerator` using data from the official bedrock client's assets folder.

### skin_packs/*
These files have been generated using `BedrockPacksGenerator` using data from the official bedrock client's assets folder.
