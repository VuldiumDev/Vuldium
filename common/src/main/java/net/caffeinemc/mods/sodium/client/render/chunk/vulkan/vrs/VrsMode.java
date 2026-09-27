package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.vrs;

public enum VrsMode {
    OFF("Off"),
    STATIC_TIER1("Static (Tier 1)"),
    FOVEATED("Foveated (Tier 2 Center Focus)"),
    CONTENT_ADAPTIVE("Content-Adaptive (Tier 2)");

    private final String displayName;

    VrsMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public boolean isEnabled() {
        return this != OFF;
    }

    public boolean isTier2() {
        return this == FOVEATED || this == CONTENT_ADAPTIVE;
    }
}
