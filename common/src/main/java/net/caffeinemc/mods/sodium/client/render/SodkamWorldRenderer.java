package net.caffeinemc.mods.sodium.client.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContext;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContextImpl;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.VulkanContextBridge;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.sync.SodkamSync2;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.culling.SodkamGpuCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline.SodkamPushConstants;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.sorting.SodkamTranslucentSorter;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.texture.SodkamAtlasTextureUploader;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.SodkamUpscaleBridge;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.UpscaleQuality;
import net.caffeinemc.mods.sodium.client.render.entity.SodkamBlockEntityBatcher;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.async.SodkamAsyncCompute;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.bindless.SodkamBindlessManager;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.cull.SodkamHiZCulling;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.io.SodkamSimdRegionLoader;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.mesher.SodkamGpuMesher;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.meshlet.SodkamMeshShaderPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.rt.RayTracingMode;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.rt.SodkamRayTracingPipeline;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.scheduler.SodkamVelocityScheduler;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.texture.SodkamVirtualTexturing;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.vrs.SodkamVrsTier2;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.SodkamMeshUploader;
import net.caffeinemc.mods.sodium.client.gpu.arena.vulkan.SodkamBufferArena;
import net.caffeinemc.mods.sodium.client.gpu.arena.vulkan.SodkamBufferArenaImpl;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.transfer.SodkamAsyncTransferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pacing.SodkamFramePacing;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Math;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.pipeline.SodkamPipelineCache;
import net.caffeinemc.mods.sodium.client.services.PlatformRuntimeInformation;
import java.nio.file.Path;
import java.util.List;

/**
 * Центральный архитектурный координатор конвейера Vuldium (SodkamWorldRenderer).
 *
 * Управляет полным покадровым жизненным циклом рендеринга воксельного мира
 * через 8 строго синхронизированных фаз (Phases 0–7) без гонок конвейера и без JNI-накладных расходов:
 *
 * - Фаза 0 (Setup): сброс аллокаторов, стриминг грязных анимированных спрайтов (вода, лава, порталы)
 * - Фаза 1 (Culling & Compute): передача фрустума в SodkamGpuCuller, vkCmdDispatch вычисления видимости
 * - Фаза 2 (Sky Pass): отрисовка фона неба и светил с depthWriteEnable = VK_FALSE и туманом
 * - Фаза 3 (Opaque Pass): непрямая отрисовка vkCmdDrawIndexedIndirectCount непрозрачного ландшафта
 * - Фаза 4 (Entities & Block Entities): рендеринг мобов и пакетный сброс SodkamBlockEntityBatcher (Z-тест + Z-запись)
 * - Фаза 5 (Translucent Pass): динамическая сортировка квадов SodkamTranslucentSorter и альфа-блендинг воды/стекла
 * - Фаза 6 (Post-Processing & Upscaling): Multi-AI Upscale Bridge (FSR / DLSS / VRS) с выводом в нативный буфер
 * - Фаза 7 (UI Pass-through): возврат управления ванильному коду для отрисовки интерфейса (HUD, инвентарь, чат)
 */
