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
package net.raphimc.viabedrock.experimental.modinterface;

import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.experimental.FeatureModule;

import java.util.Set;

public class ModUIClientModule implements FeatureModule {

    @Override
    public void onEntityAdded(final UserConnection user, final Entity entity) {
        ModUIClientInterface.sendEntityMappingAdd(user, entity.runtimeId(), entity.javaId());
    }

    @Override
    public void onEntityRemoved(final UserConnection user, final Entity entity) {
        ModUIClientInterface.sendEntityMappingRemove(user, entity.runtimeId());
    }

    @Override
    public void onChannelRegistered(final UserConnection user, final Set<String> channels) {
        if (channels.contains(ModUIClientInterface.ENTITY_MAPPING_CHANNEL)) {
            ModUIClientInterface.sendEntityMappingSync(user);
        }
    }

}
