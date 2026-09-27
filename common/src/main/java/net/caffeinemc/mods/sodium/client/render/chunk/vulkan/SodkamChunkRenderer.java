package net.caffeinemc.mods.sodium.client.render.chunk.vulkan;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Исполнительный рендерер чанков Sodkam на базе нативного Vulkan API.
 * Управляет формированием косвенных вызовов (Indirect Draw), привязкой
 * графических пайплайнов и эмиссией команд в ванильный RenderPass.
 */
public interface SodkamChunkRenderer extends AutoCloseable {

    /**
     * Подготавливает видимые списки секций к отрисовке: заполняет структуры
     * косвенных вызовов (Indirect Draw Buffers) и вычисляет видимость на CPU.
     *
     * @param renderLists             коллекция списков видимых секций по проходам
     * @param cameraTransform         трансформация камеры текущего кадра
     * @param indexedRenderingEnabled флаг использования индексированного рендеринга
     */
    void prepare(ChunkRenderListIterable renderLists, CameraTransform cameraTransform, boolean indexedRenderingEnabled);

    /**
     * Записывает команды косвенной отрисовки ландшафта для указанного прохода (Solid, Cutout, Translucent)
     * в активный первичный или вторичный командный буфер ванильного прохода рендера.
     *
     * @param pass          тип текущего прохода террейна
     * @param commandBuffer активный VkCommandBuffer ванильного RenderPass
     * @param modelView     матрица Model-View камеры
     * @param projection    матрица проекции
     */
    void renderTerrainPass(
            TerrainRenderPass pass,
            VkCommandBuffer commandBuffer,
            Matrix4f modelView,
            Matrix4f projection
    );

    /**
     * Обновляет параметры тумана и глобальные константы в Push Constant блоке пайплайна.
     *
     * @param commandBuffer  активный VkCommandBuffer
     * @param pipelineLayout дескриптор VkPipelineLayout
     * @param fogStart       дистанция начала тумана
     * @param fogEnd         дистанция окончания тумана
     * @param fogColorR      красный компонент цвета
     * @param fogColorG      зеленый компонент цвета
     * @param fogColorB      синий компонент цвета
     * @param fogColorA      альфа-компонент цвета
     */
    void updatePushConstants(
            VkCommandBuffer commandBuffer,
            long pipelineLayout,
            float fogStart,
            float fogEnd,
            float fogColorR,
            float fogColorG,
            float fogColorB,
            float fogColorA
    );

    /**
     * Проверяет, имеются ли записанные команды отрисовки для указанного прохода.
     *
     * @param pass проход террейна
     * @return true, если в текущем кадре есть геометрия для отрисовки
     */
    boolean hasCommandsForPass(TerrainRenderPass pass);

    /**
     * Освобождение пайплайнов, дескрипторов и indirect-буферов рендерера.
     */
    @Override
    void close();
}
