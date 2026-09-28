package com.mojang.blaze3d.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility shim for MC 26.1, where BindGroupLayout did not exist.
 * In MC 26.2+, the real com.mojang.blaze3d.pipeline.BindGroupLayout is used.
 *
 * This shim collects samplers and uniforms so that version-specific
 * code can apply them to the RenderPipeline.Builder appropriately.
 */
public class BindGroupLayout {
    public final List<String> samplers;
    public final List<UniformEntry> uniforms;

    private BindGroupLayout(List<String> samplers, List<UniformEntry> uniforms) {
        this.samplers = samplers;
        this.uniforms = uniforms;
    }

    public static Builder builder() {
        return new Builder();
    }

    public record UniformEntry(String name, Object type, Object format) {
        public UniformEntry(String name, Object type) {
            this(name, type, null);
        }
    }

    public static class Builder {
        private final List<String> samplers = new ArrayList<>();
        private final List<UniformEntry> uniforms = new ArrayList<>();

        public Builder withSampler(String name) {
            this.samplers.add(name);
            return this;
        }

        public Builder withUniform(String name, Object type) {
            this.uniforms.add(new UniformEntry(name, type));
            return this;
        }

        public Builder withUniform(String name, Object type, Object format) {
            this.uniforms.add(new UniformEntry(name, type, format));
            return this;
        }

        public BindGroupLayout build() {
            return new BindGroupLayout(new ArrayList<>(this.samplers), new ArrayList<>(this.uniforms));
        }
    }
}
