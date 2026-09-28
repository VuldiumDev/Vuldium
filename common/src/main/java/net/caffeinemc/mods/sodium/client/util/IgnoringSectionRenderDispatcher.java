package net.caffeinemc.mods.sodium.client.util;

import net.minecraft.TracingExecutor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class IgnoringSectionRenderDispatcher extends SectionRenderDispatcher {
    public IgnoringSectionRenderDispatcher(ClientLevel level, LevelRenderer renderer, TracingExecutor executor, RenderBuffers renderBuffers, SectionCompiler sectionCompiler) {
        super(level, renderer, executor, renderBuffers, sectionCompiler);
        super.dispose();
    }

    @Override
    public void setLevel(ClientLevel level, SectionCompiler sectionCompiler) {

    }

    @Override
    public void setCameraPosition(Vec3 cameraPosition) {

    }

    @Override
    public @Nullable RenderSectionBufferSlice getRenderSectionSlice(SectionMesh sectionMesh, ChunkSectionLayer layer) {
        return null;
    }

    @Override
    public void lock() {

    }

    @Override
    public void unlock() {

    }

    @Override
    public void uploadGlobalGeomBuffersToGPU() {
    }

    @Override
    public void clearCompileQueue() {
    }

    @Override
    public boolean isQueueEmpty() {
        return true;
    }

    @Override
    public void dispose() {

    }

    @Override
    public String getStats() {
        return "None";
    }

    @Override
    public int getCompileQueueSize() {
        return 0;
    }

    @Override
    public int getFreeBufferCount() {
        return 0;
    }
}
