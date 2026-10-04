package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.scheduler;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Векторно-прогнозируемый планировщик очереди чанков (Velocity-Aware Chunk Scheduler).
 *
 * Устраняет проблему концентрической загрузки Sodium при полете на элитрах и быстром движении:
 * 1. Предиктивный фрустум ускорения: вычисляет вектор скорости игрока и вектор взгляда.
 * 2. Динамическая деформация сетки приоритетов по формуле:
 *    Priority = Distance - (V_norm · D_norm) * Delta_bias
 *    Чанки прямо по курсу движения получают наивысший Priority Class 0.
 * 3. Неблокирующая потокобезопасная очередь {@link PriorityBlockingQueue}.
 * 4. Адаптивный Bandwidth Throttler (лимит до 4.0 мс на кадр) для исключения задержек рендеринга.
 */
public class VuldiumVelocityScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/VelocityScheduler");

    // Бюджет пропускной способности: не более 3.0 мс на передачу данных в кадре
    public static final long MAX_FRAME_BUDGET_NANOS = 3_000_000L;

    // Временной горизонт упреждения полета (в секундах)
    private static final double LOOKAHEAD_TIME_SECONDS = 3.5;
    private static final double BASE_BIAS = 32.0;

    // Кинематические векторы игрока
    private final Vector3d playerPos = new Vector3d();
    private final Vector3d playerVelocity = new Vector3d();
    private final Vector3d velocityNorm = new Vector3d();
    private final Vector3d lookAngleNorm = new Vector3d();
    private final Vector3d effectiveMotionDir = new Vector3d();

    private double currentSpeed = 0.0;
    private double deltaBias = BASE_BIAS;
    private long lastUpdateTimeNanos = 0;

    /**
     * Элемент очереди задач загрузки/мешинга секций чанков.
     */
    public record ChunkTask(
            int chunkX,
            int chunkZ,
            int priorityClass, // 0 = Критический (курс полета), 1 = Периферия, 2 = Тыл
            double priority,   // Чем меньше значение, тем выше приоритет в очереди
            double distance,
            Runnable task
    ) implements Comparable<ChunkTask> {
        @Override
        public int compareTo(ChunkTask other) {
            return Double.compare(this.priority, other.priority);
        }
    }

    // Неблокирующая потокобезопасная приоритетная очередь
    private final PriorityBlockingQueue<ChunkTask> taskQueue = new PriorityBlockingQueue<>();

    // Мониторинг времени отправки в VRAM
    private final AtomicLong frameTimeSpentNanos = new AtomicLong(0);
    private volatile boolean isThrottled = false;

    public VuldiumVelocityScheduler() {
        LOGGER.info("Vuldium Velocity-Aware Chunk Scheduler инициализирован (Lookahead: {} s, Budget: 3.0 ms).", LOOKAHEAD_TIME_SECONDS);
    }

    /**
     * Обновляет параметры движения игрока на основе векторов позиции, скорости и направления взгляда.
     *
     * @param pos       текущая позиция игрока в мире
     * @param velocity  вектор скорости игрока (блоков/тик или блоков/сек)
     * @param lookAngle единичный вектор направления взгляда камеры
     */
    public void updatePlayerVelocity(Vec3 pos, Vec3 velocity, Vec3 lookAngle) {
        if (pos == null) {
            return;
        }

        double px = pos.x;
        double py = pos.y;
        double pz = pos.z;

        double vx = velocity != null ? velocity.x : 0.0;
        double vy = velocity != null ? velocity.y : 0.0;
        double vz = velocity != null ? velocity.z : 0.0;

        double lx = lookAngle != null ? lookAngle.x : 0.0;
        double ly = lookAngle != null ? lookAngle.y : 0.0;
        double lz = lookAngle != null ? lookAngle.z : 0.0;

        this.updateMotionInternal(px, py, pz, vx, vy, vz, lx, ly, lz);
    }

    /**
     * Перегрузка для совместимости с JOML-векторами.
     */
    public void updatePlayerMotion(Vector3dc currentPos) {
        long now = System.nanoTime();
        if (this.lastUpdateTimeNanos == 0) {
            this.playerPos.set(currentPos);
            this.lastUpdateTimeNanos = now;
            return;
        }

        double dt = (now - this.lastUpdateTimeNanos) / 1_000_000_000.0;
        if (dt > 0.001) {
            double vx = (currentPos.x() - this.playerPos.x) / dt;
            double vy = (currentPos.y() - this.playerPos.y) / dt;
            double vz = (currentPos.z() - this.playerPos.z) / dt;

            this.updateMotionInternal(currentPos.x(), currentPos.y(), currentPos.z(), vx, vy, vz, 0, 0, 0);
            this.lastUpdateTimeNanos = now;
        }
    }

    private synchronized void updateMotionInternal(double px, double py, double pz,
                                                  double vx, double vy, double vz,
                                                  double lx, double ly, double lz) {
        this.playerPos.set(px, py, pz);
        this.playerVelocity.set(vx, vy, vz);
        this.currentSpeed = Math.sqrt(vx * vx + vy * vy + vz * vz);

        // Нормализация вектора скорости
        if (this.currentSpeed > 0.01) {
            this.velocityNorm.set(vx / this.currentSpeed, vy / this.currentSpeed, vz / this.currentSpeed);
        } else {
            this.velocityNorm.set(0, 0, 0);
        }

        // Нормализация вектора взгляда
        double lookLen = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (lookLen > 0.001) {
            this.lookAngleNorm.set(lx / lookLen, ly / lookLen, lz / lookLen);
        } else {
            this.lookAngleNorm.set(0, 0, 0);
        }

        // Результирующий эффективный вектор направления: при высокой скорости доминирует скорость,
        // но направление взгляда направляет фокус внимания игрока
        if (this.currentSpeed > 1.0) {
            this.effectiveMotionDir.set(this.velocityNorm).mul(0.75).add(this.lookAngleNorm.x * 0.25, this.lookAngleNorm.y * 0.25, this.lookAngleNorm.z * 0.25);
            double effLen = this.effectiveMotionDir.length();
            if (effLen > 0.001) {
                this.effectiveMotionDir.div(effLen);
            }
        } else if (lookLen > 0.001) {
            this.effectiveMotionDir.set(this.lookAngleNorm);
        } else {
            this.effectiveMotionDir.set(0, 0, 0);
        }

        // Расчет смещения упреждения: Delta_bias = Speed * LookaheadTime * Scaling
        this.deltaBias = Math.max(BASE_BIAS, this.currentSpeed * LOOKAHEAD_TIME_SECONDS * 16.0);
    }

    /**
     * Оценивает приоритет чанка по формуле предиктивного конуса:
     * Priority = Distance - (V_norm · D_norm) * Delta_bias
     *
     * @param chunkX мировая X координата чанка
     * @param chunkZ мировая Z координата чанка
     * @param task   действие загрузки/мешинга
     * @return сформированная задача с вычисленным приоритетом
     */
    public ChunkTask evaluateChunk(int chunkX, int chunkZ, Runnable task) {
        double worldX = chunkX * 16.0 + 8.0;
        double worldZ = chunkZ * 16.0 + 8.0;

        double dx = worldX - this.playerPos.x;
        double dz = worldZ - this.playerPos.z;
        double euclideanDistance = Math.sqrt(dx * dx + dz * dz);

        // Нормализованный вектор направления к центру чанка (D_norm)
        double invDist = 1.0 / Math.max(0.001, euclideanDistance);
        double dirX = dx * invDist;
        double dirZ = dz * invDist;

        // Скалярное произведение (V_norm · D_norm) в плоскости XZ
        double alignment = dirX * this.effectiveMotionDir.x + dirZ * this.effectiveMotionDir.z;

        // Расчет итогового приоритета по спецификации
        double priority = euclideanDistance - (alignment * this.deltaBias);

        // Определение класса приоритета
        int priorityClass;
        if (alignment > 0.65 && this.currentSpeed > 1.5) {
            priorityClass = 0; // Priority Class 0: чанк прямо по курсу быстрого полета
        } else if (alignment >= -0.2) {
            priorityClass = 1; // Priority Class 1: поле зрения игрока
        } else {
            priorityClass = 2; // Priority Class 2: позади игрока
        }

        return new ChunkTask(chunkX, chunkZ, priorityClass, priority, euclideanDistance, task);
    }

    /**
     * Ставит задачу обработки чанка в неблокирующую приоритетную очередь.
     */
    public void enqueue(int chunkX, int chunkZ, Runnable task) {
        ChunkTask evaluated = this.evaluateChunk(chunkX, chunkZ, task);
        this.taskQueue.offer(evaluated);
    }

    /**
     * Сбрасывает таймер бюджета в начале каждого кадра.
     */
    public void beginFrame() {
        this.frameTimeSpentNanos.set(0);
        this.isThrottled = false;
    }

    /**
     * Извлекает и выполняет задачи из очереди до исчерпания кадрового лимита Bandwidth Throttler (4.0 мс).
     *
     * @return количество выполненных задач
     */
    public int drainQueueWithinBudget() {
        int executedTasks = 0;
        long startNanos = System.nanoTime();

        while (!this.taskQueue.isEmpty()) {
            long elapsedNanos = System.nanoTime() - startNanos;
            if (elapsedNanos >= MAX_FRAME_BUDGET_NANOS) {
                this.isThrottled = true;
                break;
            }

            ChunkTask chunkTask = this.taskQueue.poll();
            if (chunkTask != null && chunkTask.task() != null) {
                chunkTask.task().run();
                executedTasks++;
            }
        }

        this.frameTimeSpentNanos.addAndGet(System.nanoTime() - startNanos);
        return executedTasks;
    }

    public void clear() {
        this.taskQueue.clear();
    }

    public double getCurrentSpeed() {
        return this.currentSpeed;
    }

    public boolean isThrottled() {
        return this.isThrottled;
    }

    public int getPendingCount() {
        return this.taskQueue.size();
    }

    public PriorityBlockingQueue<ChunkTask> getTaskQueue() {
        return this.taskQueue;
    }
}
