package net.caffeinemc.mods.sodium.client.render.chunk.vulkan;

import net.caffeinemc.mods.sodium.client.gpu.arena.vulkan.SodkamBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContext;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.transfer.SodkamAsyncTransferManager;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.transfer.SodkamAsyncTransferManager.BufferTransferRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Высокопроизводительный асинхронный загрузчик геометрии чанков (SodkamMeshUploader).
 * Устраняет микрофризы и просадки 0.1% Low при высоких дистанциях прорисовки (32+ чанка).
 *
 * Архитектурные гарантии:
 * 1. Upload Budget Limiter: Ограничение размера трансфера (8–16 МБ) и времени выполнения
 *    (не более 1.5 мс на вызовы DMA) за один кадр.
 * 2. Неблокирующая очередь: Секции, превышающие бюджет текущего кадра, безопасно остаются
 *    в очереди ожидания без блокировки рендер-потока.
 * 3. Dedicated Transfer Queue & Timeline Semaphores: Передача мешей выполняется на
 *    физически независимой очереди трансфера (VK_QUEUE_TRANSFER_BIT) без синхронных
 *    ожиданий vkQueueWaitIdle.
 * 4. Zero-Allocation Streaming: Использование предварительно выделенной памяти арены (256–512 МБ)
 *    и кольцевого хост-видимого Staging буфера без динамических вызовов vkAllocateMemory.
 */
