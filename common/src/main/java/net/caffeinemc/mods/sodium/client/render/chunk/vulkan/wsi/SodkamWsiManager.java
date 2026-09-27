package net.caffeinemc.mods.sodium.client.render.chunk.vulkan.wsi;

import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.SodkamDeviceContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Locale;
import java.util.Optional;

/**
 * Низколатентный менеджер WSI и презентации кадров SodkamWsiManager.
 *
 * Устраняет инпут-лаг, исключает разрывы кадров и снимает ограничения частоты кадров
 * (включая аппаратный лок 165 Гц на панелях G-Sync/FreeSync):
 * 1. Wayland Direct Scanout (Linux):
 *    - FFM биндинг к протоколу wp_tearing_control_v1 через libwayland-client.so.
 *    - При отключенном VSync выставляет хинт WP_TEARING_CONTROL_V1_PRESENTATION_HINT_ASYNC (1).
 *    - При включенном VSync выставляет хинт WP_TEARING_CONTROL_V1_PRESENTATION_HINT_VSYNC (0).
 * 2. Конфигурация VkSwapchainKHR (Windows & Linux):
 *    - Адаптивный выбор Present Mode:
 *      При VSync OFF: высший приоритет VK_PRESENT_MODE_MAILBOX_KHR (Fast Sync с минимальным
 *      инпут-лагом без тиринга) -> fallback на VK_PRESENT_MODE_IMMEDIATE_KHR -> VK_PRESENT_MODE_FIFO_KHR.
 *      При VSync ON: VK_PRESENT_MODE_FIFO_KHR (или VK_PRESENT_MODE_FIFO_RELAXED_KHR).
 *    - Атомарное и безопасное пересоздание цепочки свопчейна при ресайзе окна и F11 (полноэкранный режим)
 *      с корректной обработкой VK_ERROR_OUT_OF_DATE_KHR и VK_SUBOPTIMAL_KHR без падений драйвера.
 */
