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
package net.raphimc.viabedrock.experimental.task;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.experimental.storage.ScriptDebugTextTracker;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

/**
 * 每个刻驱动一次 {@link ScriptDebugTextTracker#tick()}，使具有
 * 有限存活时间（{@code totalTimeLeft}）的调试文本形状被自动移除，与基岩客户端一致。
 */
public class ScriptDebugTextTickTask implements Runnable {

    @Override
    public void run() {
        for (UserConnection info : Via.getManager().getConnectionManager().getConnections()) {
            final ScriptDebugTextTracker tracker = info.get(ScriptDebugTextTracker.class);
            if (tracker != null) {
                info.getChannel().eventLoop().submit(() -> {
                    if (!info.getChannel().isActive()) return;

                    try {
                        tracker.tick();
                    } catch (Throwable e) {
                        BedrockProtocol.kickForIllegalState(info, "Error ticking script debug text tracker. See console for details.", e);
                    }
                });
            }
        }
    }

}
