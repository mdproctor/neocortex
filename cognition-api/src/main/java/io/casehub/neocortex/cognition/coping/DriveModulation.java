package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognition.drive.DriveAxis;

public record DriveModulation(
    DriveAxis axis,
    double intensityDelta,
    String reason
) {}
