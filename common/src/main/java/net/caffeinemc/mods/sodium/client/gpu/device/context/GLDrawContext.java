package net.caffeinemc.mods.sodium.client.gpu.device.context;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;

public class GLDrawContext extends DrawContext {
    @Override
    public void setContext(RenderPass pass, RenderPipeline pipeline) {
        this.pass = pass;
    }

    @Override
    public void pushConstants(float x, float y, float z, int currentTime, int regionId) {

    }

    @Override
    public void rotate() {

    }

    @Override
    public void delete() {

    }

    @Override
    public void endDraw() {

    }
}
