package net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities;

/**
 * Определение вендора графического процессора на основе стандартизированного vendorID Vulkan.
 */
public enum GpuVendor {
    NVIDIA(0x10DE, "NVIDIA Corporation", UpscalerType.DLSS),
    AMD(0x1002, "Advanced Micro Devices, Inc.", UpscalerType.FSR),
    INTEL(0x8086, "Intel Corporation", UpscalerType.XESS),
    ARM(0x13B5, "ARM Holdings", UpscalerType.FSR_GENERIC),
    QUALCOMM(0x5143, "Qualcomm", UpscalerType.FSR_GENERIC),
    UNKNOWN(0, "Unknown Vendor", UpscalerType.FSR_GENERIC);

    private final int vendorId;
    private final String vendorName;
    private final UpscalerType primaryUpscaler;

    GpuVendor(int vendorId, String vendorName, UpscalerType primaryUpscaler) {
        this.vendorId = vendorId;
        this.vendorName = vendorName;
        this.primaryUpscaler = primaryUpscaler;
    }

    public int getVendorId() {
        return this.vendorId;
    }

    public String getVendorName() {
        return this.vendorName;
    }

    public UpscalerType getPrimaryUpscaler() {
        return this.primaryUpscaler;
    }

    public static GpuVendor fromVendorId(int vendorId) {
        for (GpuVendor vendor : values()) {
            if (vendor.vendorId == vendorId) {
                return vendor;
            }
        }
        return UNKNOWN;
    }
}
