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
package net.raphimc.viabedrock.test;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.platform.ViaPlatform;
import com.viaversion.viaversion.ViaManagerImpl;
import com.viaversion.viaversion.debug.DebugHandlerImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.logging.Logger;

/**
 * 测试夹具共用的 Via 平台桩。
 * Via 5.12 的 UserConnectionImpl 在构造时就会查询 ProtocolManager，所以夹具必须在此之前装好管理器，
 * 否则字段初始化阶段就会落到空 manager 或上一个用例遗留的管理器上。
 */
public final class ViaTestBootstrap {

    private ViaTestBootstrap() {
    }

    public static void installDefaultManager() {
        if (Via.isLoaded() && Via.getManager() instanceof ViaManagerImpl) {
            return; // 真实 ViaVersion 已加载，装桩会打乱需要真实现的夹具
        }
        try {
            final Field managerField = Via.class.getDeclaredField("manager");
            managerField.setAccessible(true);
            managerField.set(null, newManager());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("无法安装 Via 管理器桩", e);
        }
    }

    /** 让需要真 ViaVersion 的夹具能清掉夹具装的管理器桩。 */
    public static void clearManagerUnless(final Class<?> realType) {
        try {
            final Field managerField = Via.class.getDeclaredField("manager");
            managerField.setAccessible(true);
            final Object current = managerField.get(null);
            if (current != null && !realType.isInstance(current)) {
                managerField.set(null, null);
            }
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("无法清理 Via 管理器桩", e);
        }
    }

    private static ViaManager newManager() {
        return (ViaManager) Proxy.newProxyInstance(ViaManager.class.getClassLoader(), new Class[]{ViaManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("getProtocolManager")) {
                return newProtocolManager();
            }
            if (method.getName().equals("debugHandler")) {
                return new DebugHandlerImpl();
            }
            if (method.getName().equals("getPlatform")) {
                return newPlatform();
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType() == int.class) {
                return 0;
            }
            return null;
        });
    }

    private static ViaPlatform newPlatform() {
        return (ViaPlatform) Proxy.newProxyInstance(ViaPlatform.class.getClassLoader(), new Class[]{ViaPlatform.class}, (proxy, method, args) -> {
            if (method.getName().equals("getLogger")) {
                return Logger.getLogger("ViaBedrockTest");
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType() == int.class) {
                return 0;
            }
            return null;
        });
    }

    private static ProtocolManager newProtocolManager() {
        return (ProtocolManager) Proxy.newProxyInstance(ProtocolManager.class.getClassLoader(), new Class[]{ProtocolManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("createPacketWrapper")) {
                return new PacketWrapperImpl((PacketType) args[0], null, (UserConnection) args[2]);
            }
            if (method.getName().equals("registeredProtocolCount") || method.getReturnType() == int.class) {
                return 0;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            return null;
        });
    }

}
