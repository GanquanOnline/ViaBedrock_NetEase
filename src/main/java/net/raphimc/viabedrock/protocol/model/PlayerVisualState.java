package net.raphimc.viabedrock.protocol.model;

import java.util.Optional;

public final class PlayerVisualState {
    private Boolean customSpectator;

    public Optional<Boolean> updateCustomSpectator(final EntityProperties properties, final int propertyIndex) {
        if (propertyIndex < 0 || !properties.intProperties().containsKey(propertyIndex)) {
            return Optional.empty();
        }

        final boolean next = properties.intProperties().get(propertyIndex) != 0;
        if (this.customSpectator != null && this.customSpectator == next) {
            return Optional.empty();
        }
        this.customSpectator = next;
        return Optional.of(next);
    }
}
