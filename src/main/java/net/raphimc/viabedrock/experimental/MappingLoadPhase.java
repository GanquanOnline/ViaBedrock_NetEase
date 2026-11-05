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
package net.raphimc.viabedrock.experimental;

/**
 * 在 BedrockMappingData.load() 期间，功能模块可注入自定义映射的各阶段。
 * 顺序与 load 方法中的初始化顺序一致。
 */
public enum MappingLoadPhase {

    /**
     * 在原版方块状态被载入 javaBlockStates BiMap 之后。
     * 自定义方块状态映射应在此处添加。
     */
    AFTER_BLOCK_STATES,

    /**
     * 在 bedrockToJavaEntities 映射被填充之后。
     * 自定义实体类型回退映射应在此处加载。
     */
    AFTER_ENTITY_MAPPINGS,

    /**
     * 在 javaBlockEntities 映射被填充之后。
     * 自定义方块实体类型映射应在此处加载。
     */
    AFTER_BLOCK_ENTITIES,

    /**
     * 在所有映射完全加载之后。
     * 附加数据文件（例如 block_light_data.nbt）应在此处加载。
     */
    COMPLETE

}
