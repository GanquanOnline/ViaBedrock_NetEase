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

/** 从带索引的 MOT 属性快照中解析出的具名值。 */
public record EntityPropertyValue(
        String name,
        int type,
        Object value,
        Integer rawIntegerValue,
        Float rawFloatValue
) {

    public boolean isPresent() {
        return this.value != null;
    }

    public boolean isUnknownType() {
        return this.type < EntityPropertyDefinition.INTEGER || this.type > EntityPropertyDefinition.ENUM;
    }

    public Integer intValue() {
        return this.rawIntegerValue;
    }

    public Float floatValue() {
        return this.rawFloatValue;
    }

    public Boolean booleanValue() {
        return this.value instanceof Boolean booleanValue ? booleanValue : null;
    }

    public String enumValue() {
        return this.value instanceof String stringValue ? stringValue : null;
    }

    public Object rawValue() {
        return this.rawFloatValue != null ? this.rawFloatValue : this.rawIntegerValue;
    }
}
