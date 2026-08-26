package io.casehub.neocortex.mindmap;

public class MindMapCapabilityException extends RuntimeException {

    private final MindMapCapability capability;

    public MindMapCapabilityException(MindMapCapability capability) {
        super("Capability not supported: " + capability);
        this.capability = capability;
    }

    public MindMapCapability capability() {
        return capability;
    }
}
