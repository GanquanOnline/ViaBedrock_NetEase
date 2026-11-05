/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.protocol.model.EntityProperties;
import net.raphimc.viabedrock.protocol.model.EntityPropertyDefinition;

import java.util.List;
import java.util.Map;

/** 每连接的 MOT 实体属性注册表与快照解析器。 */
public final class EntityPropertyStorage extends StoredObject {

    private final EntityPropertyRegistry registry = new EntityPropertyRegistry();

    public EntityPropertyStorage(final UserConnection user) {
        super(user);
    }

    public static EntityPropertyStorage getOrCreate(final UserConnection user) {
        EntityPropertyStorage storage = user.get(EntityPropertyStorage.class);
        if (storage == null) {
            storage = new EntityPropertyStorage(user);
            user.put(storage);
        }
        return storage;
    }

    public void register(final CompoundTag root) {
        this.registry.register(root);
    }

    public EntityProperties resolve(final String entityIdentifier, final EntityProperties raw) {
        return this.registry.resolve(entityIdentifier, raw);
    }

    public List<EntityPropertyDefinition> definitions(final String entityIdentifier) {
        return this.registry.definitions(entityIdentifier);
    }

    public Map<String, List<EntityPropertyDefinition>> definitions() {
        return this.registry.snapshot();
    }
}
