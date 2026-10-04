package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.mesher;

import net.caffeinemc.mods.sodium.client.gpu.arena.vulkan.VuldiumBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Аппаратный Compute-Driven GPU Mesher (Vuldium).
 * Полностью устраняет CPU-мешинг чанков, передавая по шине PCIe только сжатые палитры
 * состояний блоков (4–8 КБ на субчанк вместо 100–400 КБ вершин).
 *
 * Делегирует выполнение вычислительных шейдеров и синхронизацию VK_KHR_synchronization2
 * классу {@link VuldiumVoxelComputeDispatcher}.
 */
public class VuldiumGpuMesher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/GpuMesher");

    public static final int SECTION_VOXELS = VuldiumVoxelComputeDispatcher.SECTION_VOXELS;
    public static final int PALETTE_BUFFER_SIZE = VuldiumVoxelComputeDispatcher.PALETTE_SECTION_BYTES;

    private final VuldiumDeviceContext context;
    private final VuldiumBufferArena bufferArena;
    private final VuldiumVoxelComputeDispatcher dispatcher;
    private boolean isClosed = false;

    public VuldiumGpuMesher(VuldiumDeviceContext context, VuldiumBufferArena bufferArena) {
        this.context = context;
        this.bufferArena = bufferArena;
        this.dispatcher = new VuldiumVoxelComputeDispatcher(context, bufferArena);

        LOGGER.info("Vuldium Compute-Driven GPU Mesher успешно инициализирован (16-байтный формат VuldiumPackedVertex).");
    }

    /**
     * Загружает сырую палитру блоков секции в отображенную Host-Visible память без GC аллокаций.
     */
    public void uploadRawPalette(int[] blockStates) {
        this.dispatcher.uploadPalette(0, blockStates);
    }

    /**
     * Диспетчеризирует аппаратную генерацию меша секции на GPU.
     */
    public void dispatchMeshing(VkCommandBuffer cmd, int sectionX, int sectionY, int sectionZ, int sectionIndex) {
        this.dispatcher.dispatchMeshingSingle(cmd, sectionX, sectionY, sectionZ, sectionIndex);
    }

    /**
     * Пакетная диспетчеризация вычислений меша нескольких секций.
     */
    public void dispatchMeshingBatch(VkCommandBuffer cmd, int sectionCount) {
        this.dispatcher.dispatchMeshing(cmd, sectionCount);
    }

    public VuldiumVoxelComputeDispatcher getDispatcher() {
        return this.dispatcher;
    }

    public long getCounterBuffer() {
        return this.dispatcher.getCounterBuffer();
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        this.dispatcher.close();
        this.isClosed = true;

        LOGGER.info("Vuldium Compute-Driven GPU Mesher успешно закрыт.");
    }
}
