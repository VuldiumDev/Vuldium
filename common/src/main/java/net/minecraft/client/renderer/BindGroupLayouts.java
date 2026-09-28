package net.minecraft.client.renderer;

import com.mojang.blaze3d.pipeline.BindGroupLayout;

public class BindGroupLayouts {
    public static final BindGroupLayout IN_SAMPLER = BindGroupLayout.builder().withSampler("InSampler").build();
}
