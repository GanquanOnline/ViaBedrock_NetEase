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
package net.raphimc.viabedrock.api.model.container;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;

/**
 * 恒等映射的基岩容器，其 Java 菜单槽位数已与基岩槽位数一致。
 * 用于漏斗、发射器、投掷器、木桶、合成器、载具箱子
 * 以及其余共享 1:1 槽位布局的原版方块菜单。
 */
public class GenericContainer extends Container {

    public GenericContainer(final UserConnection user, final byte containerId, final ContainerType type,
                            final TextComponent title, final BlockPosition position, final int size,
                            final String... validBlockTags) {
        super(user, containerId, type, title, position, size, validBlockTags);
    }

}

