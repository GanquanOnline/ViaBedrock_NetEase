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
import net.raphimc.viabedrock.experimental.storage.MultilineNametagTracker;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

/** 每个客户端刻协调一次依赖观察者的玩家多行名称标签可见性。 */
public class MultilineNametagTickTask implements Runnable {

    @Override
    public void run() {
        for (UserConnection info : Via.getManager().getConnectionManager().getConnections()) {
            final MultilineNametagTracker tracker = info.get(MultilineNametagTracker.class);
            if (tracker != null) {
                info.getChannel().eventLoop().submit(() -> {
                    if (!info.getChannel().isActive()) return;

                    try {
                        tracker.tick();
                    } catch (Throwable e) {
                        BedrockProtocol.kickForIllegalState(info, "Error ticking multiline nametag tracker. See console for details.", e);
                    }
                });
            }
        }
    }

}
