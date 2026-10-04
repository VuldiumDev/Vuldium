package net.caffeinemc.mods.sodium.client.gpu.arena.vulkan;

import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;
import net.caffeinemc.mods.sodium.client.gpu.arena.PendingUpload;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

/**
 * Низкоуровневая арена памяти буферов Vulkan для хранения геометрии секций чанков.
 * Заменяет glBufferStorage и архитектуру OpenGL-арен Sodium.
 */
public interface VuldiumBufferArena extends AutoCloseable {

    /**
     * @return Нативный хэндл буфера геометрии на GPU (VkBuffer).
     */
    long getVkBufferHandle();

    /**
     * @return Выделенная память буфера на GPU (VkDeviceMemory).
     */
    long getVkDeviceMemory();

    /**
     * @return Общая ёмкость арены в байтах.
     */
    long getCapacity();

    /**
     * @return Текущий объём фактически используемой геометрии в байтах.
     */
    long getUsedBytes();

    /**
     * Выделяет сегмент геометрии внутри арены.
     *
     * @param sizeBytes требуемый размер сегмента
     * @return дескриптор сегмента с относительным смещением
     */
    BufferSegment allocate(long sizeBytes);

    /**
     * Освобождает ранее выделенный сегмент, возвращая память в пул субаллокатора.
     *
     * @param segment освобождаемый сегмент
     */
    void free(BufferSegment segment);

    /**
     * Помещает пакет обновлений геометрии в очередь хост-видимого Staging Buffer для DMA-отправки.
     *
     * @param uploadQueue список пакетов загрузки геометрии
     */
    void enqueueUploads(List<PendingUpload> uploadQueue);

    /**
     * Записывает команды DMA-копирования (vkCmdCopyBuffer) из Staging-буфера в Device-буфер
     * в переданный командный буфер перед началом фазы отрисовки геометрии.
     *
     * @param transferCmdBuf активный командный буфер фазы трансфера
     */
    void flushUploads(VkCommandBuffer transferCmdBuf);

    /**
     * Выполняет дефрагментацию и сжатие свободных сегментов арены.
     *
     * @param copyCmdBuf командный буфер для копирования перемещаемых блоков внутри VRAM
     */
    void defragment(VkCommandBuffer copyCmdBuf);

    /**
     * @return Нативный хэндл кольцевого Staging буфера в Host-Visible памяти (VkBuffer).
     */
    long getStagingVkBuffer();

    /**
     * @return Ёмкость кольцевого Staging буфера в байтах.
     */
    long getStagingCapacity();

    /**
     * Копирует данные из прямого байт-буфера в кольцевой Staging-буфер и возвращает смещение в нём.
     *
     * @param buffer прямой ByteBuffer с данными геометрии
     * @param bytes  количество байт для записи
     * @return относительное смещение внутри Staging буфера
     */
    long stageDirectBuffer(java.nio.ByteBuffer buffer, long bytes);

    /**
     * Освобождает VkBuffer, VkDeviceMemory и связанный Ring Staging Buffer.
     */
    @Override
    void close();
}
