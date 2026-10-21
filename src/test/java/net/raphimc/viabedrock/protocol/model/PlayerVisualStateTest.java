package net.raphimc.viabedrock.protocol.model;

import com.viaversion.viaversion.libs.fastutil.ints.Int2IntOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerVisualStateTest {

    @Test
    void emitsInitialAndChangedValuesButDeduplicatesRepeats() {
        final PlayerVisualState state = new PlayerVisualState();

        assertEquals(Optional.of(false), state.updateCustomSpectator(properties(1, 0), 1));
        assertEquals(Optional.empty(), state.updateCustomSpectator(properties(1, 0), 1));
        assertEquals(Optional.of(true), state.updateCustomSpectator(properties(1, 1), 1));
        assertEquals(Optional.empty(), state.updateCustomSpectator(properties(2, 1), 1));
        assertEquals(Optional.of(false), state.updateCustomSpectator(properties(1, 0), 1));
    }

    private static EntityProperties properties(final int index, final int value) {
        final Int2IntOpenHashMap integers = new Int2IntOpenHashMap();
        integers.put(index, value);
        return new EntityProperties(integers, new Int2ObjectOpenHashMap<>());
    }
}
