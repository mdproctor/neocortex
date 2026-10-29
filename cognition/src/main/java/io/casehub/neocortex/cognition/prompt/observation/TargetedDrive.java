package io.casehub.neocortex.cognition.prompt.observation;

public record TargetedDrive(
        DriveSnapshot drive,
        String targetAgentId,
        String targetDisplayName,
        String role
) {
    public static TargetedDrive untargeted(DriveSnapshot drive) {
        return new TargetedDrive(drive, null, null, null);
    }

    public boolean hasTarget() {
        return targetAgentId != null;
    }
}