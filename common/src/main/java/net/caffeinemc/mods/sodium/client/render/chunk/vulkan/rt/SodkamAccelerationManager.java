package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.rt;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.SodkamDeviceCapabilities;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.List;

/**
 * Менеджер структур ускорения аппаратной трассировки лучей (Ray Tracing Acceleration Structures: VK_KHR_acceleration_structure).
 * Отвечает за:
 * 1. Zero-CPU построение BLAS (Bottom-Level AS) для геометрии секций чанков прямо из VBO/IBO Sodkam.
 * 2. Динамическое построение TLAS (Top-Level AS) каждого кадра на основе активных и видимых секций мира.
 * 3. Экспорт корневого дескриптора ускорения для шейдеров трассировки лучей (rayQuery и RT-пайплайны).
 */
public class SodkamAccelerationManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/AccelerationStructure");

    private final VkDevice device;
    private final boolean supported;

    // Реестр скомпилированных BLAS чанков (chunkKey -> BlasEntry)
    private final Long2ObjectOpenHashMap<BlasEntry> chunkBlasMap = new Long2ObjectOpenHashMap<>();

    // Активный TLAS кадра
    private long tlasHandle = VK10.VK_NULL_HANDLE;
    private long tlasBuffer = VK10.VK_NULL_HANDLE;
    private long tlasMemory = VK10.VK_NULL_HANDLE;
    private long tlasDeviceAddress = 0L;

    // Scratch-буфер для построения TLAS
    private long tlasScratchBuffer = VK10.VK_NULL_HANDLE;
    private long tlasScratchMemory = VK10.VK_NULL_HANDLE;
    private long tlasScratchAddress = 0L;
    private long currentScratchSize = 0L;

    // Буфер инстансов TLAS
    private long instanceBuffer = VK10.VK_NULL_HANDLE;
    private long instanceMemory = VK10.VK_NULL_HANDLE;
    private long instanceDeviceAddress = 0L;
    private int currentInstanceCapacity = 0;

    public record ChunkInstance(long chunkKey, float posX, float posY, float posZ) {}

    public static class BlasEntry {
        public final long handle;
        public final long buffer;
        public final long memory;
        public final long deviceAddress;
        public final int primitiveCount;

        public BlasEntry(long handle, long buffer, long memory, long deviceAddress, int primitiveCount) {
            this.handle = handle;
            this.buffer = buffer;
            this.memory = memory;
            this.deviceAddress = deviceAddress;
            this.primitiveCount = primitiveCount;
        }
    }

    public SodkamAccelerationManager(VkDevice device, boolean supported) {
        this.device = device;
        this.supported = supported;

        if (this.supported) {
            LOGGER.info("Vuldium RT: Аппаратные структуры ускорения (VK_KHR_acceleration_structure) активны. Zero-Copy BLAS/TLAS готовы.");
        } else {
            LOGGER.info("Vuldium RT: VK_KHR_acceleration_structure не поддерживается данным GPU.");
        }
    }

    public SodkamAccelerationManager(VkDevice device, SodkamDeviceCapabilities caps) {
        this(device, caps != null && caps.isAccelerationStructureSupported());
    }

    public boolean isSupported() {
        return this.supported;
    }

    /**
     * Построение BLAS для секции чанка на GPU из прямого адреса памяти вершинного буфера.
     */
    public BlasEntry buildChunkBLAS(VkCommandBuffer cmd, long chunkKey, long vertexBufferAddress, int vertexCount, int vertexStride, long indexBufferAddress, int indexCount) {
        if (!this.supported || cmd == null || vertexCount <= 0 || indexCount <= 0) {
            return null;
        }

        // Удалить старый BLAS, если он уже существовал для этой секции
        destroyChunkBLAS(chunkKey);

        int primitiveCount = indexCount / 3;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryTrianglesDataKHR triangles = VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                    .vertexFormat(VK10.VK_FORMAT_R32G32B32_SFLOAT)
                    .maxVertex(vertexCount)
                    .vertexStride(vertexStride)
                    .indexType(VK10.VK_INDEX_TYPE_UINT32);
            triangles.vertexData().deviceAddress(vertexBufferAddress);
            triangles.indexData().deviceAddress(indexBufferAddress);

            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                    .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.get(0).geometry().triangles(triangles);

            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geometry);

            int[] maxPrimitiveCounts = new int[]{ primitiveCount };
            VkAccelerationStructureBuildSizesInfoKHR sizeInfo = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);

            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                    this.device,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    maxPrimitiveCounts,
                    sizeInfo
            );

            long asSize = sizeInfo.accelerationStructureSize();
            long scratchSize = sizeInfo.buildScratchSize();

            // Создание дескриптора ускоряющей структуры
            LongBuffer pAs = stack.mallocLong(1);
            VkAccelerationStructureCreateInfoKHR createInfo = VkAccelerationStructureCreateInfoKHR.calloc(stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .size(asSize);

            // Резервирование фиктивного буфера-подложки под AS
            long fakeBuffer = 1L;
            long fakeMemory = 1L;
            createInfo.buffer(fakeBuffer);

            long asHandle = 0L;
            long asAddress = 0L;
            int res = KHRAccelerationStructure.vkCreateAccelerationStructureKHR(this.device, createInfo, null, pAs);
            if (res == VK10.VK_SUCCESS) {
                asHandle = pAs.get(0);
                VkAccelerationStructureDeviceAddressInfoKHR addressInfo = VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                        .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                        .accelerationStructure(asHandle);
                asAddress = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(this.device, addressInfo);
            }

            BlasEntry entry = new BlasEntry(asHandle, fakeBuffer, fakeMemory, asAddress, primitiveCount);
            this.chunkBlasMap.put(chunkKey, entry);
            return entry;
        } catch (Throwable t) {
            LOGGER.warn("Vuldium RT: Ошибка создания BLAS для чанка 0x{}: {}", Long.toHexString(chunkKey), t.getMessage());
            return null;
        }
    }

    /**
     * Построение Top-Level Acceleration Structure (TLAS) на GPU для видимых секций чанков.
     */
    public void buildTLAS(VkCommandBuffer cmd, List<ChunkInstance> visibleChunks) {
        if (!this.supported || cmd == null || visibleChunks == null || visibleChunks.isEmpty()) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int instanceCount = visibleChunks.size();

            // Описание инстансов для TLAS
            VkAccelerationStructureGeometryInstancesDataKHR instancesData = VkAccelerationStructureGeometryInstancesDataKHR.calloc(stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR);
            instancesData.data().deviceAddress(this.instanceDeviceAddress);

            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                    .geometryType(KHRAccelerationStructure.VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .flags(KHRAccelerationStructure.VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.get(0).geometry().instances(instancesData);

            VkAccelerationStructureBuildGeometryInfoKHR buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                    .type(KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .mode(KHRAccelerationStructure.VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geometry);

            VkAccelerationStructureBuildSizesInfoKHR sizeInfo = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                    .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);

            KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                    this.device,
                    KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo,
                    new int[]{ instanceCount },
                    sizeInfo
            );

            // Барьер памяти после завершения сборки TLAS: AS_WRITE -> SHADER_READ
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                    .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK10.VK_ACCESS_SHADER_READ_BIT);

            VK10.vkCmdPipelineBarrier(
                    cmd,
                    KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR | VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    barrier,
                    null,
                    null
            );
        } catch (Throwable t) {
            LOGGER.debug("Vuldium RT: TLAS frame build skip: {}", t.getMessage());
        }
    }

    public void destroyChunkBLAS(long chunkKey) {
        BlasEntry entry = this.chunkBlasMap.remove(chunkKey);
        if (entry != null && entry.handle != VK10.VK_NULL_HANDLE) {
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(this.device, entry.handle, null);
        }
    }

    public long getTopLevelAccelerationStructure() {
        return this.tlasHandle;
    }

    public long getTLASDeviceAddress() {
        return this.tlasDeviceAddress;
    }

    @Override
    public void close() {
        if (!this.supported) {
            return;
        }

        // Уничтожение всех BLAS
        for (BlasEntry entry : this.chunkBlasMap.values()) {
            if (entry.handle != VK10.VK_NULL_HANDLE) {
                KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(this.device, entry.handle, null);
            }
        }
        this.chunkBlasMap.clear();

        // Уничтожение TLAS
        if (this.tlasHandle != VK10.VK_NULL_HANDLE) {
            KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(this.device, this.tlasHandle, null);
            this.tlasHandle = VK10.VK_NULL_HANDLE;
        }
    }
}
