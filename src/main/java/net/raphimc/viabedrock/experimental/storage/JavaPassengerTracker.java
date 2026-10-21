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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.ints.IntArrayList;
import com.viaversion.viaversion.libs.fastutil.ints.IntList;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class JavaPassengerTracker extends StoredObject {

    private final Map<Integer, int[]> bedrockPassengers = new LinkedHashMap<>();
    private final Map<String, Map<Integer, int[]>> virtualPassengers = new LinkedHashMap<>();

    public JavaPassengerTracker(final UserConnection user) {
        super(user);
    }

    public void reset() {
        this.bedrockPassengers.clear();
        this.virtualPassengers.clear();
    }

    public void setBedrockPassengers(final int vehicleJavaId, final int... passengerJavaIds) {
        if (passengerJavaIds.length == 0) {
            this.bedrockPassengers.remove(vehicleJavaId);
        } else {
            this.bedrockPassengers.put(vehicleJavaId, passengerJavaIds);
        }
        this.sendPassengers(vehicleJavaId);
    }

    public void clearVehicle(final int vehicleJavaId) {
        this.bedrockPassengers.remove(vehicleJavaId);
        for (final Map<Integer, int[]> sourcePassengers : this.virtualPassengers.values()) {
            sourcePassengers.remove(vehicleJavaId);
        }

        final PacketWrapper setPassengers = PacketWrapper.create(ClientboundPackets26_1.SET_PASSENGERS, this.user());
        setPassengers.write(Types.VAR_INT, vehicleJavaId); // vehicle entity id
        setPassengers.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[0]); // passenger entity ids
        setPassengers.send(BedrockProtocol.class);
    }

    public void setVirtualPassengers(final String source, final int vehicleJavaId, final int... passengerJavaIds) {
        final Map<Integer, int[]> sourcePassengers = this.virtualPassengers.computeIfAbsent(source, k -> new LinkedHashMap<>());
        if (passengerJavaIds.length == 0) {
            sourcePassengers.remove(vehicleJavaId);
            if (sourcePassengers.isEmpty()) {
                this.virtualPassengers.remove(source);
            }
        } else {
            sourcePassengers.put(vehicleJavaId, passengerJavaIds);
        }
        this.sendPassengers(vehicleJavaId);
    }

    public void resetForDimensionChange() {
        final Set<Integer> vehicles = new LinkedHashSet<>(this.bedrockPassengers.keySet());
        for (final Map<Integer, int[]> sourcePassengers : this.virtualPassengers.values()) {
            vehicles.addAll(sourcePassengers.keySet());
        }
        this.bedrockPassengers.clear();
        this.virtualPassengers.clear();
        for (final int vehicleJavaId : vehicles) {
            this.sendPassengers(vehicleJavaId);
        }
    }

    boolean hasTrackedPassengers() {
        return !this.bedrockPassengers.isEmpty() || !this.virtualPassengers.isEmpty();
    }

    public void clearSource(final String source) {
        final Map<Integer, int[]> removed = this.virtualPassengers.remove(source);
        if (removed == null) {
            return;
        }
        for (final int vehicleJavaId : removed.keySet()) {
            this.sendPassengers(vehicleJavaId);
        }
    }

    private void sendPassengers(final int vehicleJavaId) {
        final IntList passengers = new IntArrayList();
        final int[] realPassengers = this.bedrockPassengers.get(vehicleJavaId);
        if (realPassengers != null) {
            passengers.addElements(passengers.size(), realPassengers);
        }
        for (final Map<Integer, int[]> sourcePassengers : this.virtualPassengers.values()) {
            final int[] virtualIds = sourcePassengers.get(vehicleJavaId);
            if (virtualIds != null) {
                passengers.addElements(passengers.size(), virtualIds);
            }
        }

        final PacketWrapper setPassengers = PacketWrapper.create(ClientboundPackets26_1.SET_PASSENGERS, this.user());
        setPassengers.write(Types.VAR_INT, vehicleJavaId); // vehicle entity id
        setPassengers.write(Types.VAR_INT_ARRAY_PRIMITIVE, passengers.toIntArray()); // passenger entity ids
        setPassengers.send(BedrockProtocol.class);
    }

}
