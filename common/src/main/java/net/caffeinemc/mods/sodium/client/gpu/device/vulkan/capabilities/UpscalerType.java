package net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities;

/**
 * Тип доступного алгоритма суперсэмплинга и реконструкции изображения.
 */
public enum UpscalerType {
    NONE("Off"),
    DLSS("DLSS"),
    FSR("FSR 2/3"),
    XESS("XeSS"),
    FSR_GENERIC("Universal");

    private final String displayName;

    UpscalerType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }
}
