package net.caffeinemc.mods.sodium.client.util;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

public class IgnoringViewArea extends ViewArea {
    private SectionPos ppos;

    public IgnoringViewArea(SectionRenderDispatcher sectionRenderDispatcher, Level level, int viewDistance, LevelRenderer levelRenderer) {
        super(sectionRenderDispatcher, level, viewDistance, levelRenderer);
    }

    @Override
    protected void createSections(SectionRenderDispatcher sectionRenderDispatcher) {
        this.sections = new SectionRenderDispatcher.RenderSection[0];
    }

    @Override
    public void releaseAllBuffers() {

    }

    @Override
    public void repositionCamera(SectionPos cameraSectionPos) {
        if (!cameraSectionPos.equals(this.ppos)) {
            this.ppos = cameraSectionPos;
        }
    }

    @Override
    public void setDirty(int sectionX, int sectionY, int sectionZ, boolean reRenderOnMainThread) {

    }
}
