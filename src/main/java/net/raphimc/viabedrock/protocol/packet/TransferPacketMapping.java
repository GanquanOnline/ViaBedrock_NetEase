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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;

/**
 * Bedrock TRANSFER grew an optional reload-world boolean after 1.21, then NetEase 860
 * trailers. Official ViaBedrock cancels the mapped Java TRANSFER when that boolean is
 * true. Waterdog Java seamless transfer still needs the rewritten Java packet, so we
 * consume the extra fields and keep the mapping.
 */
public final class TransferPacketMapping {

    private TransferPacketMapping() {
    }

    public static void consumeLegacyAndLeftoverFields(final PacketWrapper wrapper) {
        if (wrapper instanceof PacketWrapperImpl packetWrapper) {
            final ByteBuf input = packetWrapper.getInputBuffer();
            if (input != null && input.isReadable()) {
                input.readBoolean(); // optional reload world
            }
            PacketLeftoverLayout.discardUnreadInput(input);
            return;
        }
        if (wrapper.isReadable(Types.BOOLEAN, 0)) {
            wrapper.read(Types.BOOLEAN); // reload world
        }
        PacketLeftoverLayout.discardUnreadInput(wrapper);
    }
}
