/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.model;

import java.util.List;
import java.util.Objects;

/** 单个已同步实体属性的动态注册 MOT 定义。 */
public record EntityPropertyDefinition(String name, int type, List<String> enumValues) {

    public static final int INTEGER = 0;
    public static final int FLOAT = 1;
    public static final int BOOLEAN = 2;
    public static final int ENUM = 3;

    public EntityPropertyDefinition {
        Objects.requireNonNull(name, "name");
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    public boolean isFloat() {
        return this.type == FLOAT;
    }

    public boolean isEnum() {
        return this.type == ENUM;
    }

    public String enumValue(final int index) {
        return index >= 0 && index < this.enumValues.size() ? this.enumValues.get(index) : null;
    }
}
