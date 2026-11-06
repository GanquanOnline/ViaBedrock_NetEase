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
package net.raphimc.viabedrock;

import net.raphimc.viabedrock.protocol.data.BedrockProtocolProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockProtocolGateTest {

    @Test
    void defaultsToTheBaselineProtocol(@TempDir final Path tempDir) {
        final CapturedLogs logs = new CapturedLogs();
        final ViaBedrockConfig config = new ViaBedrockConfig(tempDir.resolve("viabedrock.yml").toFile(), logs.logger());
        config.reload();

        assertEquals(975, config.getBedrockProtocolVersion());
        assertTrue(logs.hasInfoContaining("Bedrock wire: protocol 975"));
        assertFalse(logs.hasWarningContaining("no implemented layout data"));
    }

    @Test
    void autoResolvesToTheNewestProfile(@TempDir final Path tempDir) throws IOException {
        final CapturedLogs logs = new CapturedLogs();
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, "bedrock:\n  protocol-version: 0\n");

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), logs.logger());
        config.reload();

        assertEquals(BedrockProtocolProfile.newest().protocolVersion(), config.getBedrockProtocolVersion());
        assertFalse(logs.hasWarningContaining("no implemented layout data"));
    }

    @Test
    void unknownProtocolKeepsItsNumberAndWarns(@TempDir final Path tempDir) throws IOException {
        final CapturedLogs logs = new CapturedLogs();
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, "bedrock:\n  protocol-version: 1024\n");

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), logs.logger());
        config.reload();

        assertEquals(1024, config.getBedrockProtocolVersion());
        assertTrue(logs.hasWarningContaining("bedrock.protocol-version 1024 has no implemented layout data"));
        assertTrue(logs.hasInfoContaining("Bedrock wire: protocol 1024 (inferred"));
    }

    @Test
    void existingConfigWithoutTheSectionLandsOnTheBaseline(@TempDir final Path tempDir) throws IOException {
        final CapturedLogs logs = new CapturedLogs();
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, "language: \"\"\ndisable-server-blacklist: false\n");

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), logs.logger());
        config.reload();

        assertEquals(975, config.getBedrockProtocolVersion());
        assertTrue(Files.readString(configFile).contains("protocol-version"));
    }

    @Test
    void nonIntegerValuesAreRejected(@TempDir final Path tempDir) throws IOException {
        for (final String raw : List.of("\"1001\"", "abc", "975.6", "[975]", "99999999999")) {
            final CapturedLogs logs = new CapturedLogs();
            final Path configFile = tempDir.resolve("bad-" + Math.abs(raw.hashCode()) + ".yml");
            Files.writeString(configFile, "bedrock:\n  protocol-version: " + raw + "\n");

            final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), logs.logger());
            final IllegalStateException error = assertThrows(IllegalStateException.class, config::reload, raw);
            assertTrue(error.getMessage().contains("not an integer protocol number"), raw);
        }
    }

    @Test
    void integralFloatIsAccepted(@TempDir final Path tempDir) throws IOException {
        final CapturedLogs logs = new CapturedLogs();
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, "bedrock:\n  protocol-version: 975.0\n");

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), logs.logger());
        config.reload();

        assertEquals(975, config.getBedrockProtocolVersion());
    }

    @Test
    void negativeProtocolRefusesToStart(@TempDir final Path tempDir) throws IOException {
        final CapturedLogs logs = new CapturedLogs();
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, "bedrock:\n  protocol-version: -1\n");

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), logs.logger());
        final IllegalStateException error = assertThrows(IllegalStateException.class, config::reload);
        assertTrue(error.getMessage().contains("-1"));
        assertTrue(error.getMessage().contains("not a valid protocol number"));
    }

    private static final class CapturedLogs {

        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger;

        private CapturedLogs() {
            this.logger = Logger.getAnonymousLogger();
            this.logger.setLevel(Level.ALL);
            this.logger.setUseParentHandlers(false);
            final Handler handler = new Handler() {
                @Override
                public void publish(final LogRecord record) {
                    CapturedLogs.this.records.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            handler.setLevel(Level.ALL);
            this.logger.addHandler(handler);
        }

        private Logger logger() {
            return this.logger;
        }

        private boolean hasWarningContaining(final String text) {
            return this.hasRecord(Level.WARNING, text);
        }

        private boolean hasInfoContaining(final String text) {
            return this.hasRecord(Level.INFO, text);
        }

        private boolean hasRecord(final Level level, final String text) {
            for (final LogRecord record : this.records) {
                if (record.getLevel() == level && record.getMessage() != null && record.getMessage().contains(text)) {
                    return true;
                }
            }
            return false;
        }

    }

}
