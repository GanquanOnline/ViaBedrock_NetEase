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
package net.raphimc.viabedrock.protocol.provider;

import com.viaversion.viaversion.api.platform.providers.Provider;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public abstract class ResourcePackProvider implements Provider {

    private static final AtomicBoolean WARNED_UNSCOPED_ALIAS_TRUST = new AtomicBoolean();

    public abstract boolean has(final ResourcePack.Key key);

    public abstract ResourcePack load(final ResourcePack.Key key) throws Exception;

    public abstract void save(final ResourcePack resourcePack) throws Exception;

    /**
     * 旧的提供器只收到 UUID/版本，因此无法安全地区分后端或
     * 相互冲突的声明。共享缓存模式必须通过精确的 CAS 标识来解析资源包。
     */
    public static boolean isLegacyAliasLookupAllowed(final boolean sharedCacheEnabled) {
        return !sharedCacheEnabled;
    }

    protected final boolean isLegacyAliasLookupAllowed() {
        final boolean allowed = isLegacyAliasLookupAllowed(ViaBedrock.isSharedResourcePackCacheEnabled());
        if (!allowed && ViaBedrock.getConfig().shouldTrustDeclaredPackAlias()
                && WARNED_UNSCOPED_ALIAS_TRUST.compareAndSet(false, true)) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                    "The legacy global UUID/version resource-pack provider remains disabled; "
                            + "trust-declared-pack-alias only applies to backend-scoped verified CAS observations");
        }
        return allowed;
    }

}
