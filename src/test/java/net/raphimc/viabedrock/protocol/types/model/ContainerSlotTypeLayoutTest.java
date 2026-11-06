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
package net.raphimc.viabedrock.protocol.types.model;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerSlotTypeLayoutTest {

    @Test
    void neteaseShiftsIdsFromRecipeItems() {
        assertTrue(ContainerSlotTypeLayout.usesNetEaseIdShift(true));
        assertFalse(ContainerSlotTypeLayout.usesNetEaseIdShift(false));
        assertEquals(17, ContainerSlotTypeLayout.toWire(ContainerEnumName.RecipeItemsContainer, false));
        assertEquals(18, ContainerSlotTypeLayout.toWire(ContainerEnumName.RecipeItemsContainer, true));
        assertEquals(63, ContainerSlotTypeLayout.toWire(ContainerEnumName.DynamicContainer, false));
        assertEquals(64, ContainerSlotTypeLayout.toWire(ContainerEnumName.DynamicContainer, true));
        assertEquals(0, ContainerSlotTypeLayout.toWire(null, true));
    }

    @Test
    void neteaseWire64IsDynamicContainer() {
        assertEquals(ContainerEnumName.DynamicContainer, ContainerSlotTypeLayout.fromWire(64, true));
        assertEquals(ContainerEnumName.RecipeFoodContainer, ContainerSlotTypeLayout.fromWire(64, false));
        assertNull(ContainerSlotTypeLayout.fromWire(17, true));
        assertEquals(ContainerEnumName.RecipeItemsContainer, ContainerSlotTypeLayout.fromWire(17, false));
    }

    /**
     * 2225 才把 67~70 定义成保留槽位，之前这些 id 在官方 id 空间里非法。当前生成的枚举本来
     * 就没有这四个值，所以读写两侧一律按未知处理。重新生成枚举时如果补上了
     * ReservedContainerA~D，这个钉子会失败，提醒按协议号把它挡在 2225 之前。
     * 网易 860 的 id 平移不参与：那一侧 67 会先减 1 变成标准的 66。
     */
    @Test
    void reservedContainerIdsStayUnknown() {
        for (int wireId = 67; wireId <= 70; wireId++) {
            assertNull(ContainerSlotTypeLayout.fromWire(wireId, false));
        }
    }
}
