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
package net.raphimc.viabedrock.api.util;

import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.BuildPlatform;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 握手时上报的 Java 客户端设备信息。登录 JWT 在进入 PLAY 之前
 * 就已构建，因此携带玩家操作系统信息的唯一无损位置是
 * 握手主机名后缀 {@code \0gqdev\0os|model}。
 */
public final class JavaClientDevice {

    public static final String TOKEN = "gqdev";
    public static final String SEPARATOR = "\0" + TOKEN + "\0";
    public static final JavaClientDevice JAVA_EDITION = new JavaClientDevice(
            "Java Edition", BuildPlatform.UWP.getValue(), "windows");

    private static final int MAX_MODEL_LENGTH = 80;

    private final String model;
    private final int deviceOs;
    private final String osName;

    public JavaClientDevice(final String model, final int deviceOs, final String osName) {
        this.model = sanitizeModel(model);
        this.deviceOs = deviceOs;
        this.osName = osName == null || osName.isBlank() ? "windows" : osName.toLowerCase(Locale.ROOT);
    }

    public String model() {
        return this.model;
    }

    public int deviceOs() {
        return this.deviceOs;
    }

    public String osName() {
        return this.osName;
    }

    public static String handshakeSuffix(final String address) {
        if (address == null) {
            return "";
        }
        final int index = address.indexOf(SEPARATOR);
        return index < 0 ? "" : address.substring(index);
    }

    public static String stripHandshakeSuffix(final String address) {
        if (address == null) {
            return "";
        }
        final int index = address.indexOf(SEPARATOR);
        return index < 0 ? address : address.substring(0, index);
    }

    public static String appendToHandshake(final String address, final JavaClientDevice device) {
        final String host = stripHandshakeSuffix(address == null ? "" : address);
        if (device == null) {
            return host;
        }
        return host + SEPARATOR + URLEncoder.encode(device.osName() + "|" + device.model(), StandardCharsets.UTF_8);
    }

    public static JavaClientDevice parseFromHandshake(final String address) {
        final String suffix = handshakeSuffix(address);
        if (suffix.isEmpty()) {
            return JAVA_EDITION;
        }
        final String encoded = suffix.substring(SEPARATOR.length());
        if (encoded.isEmpty()) {
            return JAVA_EDITION;
        }
        final String decoded;
        try {
            decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return JAVA_EDITION;
        }
        final int split = decoded.indexOf('|');
        final String osName = split < 0 ? decoded : decoded.substring(0, split);
        final String model = split < 0 ? decoded : decoded.substring(split + 1);
        return fromOsAndModel(osName, model);
    }

    public static JavaClientDevice fromOsAndModel(final String rawOs, final String rawModel) {
        final String os = rawOs == null ? "" : rawOs.toLowerCase(Locale.ROOT);
        final String model = sanitizeModel(rawModel == null || rawModel.isBlank() ? rawOs : rawModel);
        if (os.contains("win")) {
            return new JavaClientDevice(model.isEmpty() ? "Windows" : model, BuildPlatform.UWP.getValue(), "windows");
        }
        if (os.contains("mac") || os.contains("osx") || os.contains("darwin")) {
            return new JavaClientDevice(model.isEmpty() ? "macOS" : model, BuildPlatform.OSX.getValue(), "macos");
        }
        if (os.contains("linux") || os.contains("unix")) {
            return new JavaClientDevice(model.isEmpty() ? "Linux" : model, BuildPlatform.Linux.getValue(), "linux");
        }
        if (model.isEmpty()) {
            return JAVA_EDITION;
        }
        return new JavaClientDevice(model, BuildPlatform.UWP.getValue(), "windows");
    }

    public static JavaClientDevice fromSystemProperties(final String osName, final String osArch, final String osVersion) {
        final String family = osName == null ? "" : osName;
        final StringBuilder model = new StringBuilder(family.isBlank() ? "Java Edition" : family.trim());
        if (osVersion != null && !osVersion.isBlank() && !model.toString().contains(osVersion.trim())) {
            model.append(' ').append(osVersion.trim());
        }
        if (osArch != null && !osArch.isBlank()) {
            model.append(" (").append(osArch.trim()).append(')');
        }
        return fromOsAndModel(family, model.toString());
    }

    static String sanitizeModel(final String model) {
        if (model == null) {
            return "Java Edition";
        }
        final String collapsed = model.replace('\0', ' ').replace('|', '/').trim();
        if (collapsed.isEmpty()) {
            return "Java Edition";
        }
        return collapsed.length() <= MAX_MODEL_LENGTH ? collapsed : collapsed.substring(0, MAX_MODEL_LENGTH);
    }

}