public class SodkamWorldRenderer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vuldium/WorldRenderer");

    private static volatile SodkamWorldRenderer INSTANCE;

    private final SodkamDeviceContext context;
    private final SodkamSync2 sync2;

    // Подсистемы конвейера
    private final SodkamPipelineCache pipelineCache;
    private final SodkamAtlasTextureUploader atlasUploader;
    private final SodkamGpuCuller gpuCuller;
    private final SodkamBlockEntityBatcher blockEntityBatcher;
    private final SodkamTranslucentSorter translucentSorter;
    private final SodkamUpscaleBridge upscaleBridge;
    private final SodkamPushConstants pushConstants;
    private final SodkamRayTracingPipeline rayTracingPipeline;
    private final SodkamMeshShaderPipeline meshShaderPipeline;
    private final SodkamHiZCulling hiZCulling;
    private final SodkamBindlessManager bindlessManager;
    private final SodkamAsyncCompute asyncCompute;
    private final SodkamVrsTier2 vrsTier2;
    private final SodkamVirtualTexturing virtualTexturing;
    private final SodkamBufferArena bufferArena;
    private final SodkamAsyncTransferManager asyncTransferManager;
    private final SodkamMeshUploader meshUploader;
    private final SodkamGpuMesher gpuMesher;
    private final SodkamVelocityScheduler velocityScheduler;
    private final SodkamSimdRegionLoader simdRegionLoader;

    // Предвыделенные структуры для исключения JVM аллокаций в горячем цикле отрисовки
    private final Matrix4f mvpMatrix = new Matrix4f();
    private final Vector4f[] frustumPlanes = new Vector4f[6];

    // Текущее состояние окружения и тумана
    private final Vector4f currentFogColor = new Vector4f(0.5f, 0.6f, 0.7f, 1.0f);
    private float currentFogStart = 0.0f;
    private float currentFogEnd = 256.0f;
    private int currentFogShape = 0; // 0 = Sphere, 1 = Cylinder

    private boolean isClosed = false;

    public static SodkamWorldRenderer getInstance() {
        if (INSTANCE == null) {
            synchronized (SodkamWorldRenderer.class) {
                if (INSTANCE == null) {
                    if (!VulkanContextBridge.isVulkanBackend()) {
                        return null;
                    }
                    try {
                        var handles = VulkanContextBridge.extractNativeHandles();
                        SodkamDeviceContext ctx = new SodkamDeviceContextImpl(handles);
                        INSTANCE = new SodkamWorldRenderer(ctx);
                    } catch (Throwable t) {
                        LOGGER.warn("Не удалось инициализировать SodkamWorldRenderer (fallback на ванильный пайплайн): {}", t.getMessage());
                        return null;
                    }
                }
            }
        }
        return INSTANCE;
    }

    public static SodkamWorldRenderer getInstanceNullable() {
        return INSTANCE;
    }

    public static void init(SodkamDeviceContext context) {
        synchronized (SodkamWorldRenderer.class) {
            if (INSTANCE != null) {
                INSTANCE.close();
            }
            INSTANCE = new SodkamWorldRenderer(context);
        }
    }

    public SodkamWorldRenderer(SodkamDeviceContext context) {
        this.context = context;
        this.sync2 = new SodkamSync2(context);

        Path cacheDir = PlatformRuntimeInformation.getInstance().getGameDirectory().resolve("vuldium_cache");
        Path cacheFile = cacheDir.resolve("pipelines.bin");
        this.pipelineCache = new SodkamPipelineCache(context.getLogicalDevice(), cacheFile);

        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (this.pipelineCache != null) {
                    this.pipelineCache.saveToDisk();
                }
            }, "Vuldium-PipelineCache-Save"));
        } catch (Throwable ignored) {}

        this.atlasUploader = new SodkamAtlasTextureUploader(context);
        this.gpuCuller = SodkamGpuCuller.create(context, 8192, this.pipelineCache.getHandle());
        this.blockEntityBatcher = new SodkamBlockEntityBatcher(context, 4096);
        this.upscaleBridge = new SodkamUpscaleBridge(context);
        this.pushConstants = new SodkamPushConstants();

        // Инициализация сортировщика квадов с компактным начальным размером (4096 квадов)
        this.translucentSorter = new SodkamTranslucentSorter(4096, new float[4096 * 3]);

        var caps = context.getDeviceCapabilities();
        this.rayTracingPipeline = new SodkamRayTracingPipeline(context.getLogicalDevice(), caps.VK_KHR_ray_query);
        this.meshShaderPipeline = new SodkamMeshShaderPipeline(context.getLogicalDevice(), caps.VK_EXT_mesh_shader);
        this.hiZCulling = new SodkamHiZCulling(context, this.gpuCuller);
        this.bindlessManager = new SodkamBindlessManager(context);
        this.asyncCompute = new SodkamAsyncCompute(context.getLogicalDevice(), context.getTransferQueue(), context.getTransferQueueFamilyIndex(), true);
        this.vrsTier2 = new SodkamVrsTier2(context.getLogicalDevice(), caps.VK_KHR_fragment_shading_rate);
        this.virtualTexturing = new SodkamVirtualTexturing(context.getLogicalDevice());

        // Предварительное выделение 512 МБ арены памяти геометрии (Device-Local) и 48 МБ Staging
        this.bufferArena = SodkamBufferArenaImpl.createHighDistanceArena(context, 48);
        this.asyncTransferManager = new SodkamAsyncTransferManager(context);
        this.meshUploader = new SodkamMeshUploader(context, this.bufferArena, this.asyncTransferManager);
        this.gpuMesher = new SodkamGpuMesher(context, this.bufferArena);
        this.velocityScheduler = new SodkamVelocityScheduler();
        this.simdRegionLoader = new SodkamSimdRegionLoader();

        for (int i = 0; i < 6; i++) {
            this.frustumPlanes[i] = new Vector4f();
        }

        LOGGER.info("VuldiumWorldRenderer успешно инициализирован. Все подсистемы (Culler, Atlas, BE-Batcher, Translucent, Upscale, RT, Meshlets, Hi-Z, VRS) активны.");
    }

    /**
     * Центральная точка входа покадрового рендеринга мира (Phases 0–7).
     *
     * @param cmd                    активный командный буфер Vulkan (может извлекаться из renderPass)
     * @param renderPass             активный RenderPass мира RenderPearl
     * @param camera                 камера игрока
     * @param projectionMatrix       матрица проекции
     * @param modelViewMatrix        матрица вида (ModelView)
     * @param fogColor               цвет тумана (RGBA)
     * @param fogStart               дистанция начала тумана
     * @param fogEnd                 дистанция окончания тумана
     * @param fogShape               форма тумана (0 = сфера, 1 = цилиндр)
     * @param targetWidth            нативная ширина экрана
     * @param targetHeight           нативная высота экрана
     * @param totalChunks            общее количество секций чанков в сцене
     * @param dirtySprites           список обновленных анимированных спрайтов за тик
     * @param atlasImage             дескриптор текстурного атласа блоков
     * @param depthImageView         дескриптор буфера глубины мира для рендеринга/апскейла
     * @param skyPassCallback        обратный вызов отрисовки неба
     * @param entitiesPassCallback   обратный вызов отрисовки сущностей (мобы, игроки)
     * @param translucentPassCallback обратный вызов отрисовки полупрозрачной геометрии
     */
    public void renderWorldFrame(
            VkCommandBuffer cmd,
            RenderPass renderPass,
            Camera camera,
            Matrix4fc projectionMatrix,
            Matrix4fc modelViewMatrix,
            Vector4f fogColor,
            float fogStart,
            float fogEnd,
            int fogShape,
            int targetWidth,
            int targetHeight,
            int totalChunks,
            List<SodkamAtlasTextureUploader.SpriteUpdate> dirtySprites,
            long atlasImage,
            long depthImageView,
            Runnable skyPassCallback,
            Runnable entitiesPassCallback,
            Runnable translucentPassCallback
    ) {
        if (cmd == null && renderPass != null) {
            cmd = VulkanContextBridge.extractCommandBuffer(renderPass);
        }

        // Сохраняем актуальные параметры тумана
        if (fogColor != null) {
            this.currentFogColor.set(fogColor);
        }
        this.currentFogStart = fogStart;
        this.currentFogEnd = fogEnd;
        this.currentFogShape = fogShape;

        var sodkamOpts = SodiumClientMod.options().sodkam;

        // =========================================================================
        // ФАЗА 0 (Setup): Сброс временных аллокаторов кадра и стриминг спрайтов
        // =========================================================================
        this.blockEntityBatcher.begin();

        // Неблокирующая загрузка мешей чанков с контролем бюджета (Upload Budget Limiter)
        if (this.meshUploader != null && cmd != null) {
            this.meshUploader.processPendingUploads(cmd);
        }

        if (dirtySprites != null && !dirtySprites.isEmpty() && atlasImage != VK10.VK_NULL_HANDLE && cmd != null) {
            this.atlasUploader.uploadUpdates(cmd, atlasImage, dirtySprites);
        }

        // Virtual Texturing (Sparse VRAM): потоковая подгрузка мипов атласа
        if (sodkamOpts.virtualTexturing) {
            this.virtualTexturing.setEnabled(true);
        }

        // =========================================================================
        // Плавная интерполяция камеры и матриц (Frame Pacing & Camera Interpolation)
        // =========================================================================
        Vec3 cameraPos = SodkamFramePacing.getInterpolatedCameraPosition(camera);
        Matrix4f smoothModelView = SodkamFramePacing.buildSmoothModelViewMatrix(camera, modelViewMatrix);

        // Velocity-Aware Chunk Scheduler: предиктивное обновление вектора движения и кадровый бюджет
        if (camera != null) {
            Vec3 cPos = camera.position();
            this.velocityScheduler.updatePlayerMotion(new org.joml.Vector3d(cPos.x, cPos.y, cPos.z));
            this.velocityScheduler.beginFrame();
            this.velocityScheduler.drainQueueWithinBudget();
        }

        // Вычисление MVP матрицы и извлечение 6 плоскостей видимости Frustum Culling
        this.mvpMatrix.set(projectionMatrix).mul(smoothModelView);
        extractFrustumPlanes(this.mvpMatrix, this.frustumPlanes);

        // Заполнение Push Constants для шейдеров геометрии с непрерывными координатами
        this.pushConstants.setMvpMatrix(this.mvpMatrix);
        this.pushConstants.setRegionOffset(cameraPos.x, cameraPos.y, cameraPos.z);
        this.pushConstants.setFogColor(this.currentFogColor.x(), this.currentFogColor.y(), this.currentFogColor.z(), this.currentFogColor.w());
        this.pushConstants.setFogParameters(this.currentFogStart, this.currentFogEnd, this.currentFogShape);

        // =========================================================================
        // ФАЗА 1 (Culling & Compute): GPU Frustum / Hi-Z Culling через Compute Shader
        // =========================================================================
        if (cmd != null && totalChunks > 0) {
            if (sodkamOpts.hiZOcclusionCulling && this.hiZCulling != null) {
                this.hiZCulling.setViewParameters(this.mvpMatrix, this.frustumPlanes);
                if (depthImageView != VK10.VK_NULL_HANDLE) {
                    this.hiZCulling.generateHiZPyramid(cmd, depthImageView);
                }
                this.hiZCulling.executeCull(cmd, totalChunks);
            } else if (this.gpuCuller != null && sodkamOpts.gpuCulling) {
                this.gpuCuller.recordCulling(cmd, this.frustumPlanes, totalChunks);
            }
        }

        // ФАЗА 1.5: Асинхронные частицы (Async Compute Queue)
        if (cmd != null && sodkamOpts.asyncComputeParticles && this.asyncCompute != null) {
            this.asyncCompute.setEnabled(true);
            this.asyncCompute.dispatchSimulation(16384);
        }

        // =========================================================================
        // ФАЗА 2 (Sky Pass): Отрисовка фона неба и светил
        // =========================================================================
        // Рендеринг неба выполняется с depthWriteEnable = VK_FALSE для сохранения буфера глубины
        if (skyPassCallback != null) {
            skyPassCallback.run();
        }

        // =========================================================================
        // ФАЗА 3 (Opaque Pass): Непрозрачная геометрия мира (Meshlets или Draw Indirect)
        // =========================================================================
        // Variable Rate Shading Tier 2: генерация карты частоты шейдинга
        if (cmd != null && sodkamOpts.vrsMode.isEnabled() && this.vrsTier2 != null) {
            this.vrsTier2.setMode(sodkamOpts.vrsMode);
            this.vrsTier2.generateShadingRateImage(cmd, 1.0f);
        }

        // Тест глубины и запись глубины включены (depthTestEnable = true, depthWriteEnable = true)
        if (cmd != null && totalChunks > 0) {
            if (sodkamOpts.meshShaders && this.meshShaderPipeline != null) {
                this.meshShaderPipeline.setEnabled(true);
                this.meshShaderPipeline.drawMeshTasks(cmd, (totalChunks * 8 + 63) / 64, 1, 1);
            } else if (this.gpuCuller != null) {
                this.gpuCuller.recordDrawIndirectCount(cmd, totalChunks);
            }
        }

        // ФАЗА 3.5: Аппаратный Ray Tracing (VK_KHR_ray_query RTAO & Contact Shadows)
        if (cmd != null && sodkamOpts.rayTracing != RayTracingMode.OFF && this.rayTracingPipeline != null) {
            this.rayTracingPipeline.execute(cmd, sodkamOpts.rayTracing, depthImageView);
        }

        // Барьер синхронизации: завершение записи непрозрачного мира -> начало рендеринга сущностей
        if (cmd != null) {
            this.sync2.barrierOpaqueToEntities(cmd);
        }

        // =========================================================================
        // ФАЗА 4 (Entities & Block Entities): Отрисовка мобов и пакетный сброс геометрии блоков
        // =========================================================================
        if (entitiesPassCallback != null) {
            entitiesPassCallback.run();
        }

        // Пакетный сброс сундуков, табличек, голов мобов, поршней и спавнеров единым батчем
        this.blockEntityBatcher.end();
        if (cmd != null) {
            this.blockEntityBatcher.flush(cmd);
        }

        // Барьер синхронизации: глубина сущностей и блоков записана -> начало Translucent Pass
        if (cmd != null) {
            this.sync2.barrierEntitiesToTranslucent(cmd);
        }

        // =========================================================================
        // ФАЗА 5 (Translucent Pass): Сортировка квадов и отрисовка воды/стекла
        // =========================================================================
        if (camera != null && this.translucentSorter != null) {
            double camX = camera.position().x();
            double camY = camera.position().y();
            double camZ = camera.position().z();
            if (this.translucentSorter.shouldResort(camX, camY, camZ)) {
                this.translucentSorter.sortAndUpload(camX, camY, camZ);
            }
        }

        // Отрисовка полупрозрачных поверхностей с альфа-блендингом (depthWriteEnable = VK_FALSE)
        if (translucentPassCallback != null) {
            translucentPassCallback.run();
        }

        // =========================================================================
        // ФАЗА 6 (Post-Processing & Upscaling): Multi-AI Upscale Bridge (FSR / DLSS / XeSS)
        // =========================================================================
        if (cmd != null && this.upscaleBridge != null && this.upscaleBridge.getQuality() != UpscaleQuality.NATIVE) {
            this.sync2.barrierTranslucentToUpscaler(cmd, this.upscaleBridge.getWorldImageView());
            this.upscaleBridge.ensureResolution(targetWidth, targetHeight, this.upscaleBridge.getQuality());
            if (depthImageView != VK10.VK_NULL_HANDLE) {
                this.upscaleBridge.executeUpscale(cmd, depthImageView, false);
            }
        }

        // =========================================================================
        // ФАЗА 7 (UI Pass-through): Возврат управления ванильному коду для отрисовки интерфейса
        // =========================================================================
        // Ванильный GUI (HUD, инвентарь, чат) рисуется поверх масштабированного кадра
        // в нативном экранном разрешении с максимальной чёткостью шрифтов.
    }

    /**
     * Извлечение 6 плоскостей видимости фрустума из матрицы MVP методом Gribb-Hartmann.
     * Выполняется без JVM-аллокаций.
     */
    public static void extractFrustumPlanes(Matrix4fc m, Vector4f[] planes) {
        // Левая плоскость: row 3 + row 0
        planes[0].set(m.m30() + m.m00(), m.m31() + m.m01(), m.m32() + m.m02(), m.m33() + m.m03());
        // Правая плоскость: row 3 - row 0
        planes[1].set(m.m30() - m.m00(), m.m31() - m.m01(), m.m32() - m.m02(), m.m33() - m.m03());
        // Нижняя плоскость: row 3 + row 1
        planes[2].set(m.m30() + m.m10(), m.m31() + m.m11(), m.m32() + m.m12(), m.m33() + m.m13());
        // Верхняя плоскость: row 3 - row 1
        planes[3].set(m.m30() - m.m10(), m.m31() - m.m11(), m.m32() - m.m12(), m.m33() - m.m13());
        // Ближняя плоскость: row 3 + row 2
        planes[4].set(m.m30() + m.m20(), m.m31() + m.m21(), m.m32() + m.m22(), m.m33() + m.m23());
        // Дальняя плоскость: row 3 - row 2
        planes[5].set(m.m30() - m.m20(), m.m31() - m.m21(), m.m32() - m.m22(), m.m33() - m.m23());

        // Нормализация плоскостей
        for (int i = 0; i < 6; i++) {
            Vector4f p = planes[i];
            float len = Math.invsqrt(p.x * p.x + p.y * p.y + p.z * p.z);
            p.x *= len;
            p.y *= len;
            p.z *= len;
            p.w *= len;
        }
    }

    public void onRenderOpaqueGroup(RenderPass pass, ChunkRenderMatrices matrices, double x, double y, double z) {
        VkCommandBuffer cmd = VulkanContextBridge.extractCommandBuffer(pass);
        if (cmd != null && this.gpuCuller != null) {
            this.gpuCuller.recordDrawIndirectCount(cmd, 65536);
            this.sync2.barrierOpaqueToEntities(cmd);
        }
    }

    public void onRenderTranslucentGroup(RenderPass pass, ChunkRenderMatrices matrices, double x, double y, double z) {
        if (this.translucentSorter != null && this.translucentSorter.shouldResort(x, y, z)) {
            this.translucentSorter.sortAndUpload(x, y, z);
        }
    }

    public void flushBlockEntities(VkCommandBuffer cmd) {
        if (this.blockEntityBatcher != null) {
            this.blockEntityBatcher.end();
            this.blockEntityBatcher.flush(cmd);
        }
    }

    public SodkamBlockEntityBatcher getBlockEntityBatcher() {
        return this.blockEntityBatcher;
    }

    public SodkamAtlasTextureUploader getAtlasUploader() {
        return this.atlasUploader;
    }

    public SodkamGpuCuller getGpuCuller() {
        return this.gpuCuller;
    }

    public SodkamUpscaleBridge getUpscaleBridge() {
        return this.upscaleBridge;
    }

    public SodkamPushConstants getPushConstants() {
        return this.pushConstants;
    }

    public SodkamSync2 getSync2() {
        return this.sync2;
    }

    public SodkamDeviceContext getContext() {
        return this.context;
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) {
            return;
        }

        if (this.context != null) {
            this.context.waitIdle();
        }

        if (this.blockEntityBatcher != null) {
            this.blockEntityBatcher.close();
        }
        if (this.atlasUploader != null) {
            this.atlasUploader.close();
        }
        if (this.gpuCuller != null) {
            this.gpuCuller.close();
        }
        if (this.translucentSorter != null) {
            this.translucentSorter.close();
        }
        if (this.upscaleBridge != null) {
            this.upscaleBridge.close();
        }
        if (this.pushConstants != null) {
            this.pushConstants.free();
        }
        if (this.rayTracingPipeline != null) {
            this.rayTracingPipeline.close();
        }
        if (this.meshShaderPipeline != null) {
            this.meshShaderPipeline.close();
        }
        if (this.hiZCulling != null) {
            this.hiZCulling.close();
        }
        if (this.bindlessManager != null) {
            this.bindlessManager.close();
        }
        if (this.asyncCompute != null) {
            this.asyncCompute.close();
        }
        if (this.vrsTier2 != null) {
            this.vrsTier2.close();
        }
        if (this.virtualTexturing != null) {
            this.virtualTexturing.close();
        }
        if (this.meshUploader != null) {
            this.meshUploader.close();
        }
        if (this.bufferArena != null) {
            this.bufferArena.close();
        }
        if (this.asyncTransferManager != null) {
            this.asyncTransferManager.close();
        }
        if (this.gpuMesher != null) {
            this.gpuMesher.close();
        }
        if (this.simdRegionLoader != null) {
            this.simdRegionLoader.close();
        }
        if (this.pipelineCache != null) {
            this.pipelineCache.close();
        }

        this.isClosed = true;
        LOGGER.info("VuldiumWorldRenderer успешно освобожден.");
    }

    public SodkamPipelineCache getPipelineCache() {
        return this.pipelineCache;
    }

    public SodkamMeshUploader getMeshUploader() {
        return this.meshUploader;
    }

    public SodkamBindlessManager getBindlessManager() {
        return this.bindlessManager;
    }

    public SodkamBufferArena getBufferArena() {
        return this.bufferArena;
    }

    public SodkamGpuMesher getGpuMesher() {
        return this.gpuMesher;
    }

    public SodkamVelocityScheduler getVelocityScheduler() {
        return this.velocityScheduler;
    }

    public SodkamSimdRegionLoader getSimdRegionLoader() {
        return this.simdRegionLoader;
    }
}
