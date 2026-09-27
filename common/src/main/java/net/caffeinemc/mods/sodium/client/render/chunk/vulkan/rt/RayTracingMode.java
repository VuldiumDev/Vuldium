package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.rt;

public enum RayTracingMode {
    OFF("Off"),
    RTAO("RTAO (Ambient Occlusion)"),
    RTAO_AND_SHADOWS("RTAO + Contact Shadows");

    private final String displayName;

    RayTracingMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public boolean isEnabled() {
        return this != OFF;
    }

    public boolean hasShadows() {
        return this == RTAO_AND_SHADOWS;
    }
}
