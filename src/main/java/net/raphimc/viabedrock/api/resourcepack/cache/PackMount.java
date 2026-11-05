/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.cache;

import java.util.Objects;

/** 一个有序堆叠挂载，把声明的别名绑定到确切内容和一次子包选择。 */
public record PackMount(PackAlias alias, ContentDigest contentDigest, String subpack) {

    public PackMount {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(contentDigest, "contentDigest");
        Objects.requireNonNull(subpack, "subpack");
        DigestSupport.strictUtf8(subpack, "subpack");
    }

    public PackMount(final PackAlias alias, final ContentDigest contentDigest) {
        this(alias, contentDigest, "");
    }

}
