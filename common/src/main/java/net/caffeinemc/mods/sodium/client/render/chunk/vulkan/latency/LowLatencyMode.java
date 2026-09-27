package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.latency;

public enum LowLatencyMode {
    OFF("Off"),
    ON("On (Anti-Lag)"),
    ON_BOOST("On + Boost");

    private final String displayName;

    LowLatencyMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public boolean isEnabled() {
        return this != OFF;
    }

    public boolean isBoost() {
        return this == ON_BOOST;
    }
}