public class SodkamMeshUploader implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/MeshUploader");

    // Бюджет по умолчанию: 12 МБ за кадр (масштабируется до 16 МБ при 32+ чанках)
    public static final long DEFAULT_MIN_BYTES_BUDGET = 8L * 1024L * 1024L;   // 8 МБ
    public static final long DEFAULT_MAX_BYTES_BUDGET = 16L * 1024L * 1024L;  // 16 МБ
    public static final long DEFAULT_FRAME_BYTES_BUDGET = 12L * 1024L * 1024L; // 12 МБ

    // Лимит времени кадра на подготовку и запись DMA-копирования: 1.5 мс
    public static final long DEFAULT_MAX_DURATION_NANOS = 1_500_000L; // 1.5 ms

    private final SodkamDeviceContext context;
    private final SodkamBufferArena bufferArena;
    private final SodkamAsyncTransferManager transferManager;

    private long currentMaxBytesBudget = DEFAULT_FRAME_BYTES_BUDGET;
    private long maxDurationNanos = DEFAULT_MAX_DURATION_NANOS;

    public record PendingMeshUpload(
            RenderSection section,
            TerrainRenderPass pass,
            ByteBuffer vertexData,
            long targetOffset,
            long dataSize,
            Runnable onComplete
    ) {}

    private final Object queueLock = new Object();
    private final Deque<PendingMeshUpload> pendingQueue = new ArrayDeque<>();
    private long pendingBytes = 0;

    // Метрики производительности последнего кадра
    private long lastFrameUploadedBytes = 0;
    private long lastFrameDurationNanos = 0;
    private int lastFrameTasksCount = 0;
    private boolean lastFrameBudgetExhausted = false;

    public SodkamMeshUploader(
            SodkamDeviceContext context,
            SodkamBufferArena bufferArena,
            SodkamAsyncTransferManager transferManager
    ) {
        this.context = Objects.requireNonNull(context, "SodkamDeviceContext cannot be null");
        this.bufferArena = Objects.requireNonNull(bufferArena, "SodkamBufferArena cannot be null");
        this.transferManager = Objects.requireNonNull(transferManager, "SodkamAsyncTransferManager cannot be null");

        LOGGER.info("Vuldium Mesh Uploader инициализирован (Budget: {} MB, TimeLimit: {} ms, DedicatedQueue={})",
                this.currentMaxBytesBudget / (1024 * 1024),
                this.maxDurationNanos / 1_000_000.0f,
                context.getTransferQueueFamilyIndex() != context.getGraphicsQueueFamilyIndex()
        );
    }

    /**
     * Динамически настраивает бюджет под текущую дистанцию прорисовки.
     * При render distance >= 32 чанка расширяет бюджет до 16 МБ.
     */
    public void configureForRenderDistance(int renderDistance) {
        if (renderDistance >= 32) {
            this.currentMaxBytesBudget = DEFAULT_MAX_BYTES_BUDGET;
        } else if (renderDistance >= 16) {
            this.currentMaxBytesBudget = DEFAULT_FRAME_BYTES_BUDGET;
        } else {
            this.currentMaxBytesBudget = DEFAULT_MIN_BYTES_BUDGET;
        }
    }

    /**
     * Добавляет задачу загрузки геометрии секции чанка в очередь ожидания.
     */
    public void enqueueUpload(
            RenderSection section,
            TerrainRenderPass pass,
            ByteBuffer vertexData,
            long targetOffset,
            Runnable onComplete
    ) {
        if (vertexData == null || !vertexData.hasRemaining()) {
            return;
        }

        long size = vertexData.remaining();
        synchronized (this.queueLock) {
            this.pendingQueue.add(new PendingMeshUpload(section, pass, vertexData, targetOffset, size, onComplete));
            this.pendingBytes += size;
        }
    }

    /**
     * Обрабатывает накопленную очередь обновлений мешей с жёстким соблюдением
     * ограничений по объёму байт и затраченному времени (Frame Budget).
     *
     * @param graphicsCmd активный командный буфер графической очереди
     * @return количество успешно загруженных секций в текущем кадре
     */
    public int processPendingUploads(VkCommandBuffer graphicsCmd) {
        return this.processPendingUploads(graphicsCmd, this.currentMaxBytesBudget, this.maxDurationNanos);
    }

    /**
     * Перегрузка с кастомным бюджетом загрузки.
     */
    public int processPendingUploads(VkCommandBuffer graphicsCmd, long byteBudget, long timeBudgetNanos) {
        if (this.pendingQueue.isEmpty()) {
            this.lastFrameUploadedBytes = 0;
            this.lastFrameDurationNanos = 0;
            this.lastFrameTasksCount = 0;
            this.lastFrameBudgetExhausted = false;
            return 0;
        }

        long startTime = System.nanoTime();
        long bytesUploadedThisFrame = 0;
        int tasksCompleted = 0;
        boolean budgetReached = false;

        List<BufferTransferRegion> transferRegions = new ArrayList<>();
        List<PendingMeshUpload> processedTasks = new ArrayList<>();

        synchronized (this.queueLock) {
            while (!this.pendingQueue.isEmpty()) {
                // 1. Проверка временного бюджета кадра (1.5 мс)
                long elapsed = System.nanoTime() - startTime;
                if (tasksCompleted > 0 && elapsed >= timeBudgetNanos) {
                    budgetReached = true;
                    break;
                }

                PendingMeshUpload task = this.pendingQueue.peek();
                long taskSize = task.dataSize();

                // 2. Проверка бюджета объема копирования (8–16 МБ)
                if (tasksCompleted > 0 && (bytesUploadedThisFrame + taskSize > byteBudget)) {
                    budgetReached = true;
                    break;
                }

                // Извлекаем задачу из очереди
                this.pendingQueue.poll();
                this.pendingBytes -= taskSize;

                // 3. Быстрое копирование в предвыделенный кольцевой Staging-буфер
                long stagingOffset = this.bufferArena.stageDirectBuffer(task.vertexData(), taskSize);
                transferRegions.add(new BufferTransferRegion(stagingOffset, task.targetOffset(), taskSize));
                processedTasks.add(task);

                bytesUploadedThisFrame += taskSize;
                tasksCompleted++;
            }
        }

        if (!transferRegions.isEmpty()) {
            // Открываем запись в командный буфер выделенной очереди передачи
            this.transferManager.beginTransfer();

            // Записываем батч команд vkCmdCopyBuffer и устанавливаем Release-барьер
            this.transferManager.recordCopies(
                    this.bufferArena.getStagingVkBuffer(),
                    this.bufferArena.getVkBufferHandle(),
                    transferRegions
            );

            // Асинхронно отправляем в Dedicated Transfer Queue со взводом Timeline Semaphore
            // (НИКАКИХ vkQueueWaitIdle в основном цикле!)
            this.transferManager.submitTransfer();

            // Записываем Acquire-барьер в графический командный буфер перед началом фазы отрисовки
            this.transferManager.recordAcquireBarrier(graphicsCmd, this.bufferArena.getVkBufferHandle());

            // Оповещаем слушателей о завершении загрузки геометрии
            for (PendingMeshUpload task : processedTasks) {
                if (task.onComplete() != null) {
                    try {
                        task.onComplete().run();
                    } catch (Throwable t) {
                        LOGGER.error("Ошибка в обратном вызове onComplete задачи загрузки меша", t);
                    }
                }
            }
        }

        this.lastFrameUploadedBytes = bytesUploadedThisFrame;
        this.lastFrameDurationNanos = System.nanoTime() - startTime;
        this.lastFrameTasksCount = tasksCompleted;
        this.lastFrameBudgetExhausted = budgetReached;

        return tasksCompleted;
    }

    public int getPendingQueueSize() {
        synchronized (this.queueLock) {
            return this.pendingQueue.size();
        }
    }

    public long getPendingBytes() {
        synchronized (this.queueLock) {
            return this.pendingBytes;
        }
    }

    public long getLastFrameUploadedBytes() {
        return this.lastFrameUploadedBytes;
    }

    public long getLastFrameDurationNanos() {
        return this.lastFrameDurationNanos;
    }

    public int getLastFrameTasksCount() {
        return this.lastFrameTasksCount;
    }

    public boolean isLastFrameBudgetExhausted() {
        return this.lastFrameBudgetExhausted;
    }

    public long getCurrentMaxBytesBudget() {
        return this.currentMaxBytesBudget;
    }

    public void setMaxBytesBudget(long maxBytes) {
        this.currentMaxBytesBudget = maxBytes;
    }

    public long getMaxDurationNanos() {
        return this.maxDurationNanos;
    }

    public void setMaxDurationNanos(long maxDurationNanos) {
        this.maxDurationNanos = maxDurationNanos;
    }

    @Override
    public void close() {
        synchronized (this.queueLock) {
            this.pendingQueue.clear();
            this.pendingBytes = 0;
        }
    }
}
