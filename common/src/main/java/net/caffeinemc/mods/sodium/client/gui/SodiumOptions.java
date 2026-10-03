package net.caffeinemc.mods.sodium.client.gui;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.textures.FilterMode;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.UpscalerType;
import net.caffeinemc.mods.sodium.client.gui.options.InactivityFpsLimitMode;
import net.caffeinemc.mods.sodium.client.render.chunk.DeferMode;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.QuadSplittingMode;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.UpscaleQuality;
import net.caffeinemc.mods.sodium.client.services.PlatformRuntimeInformation;
import net.caffeinemc.mods.sodium.client.util.FileUtil;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class SodiumOptions {
    private static final String DEFAULT_FILE_NAME = "sodium-options.json";

    public final QualitySettings quality = new QualitySettings();
    public final PerformanceSettings performance = new PerformanceSettings();
    public final AdvancedSettings advanced = new AdvancedSettings();
    public final SodkamSettings sodkam = new SodkamSettings();

    public final AnimationSettings animations = new AnimationSettings();
    public final ParticleSettings particles = new ParticleSettings();
    public final DetailSettings details = new DetailSettings();
    public final RenderSettings render = new RenderSettings();
    public final ExtraSettings extra = new ExtraSettings();

    public final DebugSettings debug = new DebugSettings();
    public final NotificationSettings notifications = new NotificationSettings();

    private boolean readOnly;

    private SodiumOptions() {
        // NO-OP
    }

    public static SodiumOptions defaults() {
        return new SodiumOptions();
    }

    public static class QualitySettings {
        public boolean hiddenFluidCulling = true;
        public boolean improvedFluidShaping = false;
        public boolean useClosestPointEntitySort = false;
        public FilterMode pixelFilteringMode = FilterMode.NEAREST;
    }

    public static class PerformanceSettings {
        public int chunkBuilderThreads = 0;
        public DeferMode chunkBuildDeferMode = DeferMode.ALWAYS;

        public boolean animateOnlyVisibleTextures = true;
        public boolean useEntityCulling = true;
        public boolean useFogOcclusion = true;
        public boolean useBlockFaceCulling = true;
        public boolean useNoErrorGLContext = true;

        public InactivityFpsLimitMode inactivityFpsLimit = InactivityFpsLimitMode.AFK;
        public QuadSplittingMode quadSplittingMode = QuadSplittingMode.SAFE;
    }

    public static class AdvancedSettings {
        public boolean enableMemoryTracing = false;
    }

    public static class SodkamSettings {
        // AI Super Resolution & Reconstruction
        public UpscaleQuality superResolution = UpscaleQuality.NATIVE;
        public UpscalerType upscaler = UpscalerType.FSR;
        public int sharpness = 35;

        // Frame Generation & Latency
        public net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.FrameGenMode frameGen = net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.FrameGenMode.OFF;
        public boolean frameGeneration = false;
        public net.caffeinemc.mods.sodium.client.render.chunk.vulkan.latency.LowLatencyMode lowLatency = net.caffeinemc.mods.sodium.client.render.chunk.vulkan.latency.LowLatencyMode.OFF;

        // Ray Tracing & Real-Time Lighting
        public net.caffeinemc.mods.sodium.client.render.chunk.vulkan.rt.RayTracingMode rayTracing = net.caffeinemc.mods.sodium.client.render.chunk.vulkan.rt.RayTracingMode.OFF;

        // Next-Gen GPU Pipeline & Meshlets
        public boolean meshShaders = false;
        public boolean hiZOcclusionCulling = true;
        public boolean gpuCulling = true;

        // Advanced VRS & Memory
        public net.caffeinemc.mods.sodium.client.render.chunk.vulkan.vrs.VrsMode vrsMode = net.caffeinemc.mods.sodium.client.render.chunk.vulkan.vrs.VrsMode.OFF;
        public boolean variableRateShading = false;
        public boolean asyncComputeParticles = true;
        public boolean virtualTexturing = false;
    }

    public static class AnimationSettings {
        public boolean allAnimations = true;
        public boolean animatedWater = true;
        public boolean animatedLava = true;
        public boolean animatedFire = true;
        public boolean animatedPortal = true;
        public boolean animatedRedstone = true;
        public boolean animatedExplosion = true;
        public boolean animatedFlame = true;
        public boolean animatedSmoke = true;
    }

    public static class ParticleSettings {
        public boolean rainSplash = true;
        public boolean smoke = true;
        public boolean blockBreak = true;
        public boolean fireworks = true;
        public boolean potions = true;
        public boolean explosions = true;
        public boolean drips = true;
        public boolean other = true;
    }

    public static class DetailSettings {
        public boolean sky = true;
        public boolean stars = true;
        public boolean sunMoon = true;
        public boolean weather = true;
        public boolean vignette = true;
        public boolean heldItemTooltips = true;
    }

    public static class RenderSettings {
        public boolean fog = true;
        public int fogDistance = 100;
        public boolean staticEntities = true;
        public int entityDistance = 100;
        public boolean beaconBeams = true;
    }

    public static class ExtraSettings {
        public boolean fpsHud = false;
        public boolean coordsHud = false;
        public boolean advancementToasts = true;
        public boolean recipeToasts = true;
        public boolean systemToasts = true;
        public boolean clouds = true;
        public int cloudHeight = 192;
    }

    public static class DebugSettings {
        public boolean terrainSortingEnabled = true;
    }

    public static class NotificationSettings {
        public boolean hasClearedDonationButton = false;
        public boolean hasSeenDonationPrompt = false;
        public boolean hasEditedFullscreenOption = false;
    }

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static SodiumOptions loadFromDisk() {
        Path path = getConfigPath();
        SodiumOptions config;

        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                config = GSON.fromJson(reader, SodiumOptions.class);
            } catch (IOException e) {
                throw new RuntimeException("Could not parse config", e);
            }
        } else {
            config = new SodiumOptions();
        }

        if (config.sodkam.frameGeneration && config.sodkam.frameGen == net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.FrameGenMode.OFF) {
            config.sodkam.frameGen = net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.FrameGenMode.X2;
        }

        try {
            writeToDisk(config);
        } catch (IOException e) {
            throw new RuntimeException("Couldn't update config file", e);
        }

        return config;
    }

    private static Path getConfigPath() {
        return PlatformRuntimeInformation.getInstance().getConfigDirectory()
                .resolve(DEFAULT_FILE_NAME);
    }

    public static void writeToDisk(SodiumOptions config) throws IOException {
        if (config.isReadOnly()) {
            // throws an IOException so that it is caught correctly when trying to save the config when it's locked
            throw new IOException("Config file is read-only");
        }

        Path path = getConfigPath();
        Path dir = path.getParent();

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        } else if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }

        FileUtil.writeTextRobustly(GSON.toJson(config), path);
    }

    public boolean isReadOnly() {
        return this.readOnly;
    }

    public void setReadOnly() {
        this.readOnly = true;
    }
}
