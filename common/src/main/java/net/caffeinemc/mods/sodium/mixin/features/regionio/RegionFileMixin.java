package net.caffeinemc.mods.sodium.mixin.features.regionio;

import net.caffeinemc.mods.sodium.client.systems.regionio.SodkamRegionFileManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

@Mixin(RegionFile.class)
public abstract class RegionFileMixin {
    @Shadow @Final private FileChannel file;
    @Shadow @Final private Path path;

    @Shadow protected abstract int getOffset(ChunkPos pos);
    @Shadow private static int getSectorNumber(int offset) { throw new AssertionError(); }
    @Shadow private static int getNumSectors(int offset) { throw new AssertionError(); }
    @Shadow protected abstract DataInputStream createExternalChunkInputStream(ChunkPos pos, byte version) throws IOException;

    @Inject(method = "getChunkDataInputStream", at = @At("HEAD"), cancellable = true)
    private void onGetChunkDataInputStream(ChunkPos pos, CallbackInfoReturnable<DataInputStream> cir) {
        int offset = this.getOffset(pos);
        if (offset == 0) {
            cir.setReturnValue(null);
            return;
        }

        int sectorNumber = getSectorNumber(offset);
        int numSectors = getNumSectors(offset);

        DataInputStream mappedStream = SodkamRegionFileManager.readMappedChunkStream(
                this.path,
                this.file,
                sectorNumber,
                numSectors,
                pos,
                this::createExternalChunkInputStream
        );

        if (mappedStream != null) {
            cir.setReturnValue(mappedStream);
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        SodkamRegionFileManager.onRegionClosed(this.path);
    }

    @Inject(method = "flush", at = @At("HEAD"))
    private void onFlush(CallbackInfo ci) {
        SodkamRegionFileManager.onRegionFlushed(this.path);
    }
}
