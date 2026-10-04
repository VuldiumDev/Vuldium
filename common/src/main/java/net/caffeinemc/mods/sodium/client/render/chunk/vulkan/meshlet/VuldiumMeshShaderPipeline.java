package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.meshlet;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.VuldiumDeviceCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Конвейер геометрических шейдеров следующего поколения (Task & Mesh Shaders: VK_EXT_mesh_shader).
 * Заменяет традиционную вершинную сборку и примитивный ассемблер аппаратными кластерами геометрии (Meshlets),
 * выполняя микрокластерное отсечение по фрустуму и ориентации конуса нормалей на уровне Task-шейдеров.
 */
public class VuldiumMeshShaderPipeline implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/MeshShaders");

    public static final int MAX_MESHLET_VERTICES = 64;
    public static final int MAX_MESHLET_TRIANGLES = 126;

    private final VkDevice device;
    private final boolean supported;
    private boolean taskShaderSupported = false;
    private boolean meshShaderSupported = false;
    private int maxMeshOutputVertices = 0;
    private int maxMeshOutputPrimitives = 0;
    private int maxTaskWorkGroupTotalCount = 0;

    private boolean enabled = false;
    private long pipeline = VK10.VK_NULL_HANDLE;
    private long pipelineLayout = VK10.VK_NULL_HANDLE;

    /**
     * Описание мешлета для кластеризации геометрии чанка (16 байт выравнивания).
     */
    public record Meshlet(
            float centerX, float centerY, float centerZ, float radius,
            float coneAxisX, float coneAxisY, float coneAxisZ, float coneCutoff,
            int vertexOffset, int indexOffset,
            int vertexCount, int triangleCount
    ) {}

    public VuldiumMeshShaderPipeline(VkDevice device, boolean supportsMeshShaders) {
        this(device, (device != null ? device.getPhysicalDevice() : null), supportsMeshShaders);
    }

    public VuldiumMeshShaderPipeline(VkDevice device, VkPhysicalDevice physicalDevice, boolean supportsMeshShaders) {
        this.device = device;
        this.supported = supportsMeshShaders;

        if (this.supported && physicalDevice != null) {
            this.queryHardwareProperties(physicalDevice);
        } else {
            LOGGER.info("Vuldium Meshlets: VK_EXT_mesh_shader не поддерживается (используется традиционный MultiDraw).");
        }
    }

    public VuldiumMeshShaderPipeline(VkDevice device, VkPhysicalDevice physicalDevice, VuldiumDeviceCapabilities caps) {
        this(device, physicalDevice, caps != null && caps.isMeshShadersSupported());
    }

    private void queryHardwareProperties(VkPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMeshShaderPropertiesEXT meshProps = VkPhysicalDeviceMeshShaderPropertiesEXT.calloc(stack)
                    .sType(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_PROPERTIES_EXT);

            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2)
                    .pNext(meshProps.address());

            VK11.vkGetPhysicalDeviceProperties2(physicalDevice, props2);

            this.maxMeshOutputVertices = meshProps.maxMeshOutputVertices();
            this.maxMeshOutputPrimitives = meshProps.maxMeshOutputPrimitives();
            this.maxTaskWorkGroupTotalCount = meshProps.maxTaskWorkGroupTotalCount();

            VkPhysicalDeviceMeshShaderFeaturesEXT meshFeatures = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                    .sType(EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT);

            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    .pNext(meshFeatures.address());

            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, features2);

            this.taskShaderSupported = meshFeatures.taskShader();
            this.meshShaderSupported = meshFeatures.meshShader();

            LOGGER.info("Vuldium Meshlets: Аппаратные Task & Mesh Shaders активны. Limits: maxVerts={}, maxPrims={}, maxTaskGroups={}, TaskStage={}, MeshStage={}",
                    this.maxMeshOutputVertices, this.maxMeshOutputPrimitives, this.maxTaskWorkGroupTotalCount,
                    this.taskShaderSupported, this.meshShaderSupported);
        } catch (Throwable t) {
            LOGGER.warn("Vuldium Meshlets: Ошибка чтения аппаратных свойств VK_EXT_mesh_shader: {}", t.getMessage());
        }
    }

    public boolean isSupported() {
        return this.supported && this.meshShaderSupported;
    }

    public boolean isTaskShaderSupported() {
        return this.taskShaderSupported;
    }

    public boolean isEnabled() {
        return this.enabled && this.isSupported();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean canFallbackToIndirect() {
        return !this.isEnabled();
    }

    public int getMaxMeshOutputVertices() {
        return this.maxMeshOutputVertices;
    }

    public int getMaxMeshOutputPrimitives() {
        return this.maxMeshOutputPrimitives;
    }

    /**
     * Прямой запуск меш-шейдеров.
     */
    public void drawMeshTasks(VkCommandBuffer cmd, int groupCountX, int groupCountY, int groupCountZ) {
        if (!this.isEnabled() || cmd == null) {
            return;
        }

        EXTMeshShader.vkCmdDrawMeshTasksEXT(cmd, groupCountX, groupCountY, groupCountZ);
    }

    /**
     * Косвенный запуск меш-шейдеров на GPU (GPU-driven cluster execution).
     */
    public void drawMeshTasksIndirect(VkCommandBuffer cmd, long indirectBuffer, long offset, int drawCount, int stride) {
        if (!this.isEnabled() || cmd == null || indirectBuffer == VK10.VK_NULL_HANDLE) {
            return;
        }

        EXTMeshShader.vkCmdDrawMeshTasksIndirectEXT(cmd, indirectBuffer, offset, drawCount, stride);
    }

    /**
     * Косвенный запуск со счетчиком из GPU-буфера.
     */
    public void drawMeshTasksIndirectCount(VkCommandBuffer cmd, long indirectBuffer, long offset, long countBuffer, long countBufferOffset, int maxDrawCount, int stride) {
        if (!this.isEnabled() || cmd == null || indirectBuffer == VK10.VK_NULL_HANDLE || countBuffer == VK10.VK_NULL_HANDLE) {
            return;
        }

        EXTMeshShader.vkCmdDrawMeshTasksIndirectCountEXT(cmd, indirectBuffer, offset, countBuffer, countBufferOffset, maxDrawCount, stride);
    }

    @Override
    public void close() {
        if (this.pipeline != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipeline(this.device, this.pipeline, null);
            this.pipeline = VK10.VK_NULL_HANDLE;
        }
        if (this.pipelineLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyPipelineLayout(this.device, this.pipelineLayout, null);
            this.pipelineLayout = VK10.VK_NULL_HANDLE;
        }
    }
}