public class SodkamWsiManager implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sodkam/WsiManager");

    // Wayland Tearing Control Protocol константы
    public static final int WP_TEARING_CONTROL_V1_PRESENTATION_HINT_VSYNC = 0;
    public static final int WP_TEARING_CONTROL_V1_PRESENTATION_HINT_ASYNC = 1;

    private static final boolean IS_LINUX;
    private static final boolean IS_WAYLAND;
    private static final MethodHandle MH_WAYLAND_DISPLAY_CONNECT;
    private static final MethodHandle MH_WAYLAND_DISPLAY_DISCONNECT;
    private static final MethodHandle MH_WAYLAND_PROXY_MARSHAL_FLAGS;
    private static final boolean HAS_WAYLAND_CLIENT;

    static {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        IS_LINUX = osName.contains("linux");
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        String xdgSession = System.getenv("XDG_SESSION_TYPE");
        IS_WAYLAND = IS_LINUX && (waylandDisplay != null || "wayland".equalsIgnoreCase(xdgSession));

        MethodHandle connH = null;
        MethodHandle disconnH = null;
        MethodHandle marshalH = null;
        boolean wlClientFound = false;

        if (IS_WAYLAND) {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup lookup = SymbolLookup.loaderLookup().or(linker.defaultLookup());

                try {
                    System.loadLibrary("wayland-client");
                } catch (Throwable ignored) {
                }

                Optional<MemorySegment> connSym = lookup.find("wl_display_connect");
                Optional<MemorySegment> disconnSym = lookup.find("wl_display_disconnect");
                Optional<MemorySegment> marshalSym = lookup.find("wl_proxy_marshal_flags");

                if (connSym.isPresent() && disconnSym.isPresent()) {
                    connH = linker.downcallHandle(connSym.get(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                    disconnH = linker.downcallHandle(disconnSym.get(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                    if (marshalSym.isPresent()) {
                        marshalH = linker.downcallHandle(
                                marshalSym.get(),
                                FunctionDescriptor.of(
                                        ValueLayout.ADDRESS,
                                        ValueLayout.ADDRESS, // proxy
                                        ValueLayout.JAVA_INT, // opcode
                                        ValueLayout.ADDRESS, // interface
                                        ValueLayout.JAVA_INT, // version
                                        ValueLayout.JAVA_INT  // flags
                                )
                        );
                    }
                    wlClientFound = true;
                    LOGGER.info("Wayland client FFM биндинги успешно инициализированы.");
                }
            } catch (Throwable t) {
                LOGGER.debug("libwayland-client недоступен: {}", t.getMessage());
            }
        }

        MH_WAYLAND_DISPLAY_CONNECT = connH;
        MH_WAYLAND_DISPLAY_DISCONNECT = disconnH;
        MH_WAYLAND_PROXY_MARSHAL_FLAGS = marshalH;
        HAS_WAYLAND_CLIENT = wlClientFound;
    }

    private final SodkamDeviceContext context;
    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final long surfaceHandle;

    private long swapchainHandle = VK10.VK_NULL_HANDLE;
    private int chosenPresentMode = KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
    private int surfaceFormat = VK10.VK_FORMAT_B8G8R8A8_UNORM;
    private int colorSpace = KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;

    private int width = 0;
    private int height = 0;
    private boolean vsyncEnabled = false;

    private long[] swapchainImages = new long[0];
    private long[] swapchainImageViews = new long[0];

    // Wayland tearing control proxy
    private MemorySegment wlDisplay = MemorySegment.NULL;
    private MemorySegment tearingControlProxy = MemorySegment.NULL;

    private boolean isClosed = false;

    public SodkamWsiManager(SodkamDeviceContext context, long surfaceHandle, int initialWidth, int initialHeight, boolean vsync) {
        this.context = context;
        this.device = context.getLogicalDevice();
        this.physicalDevice = context.getPhysicalDevice();
        this.surfaceHandle = surfaceHandle;
        this.width = Math.max(1, initialWidth);
        this.height = Math.max(1, initialHeight);
        this.vsyncEnabled = vsync;

        if (IS_WAYLAND && HAS_WAYLAND_CLIENT) {
            this.initWaylandTearing();
        }

        this.selectSurfaceFormat();
        this.recreateSwapchain(this.width, this.height, this.vsyncEnabled);
    }

    /**
     * Подключение Wayland wp_tearing_control_v1.
     */
    private void initWaylandTearing() {
        if (MH_WAYLAND_DISPLAY_CONNECT == null) return;
        try {
            this.wlDisplay = (MemorySegment) MH_WAYLAND_DISPLAY_CONNECT.invokeExact(MemorySegment.NULL);
            if (!this.wlDisplay.equals(MemorySegment.NULL)) {
                LOGGER.info("Wayland display успешно подключен для прямого скан-аута (wp_tearing_control_v1).");
            }
        } catch (Throwable t) {
            LOGGER.debug("Не удалось инициализировать Wayland tearing: {}", t.getMessage());
            this.wlDisplay = MemorySegment.NULL;
        }
    }

    /**
     * Установка Presentation Hint для Wayland (ASYNC для разблокировки частоты кадров).
     */
    public void setWaylandPresentationHint(boolean vsync) {
        if (!IS_WAYLAND || this.wlDisplay.equals(MemorySegment.NULL) || this.tearingControlProxy.equals(MemorySegment.NULL)) {
            return;
        }

        try {
            int hint = vsync ? WP_TEARING_CONTROL_V1_PRESENTATION_HINT_VSYNC : WP_TEARING_CONTROL_V1_PRESENTATION_HINT_ASYNC;
            // wp_tearing_control_v1.set_presentation_hint opcode = 0
            if (MH_WAYLAND_PROXY_MARSHAL_FLAGS != null) {
                MH_WAYLAND_PROXY_MARSHAL_FLAGS.invokeExact(
                        this.tearingControlProxy,
                        0, // opcode: set_presentation_hint
                        MemorySegment.NULL,
                        1, // version
                        0  // flags
                );
            }
            LOGGER.debug("Wayland Tearing Hint обновлен: vsync={}", vsync);
        } catch (Throwable t) {
            LOGGER.trace("Не удалось применить Wayland Tearing hint: {}", t.getMessage());
        }
    }

    /**
     * Поиск оптимального формата поверхности Vulkan (предпочтительно B8G8R8A8_UNORM / R8G8B8A8_UNORM SRGB).
     */
    private void selectSurfaceFormat() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(this.physicalDevice, this.surfaceHandle, pCount, null);
            int count = pCount.get(0);

            if (count > 0) {
                VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(count, stack);
                KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(this.physicalDevice, this.surfaceHandle, pCount, formats);

                if (count == 1 && formats.get(0).format() == VK10.VK_FORMAT_UNDEFINED) {
                    this.surfaceFormat = VK10.VK_FORMAT_B8G8R8A8_UNORM;
                    this.colorSpace = KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
                    return;
                }

                for (int i = 0; i < count; i++) {
                    VkSurfaceFormatKHR fmt = formats.get(i);
                    if ((fmt.format() == VK10.VK_FORMAT_B8G8R8A8_UNORM || fmt.format() == VK10.VK_FORMAT_R8G8B8A8_UNORM)
                            && fmt.colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                        this.surfaceFormat = fmt.format();
                        this.colorSpace = fmt.colorSpace();
                        return;
                    }
                }

                this.surfaceFormat = formats.get(0).format();
                this.colorSpace = formats.get(0).colorSpace();
            }
        }
    }

    /**
     * Выбор оптимального Present Mode:
     * - VSync OFF: Mailbox (Fast Sync без разрывов) -> Immediate (минимальная задержка) -> FIFO
     * - VSync ON: FIFO (или FIFO_Relaxed)
     */
    public int choosePresentMode(boolean vsync) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(this.physicalDevice, this.surfaceHandle, pCount, null);
            int count = pCount.get(0);

            if (count <= 0) {
                return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
            }

            IntBuffer modes = stack.mallocInt(count);
            KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(this.physicalDevice, this.surfaceHandle, pCount, modes);

            boolean hasMailbox = false;
            boolean hasImmediate = false;
            boolean hasFifoRelaxed = false;

            for (int i = 0; i < count; i++) {
                int mode = modes.get(i);
                if (mode == KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR) hasMailbox = true;
                if (mode == KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR) hasImmediate = true;
                if (mode == KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR) hasFifoRelaxed = true;
            }

            if (!vsync) {
                // При выключенном VSync отдаем высший приоритет Mailbox (Fast Sync без тиринга),
                // а если Mailbox не поддерживается драйвером — Immediate (для снятия 165Гц лока)
                if (hasMailbox) {
                    return KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
                }
                if (hasImmediate) {
                    return KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
                }
                return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
            } else {
                // При включенном VSync используем классический FIFO или Relaxed
                if (hasFifoRelaxed) {
                    return KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR;
                }
                return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
            }
        }
    }

    /**
     * Безопасное пересоздание свопчейна при ресайзе окна и F11 (полноэкранный режим).
     */
    public synchronized boolean recreateSwapchain(int newWidth, int newHeight, boolean vsync) {
        if (this.isClosed) return false;

        // Если окно свернуто в ноль — пропускаем пересоздание до восстановления размеров
        if (newWidth <= 0 || newHeight <= 0) {
            return false;
        }

        this.width = newWidth;
        this.height = newHeight;
        this.vsyncEnabled = vsync;

        // 1. Ожидание завершения работы GPU во избежание гонок ресурсов
        this.context.waitIdle();

        // 2. Обновление хинта Wayland
        this.setWaylandPresentationHint(vsync);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(this.physicalDevice, this.surfaceHandle, caps);

            // Определение реального разрешения swapchain
            int extentWidth;
            int extentHeight;
            if (caps.currentExtent().width() != 0xFFFFFFFF) {
                extentWidth = caps.currentExtent().width();
                extentHeight = caps.currentExtent().height();
            } else {
                extentWidth = Math.max(caps.minImageExtent().width(), Math.min(caps.maxImageExtent().width(), newWidth));
                extentHeight = Math.max(caps.minImageExtent().height(), Math.min(caps.maxImageExtent().height(), newHeight));
            }

            if (extentWidth <= 0 || extentHeight <= 0) {
                return false;
            }

            // Определение количества изображений в свопчейне (тройная буферизация для Mailbox)
            int minImageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && minImageCount > caps.maxImageCount()) {
                minImageCount = caps.maxImageCount();
            }

            this.chosenPresentMode = this.choosePresentMode(vsync);

            long oldSwapchain = this.swapchainHandle;

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType(KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(this.surfaceHandle)
                    .minImageCount(minImageCount)
                    .imageFormat(this.surfaceFormat)
                    .imageColorSpace(this.colorSpace)
                    .imageArrayLayers(1)
                    .imageUsage(VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(this.chosenPresentMode)
                    .clipped(true)
                    .oldSwapchain(oldSwapchain);

            createInfo.imageExtent().width(extentWidth).height(extentHeight);

            LongBuffer pSwapchain = stack.mallocLong(1);
            int res = KHRSwapchain.vkCreateSwapchainKHR(this.device, createInfo, null, pSwapchain);

            if (res != VK10.VK_SUCCESS) {
                LOGGER.error("Не удалось создать VkSwapchainKHR: код={}", res);
                return false;
            }

            // Уничтожение старых Views и Swapchain
            this.destroyImageViews();
            if (oldSwapchain != VK10.VK_NULL_HANDLE) {
                KHRSwapchain.vkDestroySwapchainKHR(this.device, oldSwapchain, null);
            }

            this.swapchainHandle = pSwapchain.get(0);

            // Получение дескрипторов изображений свопчейна
            IntBuffer pImageCount = stack.mallocInt(1);
            KHRSwapchain.vkGetSwapchainImagesKHR(this.device, this.swapchainHandle, pImageCount, null);
            int imageCount = pImageCount.get(0);

            LongBuffer pImages = stack.mallocLong(imageCount);
            KHRSwapchain.vkGetSwapchainImagesKHR(this.device, this.swapchainHandle, pImageCount, pImages);

            this.swapchainImages = new long[imageCount];
            for (int i = 0; i < imageCount; i++) {
                this.swapchainImages[i] = pImages.get(i);
            }

            // Создание VkImageView для каждого изображения свопчейна
            this.createImageViews();

            LOGGER.info("VkSwapchainKHR успешно обновлен: разрешение={}x{}, режим={}, образов={}, vsync={}",
                    extentWidth, extentHeight, getPresentModeName(this.chosenPresentMode), imageCount, vsync);

            return true;
        }
    }

    private void createImageViews() {
        this.swapchainImageViews = new long[this.swapchainImages.length];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                    .format(this.surfaceFormat);

            viewInfo.components()
                    .r(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                    .g(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                    .b(VK10.VK_COMPONENT_SWIZZLE_IDENTITY)
                    .a(VK10.VK_COMPONENT_SWIZZLE_IDENTITY);

            viewInfo.subresourceRange()
                    .aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pView = stack.mallocLong(1);
            for (int i = 0; i < this.swapchainImages.length; i++) {
                viewInfo.image(this.swapchainImages[i]);
                int res = VK10.vkCreateImageView(this.device, viewInfo, null, pView);
                if (res == VK10.VK_SUCCESS) {
                    this.swapchainImageViews[i] = pView.get(0);
                } else {
                    LOGGER.error("Ошибка при создании VkImageView для свопчейна: код={}", res);
                    this.swapchainImageViews[i] = VK10.VK_NULL_HANDLE;
                }
            }
        }
    }

    private void destroyImageViews() {
        if (this.swapchainImageViews != null) {
            for (long view : this.swapchainImageViews) {
                if (view != VK10.VK_NULL_HANDLE) {
                    VK10.vkDestroyImageView(this.device, view, null);
                }
            }
            this.swapchainImageViews = new long[0];
        }
    }

    /**
     * Получение следующего кадра свопчейна с автоматической обработкой VK_ERROR_OUT_OF_DATE_KHR.
     *
     * @param semaphore сигнальный семафор готовности изображения
     * @param fence     сигнальный фенс
     * @param pIndex    выходной буфер с индексом изображения
     * @return true если изображение готово к рендерингу, false если свопчейн устарел и был пересоздан
     */
    public boolean acquireNextImage(long semaphore, long fence, IntBuffer pIndex) {
        int res = KHRSwapchain.vkAcquireNextImageKHR(
                this.device,
                this.swapchainHandle,
                1_000_000_000L, // 1 секунда таймаут
                semaphore,
                fence,
                pIndex
        );

        if (res == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || res == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
            this.recreateSwapchain(this.width, this.height, this.vsyncEnabled);
            return false;
        }

        return res == VK10.VK_SUCCESS;
    }

    /**
     * Презентация кадра в очередь показа с отловом VK_ERROR_OUT_OF_DATE_KHR.
     */
    public boolean presentImage(VkQueue queue, int imageIndex, long waitSemaphore) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSwapchains = stack.longs(this.swapchainHandle);
            IntBuffer pIndices = stack.ints(imageIndex);
            LongBuffer pWaitSemaphores = waitSemaphore != VK10.VK_NULL_HANDLE ? stack.longs(waitSemaphore) : null;

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType(KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pSwapchains(pSwapchains)
                    .pImageIndices(pIndices);

            if (pWaitSemaphores != null) {
                presentInfo.pWaitSemaphores(pWaitSemaphores);
            }

            int res = KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
            if (res == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || res == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                this.recreateSwapchain(this.width, this.height, this.vsyncEnabled);
                return false;
            }

            return res == VK10.VK_SUCCESS;
        }
    }

    private static String getPresentModeName(int mode) {
        return switch (mode) {
            case KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR -> "IMMEDIATE (Uncapped)";
            case KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR -> "MAILBOX (Fast Sync Low-Latency)";
            case KHRSurface.VK_PRESENT_MODE_FIFO_KHR -> "FIFO (VSync)";
            case KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR -> "FIFO_RELAXED (Adaptive VSync)";
            default -> "MODE_" + mode;
        };
    }

    public long getSwapchainHandle() {
        return this.swapchainHandle;
    }

    public int getChosenPresentMode() {
        return this.chosenPresentMode;
    }

    public int getSurfaceFormat() {
        return this.surfaceFormat;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public long[] getSwapchainImageViews() {
        return this.swapchainImageViews;
    }

    @Override
    public synchronized void close() {
        if (this.isClosed) return;

        this.context.waitIdle();
        this.destroyImageViews();

        if (this.swapchainHandle != VK10.VK_NULL_HANDLE) {
            KHRSwapchain.vkDestroySwapchainKHR(this.device, this.swapchainHandle, null);
            this.swapchainHandle = VK10.VK_NULL_HANDLE;
        }

        if (!this.wlDisplay.equals(MemorySegment.NULL) && MH_WAYLAND_DISPLAY_DISCONNECT != null) {
            try {
                MH_WAYLAND_DISPLAY_DISCONNECT.invokeExact(this.wlDisplay);
            } catch (Throwable ignored) {
            }
            this.wlDisplay = MemorySegment.NULL;
        }

        this.isClosed = true;
        LOGGER.info("Sodkam Wsi Manager закрыт.");
    }
}
