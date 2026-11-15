package io.casehub.neocortex.cognition.coping;

import io.casehub.neocortex.cognition.drive.DriveAxis;

public record GoalOperation(
    GoalOperationType type,
    String goalName,
    String goalDescription,
    DriveAxis axis,
    String formationReason
) {}
