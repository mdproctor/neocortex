package io.casehub.neocortex.cognition.drive;

import org.jspecify.annotations.Nullable;

import java.util.Map;

public record ModulationLayer(Map<DriveAxis, Double> modulation, double strength, String source,
                               @Nullable Map<DriveAxis, String> descriptions) {
    public ModulationLayer(Map<DriveAxis, Double> modulation, double strength, String source) {
        this(modulation, strength, source, null);
    }

    public ModulationLayer {
        modulation = Map.copyOf(modulation);
        descriptions = descriptions != null ? Map.copyOf(descriptions) : null;
    }
}
