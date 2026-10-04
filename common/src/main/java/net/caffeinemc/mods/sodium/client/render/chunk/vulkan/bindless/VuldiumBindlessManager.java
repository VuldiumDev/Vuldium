package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.bindless;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VuldiumDeviceContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBindingFlagsCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetVariableDescriptorCountAllocateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Аппаратный Bindless Descriptor Indexing менеджер (VK_EXT_descriptor_indexing / Vulkan 1.2+).
 * Полностью устраняет переключение дескрипторных наборов при рендеринге воксельных мешей,
 * объединяя все текстурные атласы, оверлеи, лайтмапы и оффскрин-буферы в единый
 * глобальный массив дескрипторов:
 *
 * layout(set = 0, binding = 0) uniform sampler2D u_Textures[];
 */
public class VuldiumBindlessManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/Bindless");

    public static final int MAX_BINDLESS_TEXTURES = 4096;

    private final VuldiumDeviceContext context;
    private final VkDevice device;
    private final boolean enabled;

    private long descriptorPool = VK10.VK_NULL_HANDLE;
    private long descriptorSetLayout = VK10.VK_NULL_HANDLE;
    private long descriptorSet = VK10.VK_NULL_HANDLE;

    private final AtomicInteger nextTextureIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<Long, Integer> textureIndexMap = new ConcurrentHashMap<>();

    private boolean isClosed = false;

    public VuldiumBindlessManager(VuldiumDeviceContext context) {
        this.context = context;
        this.device = context.getLogicalDevice();

        net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.VuldiumDeviceCapabilities caps =
                new net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.VuldiumDeviceCapabilities(context);
        boolean canUseBindless = caps.isDescriptorIndexingSupported() && caps.isPartiallyBoundSupported();

        this.enabled = canUseBindless;

        if (this.enabled) {
            this.initBindlessResources();
            LOGGER.info("Vuldium Bindless Pipeline успешно инициализирован (Капасити: {} слотов u_Textures[]).", MAX_BINDLESS_TEXTURES);
        } else {
            LOGGER.info("Vuldium Bindless Pipeline не поддерживается GPU/драйвером, используется стандартный биндинг.");
        }
    }

    private void initBindlessResources() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 1. Создание Bindless Layout Binding: Binding 0, SAMPLER2D Array
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(MAX_BINDLESS_TEXTURES)
                    .stageFlags(VK10.VK_SHADER_STAGE_ALL_GRAPHICS | VK10.VK_SHADER_STAGE_COMPUTE_BIT);

            // Флаги привязки: PARTIALLY_BOUND (не все 4096 слотов обязаны быть заполнены)
            // UPDATE_AFTER_BIND (обновление дескрипторов на лету без блокировки GPU)
            IntBuffer bindingFlags = stack.ints(
                    VK12.VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT |
                    VK12.VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT |
                    VK12.VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT
            );

            VkDescriptorSetLayoutBindingFlagsCreateInfo flagsInfo = VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_BINDING_FLAGS_CREATE_INFO)
                    .pBindingFlags(bindingFlags);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pNext(flagsInfo.address())
                    .flags(VK12.VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT)
                    .pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            int res = VK10.vkCreateDescriptorSetLayout(this.device, layoutInfo, null, pLayout);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания Bindless VkDescriptorSetLayout: " + res);
            }
            this.descriptorSetLayout = pLayout.get(0);

            // 2. Создание Bindless Descriptor Pool с UPDATE_AFTER_BIND
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0)
                    .type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(MAX_BINDLESS_TEXTURES);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK12.VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT)
                    .maxSets(1)
                    .pPoolSizes(poolSizes);

            LongBuffer pPool = stack.mallocLong(1);
            res = VK10.vkCreateDescriptorPool(this.device, poolInfo, null, pPool);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка создания Bindless VkDescriptorPool: " + res);
            }
            this.descriptorPool = pPool.get(0);

            // 3. Выделение дескрипторного сета с переменным количеством
            IntBuffer variableCounts = stack.ints(MAX_BINDLESS_TEXTURES);
            VkDescriptorSetVariableDescriptorCountAllocateInfo variableCountInfo =
                    VkDescriptorSetVariableDescriptorCountAllocateInfo.calloc(stack)
                            .sType(VK12.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_VARIABLE_DESCRIPTOR_COUNT_ALLOCATE_INFO)
                            .pDescriptorCounts(variableCounts);

            LongBuffer pSetLayouts = stack.longs(this.descriptorSetLayout);
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .pNext(variableCountInfo.address())
                    .descriptorPool(this.descriptorPool)
                    .pSetLayouts(pSetLayouts);

            LongBuffer pSet = stack.mallocLong(1);
            res = VK10.vkAllocateDescriptorSets(this.device, allocInfo, pSet);
            if (res != VK10.VK_SUCCESS) {
                throw new IllegalStateException("Ошибка выделения Bindless DescriptorSet: " + res);
            }
            this.descriptorSet = pSet.get(0);
        }
    }

    /**
     * Регистрирует пару ImageView + Sampler в глобальный массив u_Textures[].
     * Возвращает уникальный индекс текстуры (textureIndex), передаваемый в вершинные или шейдерные данные.
     */
    public int registerTexture(long imageView, long sampler) {
        if (!this.enabled || imageView == VK10.VK_NULL_HANDLE || sampler == VK10.VK_NULL_HANDLE) {
            return 0;
        }

        long key = imageView ^ (sampler << 16);
        return this.textureIndexMap.computeIfAbsent(key, k -> {
            int slot = this.nextTextureIndex.getAndIncrement();
            if (slot >= MAX_BINDLESS_TEXTURES) {
                LOGGER.warn("Достигнут лимит Bindless дескрипторов ({}). Повторное использование слота 0.", MAX_BINDLESS_TEXTURES);
                return 0;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .sampler(sampler)
                        .imageView(imageView)
                        .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

                VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack)
                        .sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(this.descriptorSet)
                        .dstBinding(0)
                        .dstArrayElement(slot)
                        .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(imageInfo);

                VK10.vkUpdateDescriptorSets(this.device, write, null);
            }

            return slot;
        });
    }

    /**
     * Привязывает глобальный Bindless Descriptor Set один раз на кадр/проход.
     */
    public void bind(VkCommandBuffer cmd, int pipelineBindPoint, long pipelineLayout, int setIndex) {
        if (!this.enabled || cmd == null || this.descriptorSet == VK10.VK_NULL_HANDLE) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSets = stack.longs(this.descriptorSet);
            VK10.vkCmdBindDescriptorSets(cmd, pipelineBindPoint, pipelineLayout, setIndex, pSets, null);
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public long getDescriptorSetLayout() {
        return this.descriptorSetLayout;
    }

    public long getDescriptorSet() {
        return this.descriptorSet;
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        if (this.descriptorPool != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorPool(this.device, this.descriptorPool, null);
            this.descriptorPool = VK10.VK_NULL_HANDLE;
        }

        if (this.descriptorSetLayout != VK10.VK_NULL_HANDLE) {
            VK10.vkDestroyDescriptorSetLayout(this.device, this.descriptorSetLayout, null);
            this.descriptorSetLayout = VK10.VK_NULL_HANDLE;
        }

        this.textureIndexMap.clear();
        this.isClosed = true;
    }
}
