package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.sorting;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Высокопроизводительный алгоритм сортировки полупрозрачных квадов (Back-to-Front Quad Sorting).
 *
 * Архитектурные принципы:
 * 1. Вершинная геометрия в VRAM неизменна. Сортировке подвергается исключительно индексный буфер (Index Buffer).
 * 2. Каждый квад кодируется 6 индексами треугольников: (i0, i1, i2, i2, i3, i0).
 * 3. От дальних к ближним (Back-to-Front): квады сортируются по убыванию расстояния до камеры.
 * 4. Zero-allocation во время рендера: переиспользование примитивных массивов и прямого ByteBuffer.
 * 5. Дискретный порог пересчёта (Threshold Caching): предотвращает бессмысленную сортировку на CPU,
 *    если смещение камеры не превышает заданный порог.
 */
public class SodkamTranslucentSorter implements AutoCloseable {
    public static final int INDICES_PER_QUAD = 6;
    public static final int BYTES_PER_QUAD = INDICES_PER_QUAD * Integer.BYTES; // 24 байта
    private static final float RESORT_DISTANCE_THRESHOLD_SQ = 0.0625f; // 0.25 блока смещения

    private final int quadCount;
    // Центры квадов относительно начала секции: [cx0, cy0, cz0, cx1, cy1, cz1, ...]
    private final float[] quadCenters;

    // Массивы для сортировки без боксинга
    private final int[] sortedQuadIndices;
    private final long[] sortKeys; // 32 бита: расстояние (floatToIntBits), 32 бита: индекс квада

    // Выходной прямой нативный буфер индексов для передачи в Staging Buffer Vulkan
    private ByteBuffer indexBuffer;
    private IntBuffer indexIntBuffer;

    // Кеш позиции последней сортировки
    private double lastSortX = Double.NaN;
    private double lastSortY = Double.NaN;
    private double lastSortZ = Double.NaN;
    private boolean isClosed = false;

    public SodkamTranslucentSorter(int quadCount, float[] quadCenters) {
        if (quadCenters.length < quadCount * 3) {
            throw new IllegalArgumentException("Массив центров квадов меньше необходимого размера: " + quadCenters.length);
        }

        this.quadCount = quadCount;
        this.quadCenters = quadCenters;
        this.sortedQuadIndices = new int[quadCount];
        this.sortKeys = new long[quadCount];

        int totalBytes = quadCount * BYTES_PER_QUAD;
        this.indexBuffer = MemoryUtil.memAlloc(totalBytes);
        this.indexIntBuffer = this.indexBuffer.asIntBuffer();

        // Начальная инициализация индексов
        for (int i = 0; i < quadCount; i++) {
            this.sortedQuadIndices[i] = i;
        }
    }

    /**
     * Проверяет, требуется ли повторный пересчёт индексов на основе перемещения камеры.
     */
    public boolean shouldResort(double camX, double camY, double camZ) {
        if (Double.isNaN(this.lastSortX)) {
            return true;
        }

        double dx = camX - this.lastSortX;
        double dy = camY - this.lastSortY;
        double dz = camZ - this.lastSortZ;
        return (dx * dx + dy * dy + dz * dz) >= RESORT_DISTANCE_THRESHOLD_SQ;
    }

    /**
     * Выполняет быструю сортировку квадов от дальних к ближним и заполняет нативный индексный буфер.
     *
     * @param camX координата камеры X относительно начала чанка
     * @param camY координата камеры Y относительно начала чанка
     * @param camZ координата камеры Z относительно начала чанка
     * @return готовый к загрузке в Vulkan Staging Buffer прямой ByteBuffer с индексами
     */
    public synchronized ByteBuffer sort(double camX, double camY, double camZ) {
        if (!this.shouldResort(camX, camY, camZ)) {
            this.indexBuffer.position(0);
            return this.indexBuffer;
        }

        float cX = (float) camX;
        float cY = (float) camY;
        float cZ = (float) camZ;

        // 1. Вычисление расстояний и упаковка в 64-битный ключ:
        // Старшие 32 бита: инвертированное расстояние для естественной сортировки по убыванию (Back-to-Front)
        // Младшие 32 бита: исходный индекс квада
        for (int i = 0; i < this.quadCount; i++) {
            int centerIdx = i * 3;
            float dx = this.quadCenters[centerIdx] - cX;
            float dy = this.quadCenters[centerIdx + 1] - cY;
            float dz = this.quadCenters[centerIdx + 2] - cZ;
            float distSq = dx * dx + dy * dy + dz * dz;

            // Преобразование float в лексикографически упорядочиваемый int
            int floatBits = Float.floatToIntBits(distSq);
            // Инвертируем биты, чтобы Arrays.sort сортировал от дальних к ближним
            int sortKey = floatBits >= 0 ? ~floatBits : floatBits ^ 0x7FFFFFFF;

            this.sortKeys[i] = (((long) sortKey) << 32) | (i & 0xFFFFFFFFL);
        }

        // 2. Сортировка примитивного массива long (Dual-Pivot Quicksort на CPU / 0 аллокаций)
        Arrays.sort(this.sortKeys);

        // 3. Генерация индексного буфера:
        // Для каждого квада q в порядке от дальних к ближним формируем 6 индексов:
        // v0, v1, v2, v2, v3, v0
        this.indexIntBuffer.clear();
        for (int i = 0; i < this.quadCount; i++) {
            int quadIdx = (int) (this.sortKeys[i] & 0xFFFFFFFFL);
            int baseVertex = quadIdx * 4;

            this.indexIntBuffer.put(baseVertex);
            this.indexIntBuffer.put(baseVertex + 1);
            this.indexIntBuffer.put(baseVertex + 2);
            this.indexIntBuffer.put(baseVertex + 2);
            this.indexIntBuffer.put(baseVertex + 3);
            this.indexIntBuffer.put(baseVertex);
        }

        this.lastSortX = camX;
        this.lastSortY = camY;
        this.lastSortZ = camZ;

        this.indexBuffer.position(0);
        this.indexBuffer.limit(this.quadCount * BYTES_PER_QUAD);
        return this.indexBuffer;
    }

    public synchronized ByteBuffer sortAndUpload(double camX, double camY, double camZ) {
        return this.sort(camX, camY, camZ);
    }

    public int getQuadCount() {
        return this.quadCount;
    }

    public int getIndexCount() {
        return this.quadCount * INDICES_PER_QUAD;
    }

    public ByteBuffer getIndexBuffer() {
        return this.indexBuffer;
    }

    @Override
    public synchronized void close() {
        if (!this.isClosed) {
            if (this.indexBuffer != null) {
                MemoryUtil.memFree(this.indexBuffer);
                this.indexBuffer = null;
                this.indexIntBuffer = null;
            }
            this.isClosed = true;
        }
    }
}
