package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale;

public enum FrameGenMode {
    OFF("1x (Off)", 1),
    X2("2x", 2),
    X3("3x", 3);

    private final String displayName;
    private final int multiplier;

    FrameGenMode(String displayName, int multiplier) {
        this.displayName = displayName;
        this.multiplier = multiplier;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public int getMultiplier() {
        return this.multiplier;
    }

    public boolean isEnabled() {
        return this != OFF;
    }
}
