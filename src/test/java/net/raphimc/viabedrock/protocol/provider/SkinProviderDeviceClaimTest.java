/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.provider;

import net.raphimc.viabedrock.api.util.JavaClientDevice;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.BuildPlatform;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkinProviderDeviceClaimTest {

    @Test
    void writesPlayerDeviceInsteadOfGatewayMotherboard() {
        final Map<String, Object> claims = new HashMap<>();
        SkinProvider.applyDeviceClaims(claims, JavaClientDevice.fromSystemProperties("Windows 11", "amd64", "10.0"));

        assertEquals("Windows 11 10.0 (amd64)", claims.get("DeviceModel"));
        assertEquals(BuildPlatform.UWP.getValue(), claims.get("DeviceOS"));
        assertFalse(String.valueOf(claims.get("DeviceModel")).contains("MS-7E51"));
    }

    @Test
    void fallsBackToJavaEditionWithoutClientReport() {
        final Map<String, Object> claims = new HashMap<>();
        SkinProvider.applyDeviceClaims(claims, null);

        assertEquals("Java Edition", claims.get("DeviceModel"));
        assertEquals(BuildPlatform.UWP.getValue(), claims.get("DeviceOS"));
    }

    @Test
    void writesJavaClientMarkerWithoutAuthSecret() {
        final Map<String, Object> claims = new HashMap<>();
        SkinProvider.putJavaClientAuthToken(claims, "", null);
        assertEquals(SkinProvider.JAVA_CLIENT_MARKER, claims.get("ViaProxyAuthToken"));
    }

    @Test
    void writesSignedTokenWhenSecretPresent() {
        final Map<String, Object> claims = new HashMap<>();
        SkinProvider.putJavaClientAuthToken(claims, "secret", "signed-token");
        assertEquals("signed-token", claims.get("ViaProxyAuthToken"));
    }

}
