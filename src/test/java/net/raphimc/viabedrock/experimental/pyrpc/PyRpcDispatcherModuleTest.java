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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.pyrpc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyRpcDispatcherModuleTest {

    @Test
    void clientLoadAddonsFinishedFitsFixstr() {
        final String method = PyRpcDispatcherModule.CLIENT_LOAD_ADDONS_FINISHED;
        assertEquals(31, method.getBytes(StandardCharsets.UTF_8).length);

        final byte[] payload = PyRpcDispatcherModule.buildClientLoadAddonsFinished();
        assertEquals((byte) 0x91, payload[0]); // fixarray 1
        assertEquals((byte) (0xa0 | 31), payload[1]); // fixstr 31
        assertEquals(method, new String(payload, 2, 31, StandardCharsets.US_ASCII));
        assertEquals(33, payload.length);
    }

    @Test
    void stringsLongerThan31BytesUseStr8() {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final String longName = "ClientLoadAddonsFinishedFromGacX"; // 32 字节
        assertEquals(32, longName.getBytes(StandardCharsets.UTF_8).length);
        PyRpcDispatcherModule.writeMsgPackStr(out, longName);
        final byte[] encoded = out.toByteArray();
        assertEquals((byte) 0xd9, encoded[0]);
        assertEquals(32, encoded[1] & 0xFF);
        assertEquals(longName, new String(encoded, 2, 32, StandardCharsets.US_ASCII));
    }

    @Test
    void engineCallMatchesMotStoreBuySuccessEnvelope() {
        // MOT NetEasePacketRegressionTest.testPyRpcPacketDecodesStoreBuySuccessSubPacket:
        // msgpackArray(msgpackString("StoreBuySuccServerEvent"))
        final byte[] payload = PyRpcDispatcherModule.buildEngineCall("StoreBuySuccServerEvent");
        final byte[] expectedName = "StoreBuySuccServerEvent".getBytes(StandardCharsets.US_ASCII);
        final byte[] expected = new byte[2 + expectedName.length];
        expected[0] = (byte) 0x91;
        expected[1] = (byte) (0xa0 | expectedName.length);
        System.arraycopy(expectedName, 0, expected, 2, expectedName.length);
        assertArrayEquals(expected, payload);
    }

    @Test
    void masterC2sMsgIdIs98247598() {
        assertEquals(98247598, PyRpcDispatcherModule.MSG_ID);
        assertTrue(PyRpcDispatcherModule.ADDONS_FINISHED_DELAY_MS > 0L);
    }

    @Test
    void motBin8ModEventS2CIsDetected() {
        final byte[] name = "ModEventS2C".getBytes(StandardCharsets.US_ASCII);
        final byte[] data = new byte[3 + name.length];
        data[0] = (byte) 0x93; // fixarray 3，MOT PyRpcWriter.writeMessage
        data[1] = (byte) 0xC4; // bin8，MOT PyRpcWriter.writeBinaryString
        data[2] = (byte) name.length;
        System.arraycopy(name, 0, data, 3, name.length);
        assertTrue(PyRpcDispatcherModule.isModEventS2C(data));
        assertEquals("ModEventS2C", PyRpcDispatcherModule.readFirstMsgPackString(data));
    }

    @Test
    void fixstrModEventS2CIsDetected() {
        final byte[] name = "ModEventS2C".getBytes(StandardCharsets.US_ASCII);
        final byte[] data = new byte[2 + name.length];
        data[0] = (byte) 0x93;
        data[1] = (byte) (0xA0 | name.length);
        System.arraycopy(name, 0, data, 2, name.length);
        assertTrue(PyRpcDispatcherModule.isModEventS2C(data));
    }

    @Test
    void engineCallIsNotModEventS2C() {
        assertFalse(PyRpcDispatcherModule.isModEventS2C(PyRpcDispatcherModule.buildClientLoadAddonsFinished()));
        assertFalse(PyRpcDispatcherModule.isModEventS2C(new byte[]{(byte) 0x93, (byte) 0xC4, 0x03, 'f', 'o', 'o'}));
        assertFalse(PyRpcDispatcherModule.isModEventS2C(null));
        assertFalse(PyRpcDispatcherModule.isModEventS2C(new byte[0]));
    }

    private static byte[] modEvent(final byte[] method, final String mod, final String system, final String event) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x93); // fixarray 3
        out.writeBytes(method);
        out.write(0x94); // fixarray 4
        PyRpcDispatcherModule.writeFixStr(out, mod);
        PyRpcDispatcherModule.writeFixStr(out, system);
        PyRpcDispatcherModule.writeFixStr(out, event);
        out.write(0x80); // fixmap 0
        out.write(0xc0); // nil
        return out.toByteArray();
    }

    private static byte[] msgPackString(final int code, final String value) {
        final byte[] name = value.getBytes(StandardCharsets.US_ASCII);
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(code);
        out.write(name.length);
        out.writeBytes(name);
        return out.toByteArray();
    }

    @Test
    void modEventTripleIsParsedFromFixstr() {
        final byte[] name = "ModEventS2C".getBytes(StandardCharsets.US_ASCII);
        final java.io.ByteArrayOutputStream method = new java.io.ByteArrayOutputStream();
        method.write(0xA0 | name.length);
        method.writeBytes(name);

        final String[] triple = PyRpcDispatcherModule.readModEventTriple(
                modEvent(method.toByteArray(), "ECNukkitClientMod", "ECNukkitClientSystem", "ResponseHudNodeDataEvent"));

        assertEquals(4, triple.length);
        assertEquals("ModEventS2C", triple[0]);
        assertEquals("ECNukkitClientMod", triple[1]);
        assertEquals("ECNukkitClientSystem", triple[2]);
        assertEquals("ResponseHudNodeDataEvent", triple[3]);
    }

    @Test
    void modEventTripleIsParsedFromMotBin8() {
        final String[] triple = PyRpcDispatcherModule.readModEventTriple(
                modEvent(msgPackString(0xC4, "ModEventS2C"), "m", "s", "RequestControlNodeEvent"));

        assertEquals(4, triple.length);
        assertEquals("ModEventS2C", triple[0]);
        assertEquals("RequestControlNodeEvent", triple[3]);
    }

    @Test
    void engineCallTripleHasMethodOnly() {
        final String[] triple = PyRpcDispatcherModule.readModEventTriple(
                PyRpcDispatcherModule.buildClientLoadAddonsFinished());

        assertEquals(1, triple.length);
        assertEquals(PyRpcDispatcherModule.CLIENT_LOAD_ADDONS_FINISHED, triple[0]);
    }

    @Test
    void unparsableEnvelopeYieldsNull() {
        assertFalse(PyRpcDispatcherModule.readModEventTriple(null) != null);
        assertFalse(PyRpcDispatcherModule.readModEventTriple(new byte[0]) != null);
        assertFalse(PyRpcDispatcherModule.readModEventTriple(new byte[]{(byte) 0x91}) != null);
    }
}
