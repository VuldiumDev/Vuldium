package net.minecraft.client.renderer.oit;

import com.mojang.blaze3d.pipeline.RenderPipeline;

public class OitPipelineSet {
    public RenderPipeline getPipeline(OitStage stage) {
        return null;
    }

    public static Builder builder(String name, RenderPipeline.Builder pipelineBuilder) {
        return new Builder();
    }

    public static class Builder {
        public Builder withAccumulateModifier(java.util.function.Consumer<RenderPipeline.Builder> modifier) {
            return this;
        }

        public OitPipelineSet build() {
            return new OitPipelineSet();
        }
    }
}
