package net.caffeinemc.mods.sodium.fabric.model;

import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.List;

public class FabricMutableProvider implements ColorProvider<BlockState> {
    @Override
    public void getColors(LevelSlice slice, BlockPos pos, BlockPos.MutableBlockPos scratchPos, BlockState state, ModelQuadView quad, int[] output, boolean smooth) {
        int tintIndex = quad.getTintIndex();

        List<BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(state);

        if (sources == null || sources.isEmpty()) {
            Arrays.fill(output, 0xFFFFFFFF);
            return;
        }

        if (tintIndex < 0 || tintIndex >= sources.size()) {
            Arrays.fill(output, 0xFFFFFFFF);
            return;
        }

        int color = sources.get(tintIndex).colorInWorld(state, slice, pos);
        Arrays.fill(output, color | 0xFF000000);
    }
}
