/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 */
package net.raphimc.viabedrock.experimental.block;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.ViaBedrock;

import java.util.Set;

/**
 * BedrockLoader 自定义方块映射同步入口。
 * 旧的 FabricRock 磁盘 JSON/全局映射路径已被有意禁用；映射现在按 UserConnection 分别维护。
 */
public class CustomBlockMappingModule implements FeatureModule {

    @Override
    public void onChannelRegistered(final UserConnection user, final Set<String> channels) {
        if (channels.contains(CustomMappingSyncStorage.CHANNEL)) {
            ViaBedrock.getPlatform().getLogger().info("BedrockLoader custom mapping sync channel registered by Java client");
            user.get(CustomMappingSyncStorage.class).beginRequestIfNeeded();
        }
    }

    @Override
    public boolean handleCustomPayload(final String channel, final PacketWrapper wrapper) {
        if (!CustomMappingSyncStorage.CHANNEL.equals(channel)) {
            return false;
        }
        final byte[] payload = wrapper.read(Types.SERVERBOUND_CUSTOM_PAYLOAD_DATA);
        wrapper.user().get(CustomMappingSyncStorage.class).handlePayload(payload);
        return true;
    }
}
