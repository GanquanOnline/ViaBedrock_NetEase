/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.api.util;

import java.util.Set;

/**
 * 破坏时间为 0（hardness == 0）的原版方块，即无论使用何种工具或 haste 效果，
 * 对每位玩家都会立即破坏。原版 Java 客户端破坏此类方块时只发送单次
 * {@code START_DESTROY_BLOCK}（不发送 {@code STOP_DESTROY_BLOCK}）；因此 ViaBedrock 必须在
 * START 处理器中完成基岩侧破坏，否则该方块在基岩服务端永远不会被破坏，
 * 客户端的预测结果也会被回滚（见 {@code ClientPlayerPackets}）。
 *
 * <p>该集合以 <b>Java</b> 方块标识符（不含命名空间，例如 {@code wheat}）为键，因为
 * Java 方块名在各版本间稳定，不像基岩调色板名称。它对应 EaseCation Nukkit 服务端上
 * {@code Block#getHardness() == 0} 的那些方块。自定义（mod）方块另行处理，
 * 通过 BedrockLoader 自定义映射通道同步的 {@code seconds_to_destroy}。
 *
 * <p>匹配是精确的，因此在此处存在但在某版本中缺失的标识符只是一个空操作
 * （绝不会误判）。工具加速实现的非零硬度方块瞬间破坏被有意
 * 排除在范围之外。
 */
public final class InstantBreakBlocks {

    private static final Set<String> SHEARS_INSTANT_BREAK = Set.of(
            "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves",
            "dark_oak_leaves", "mangrove_leaves", "cherry_leaves", "pale_oak_leaves",
            "azalea_leaves", "flowering_azalea_leaves"
    );

    private static final Set<String> CUSTOM_SHEARS_INSTANT_BREAK = Set.of(
            "easecation:cherry_leaves"
    );

    private static final Set<String> VANILLA_INSTANT_BREAK = Set.of(
            // 作物 / 耕地植物
            "wheat", "carrots", "potatoes", "beetroots",
            "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
            "nether_wart", "torchflower_crop", "pitcher_crop", "sweet_berry_bush",
            // 树苗 / 胎生苗
            "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling", "acacia_sapling",
            "dark_oak_sapling", "cherry_sapling", "pale_oak_sapling", "mangrove_propagule", "bamboo_sapling",
            // 小型花
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
            "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose",
            "torchflower", "closed_eyeblossom", "open_eyeblossom",
            // 高花 / 双层植物
            "sunflower", "lilac", "rose_bush", "peony", "pink_petals", "wildflowers",
            // 草 / 蕨 / 灌木
            "short_grass", "grass", "fern", "tall_grass", "large_fern", "dead_bush", "bush", "firefly_bush",
            // 水生植物
            "seagrass", "tall_seagrass", "kelp", "kelp_plant", "lily_pad", "sea_pickle",
            // 下界 / 洞穴植被
            "crimson_roots", "warped_roots", "nether_sprouts", "crimson_fungus", "warped_fungus",
            "weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant",
            "cave_vines", "cave_vines_plant", "hanging_roots", "spore_blossom", "small_dripleaf",
            "azalea", "flowering_azalea",
            // 蘑菇
            "brown_mushroom", "red_mushroom",
            // 甘蔗
            "sugar_cane",
            // 火把 / 光源
            "torch", "wall_torch", "soul_torch", "soul_wall_torch",
            "redstone_torch", "redstone_wall_torch", "redstone_wire",
            // 其他零硬度方块。脚手架在 Java 中硬度为 0（只发 START），但 MOT 的
            // BlockScaffolding 硬度为 0.5，因此在后端并非瞬间破坏。
            "tnt", "slime_block", "honey_block", "fire", "soul_fire", "frogspawn",
            "tripwire", "flower_pot", "decorated_pot", "end_rod"
    );

    private static final Set<String> JAVA_START_ONLY_DELAYED_MOT_BREAK = Set.of(
            "scaffolding"
    );

    private InstantBreakBlocks() {
    }

    /**
     * Java 创造模式（以及硬度为 0 / 剪刀瞬破的方块）只发送 {@code START_DESTROY_BLOCK}。
     * Nukkit-MOT SAI 从不处理 {@code CreativeDestroyBlock(13)}；它只在
     * {@code PredictDestroyBlock} 时破坏。因此这些情况下 ViaBedrock 必须在 START
     * 处理器中完成基岩侧破坏。
     */
    public static boolean shouldCompleteOnJavaStart(
            final boolean creative,
            final String javaIdentifier,
            final Float customSecondsToDestroy,
            final String heldIdentifier,
            final String customBedrockIdentifier) {
        if (creative) {
            return true;
        }
        if (isJavaStartOnlyDelayedMotBreak(javaIdentifier)) {
            return false;
        }
        if (javaIdentifier != null && isVanillaInstantBreak(javaIdentifier)) {
            return true;
        }
        if (customSecondsToDestroy != null && customSecondsToDestroy.floatValue() == 0.0F) {
            return true;
        }
        return "minecraft:shears".equals(heldIdentifier)
                && isShearsInstantBreak(javaIdentifier, customBedrockIdentifier);
    }

    /**
     * Java 仍然只发送 {@code START_DESTROY_BLOCK}，但 MOT 的硬度不为 0。
     * 在同一刻用 {@code PredictDestroyBlock} 完成会被判定为快速破坏而拒绝。
     */
    public static boolean isJavaStartOnlyDelayedMotBreak(final String javaIdentifier) {
        return javaIdentifier != null && JAVA_START_ONLY_DELAYED_MOT_BREAK.contains(javaIdentifier);
    }

    /**
     * MOT 的 {@code BlockScaffolding} 硬度为 0.5，可徒手采集：
     * {@code calculateBreakTime = 0.5 * 1.5 = 0.75s}。{@code Level.useBreakOn} 会减去
     * 0.15s 的宽限，因此 PredictDestroy 在 0.60s（12 刻）后才会被接受。START 之后
     * 通常在同一刻收到 {@code CLIENT_TICK_END}，使 age 递增一次，
     * 所以应在 START 快照后第 13 刻到期。
     */
    public static int delayedMotBreakTicks(final String javaIdentifier) {
        if (!isJavaStartOnlyDelayedMotBreak(javaIdentifier)) {
            return 0;
        }
        return 13;
    }

    /**
     * @param javaIdentifier 不含命名空间的 Java 方块标识符（例如 {@code wheat}）
     *
     * @return 该原版方块是否总是瞬间破坏（硬度为 0）
     */
    public static boolean isVanillaInstantBreak(final String javaIdentifier) {
        return VANILLA_INSTANT_BREAK.contains(javaIdentifier);
    }

    public static boolean isShearsInstantBreak(final String javaIdentifier, final String customBedrockIdentifier) {
        return (javaIdentifier != null && SHEARS_INSTANT_BREAK.contains(javaIdentifier))
                || (customBedrockIdentifier != null && CUSTOM_SHEARS_INSTANT_BREAK.contains(customBedrockIdentifier));
    }

}
