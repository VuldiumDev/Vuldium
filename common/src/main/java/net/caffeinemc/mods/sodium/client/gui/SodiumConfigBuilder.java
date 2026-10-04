package net.caffeinemc.mods.sodium.client.gui;

import com.mojang.blaze3d.platform.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.*;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.compatibility.workarounds.Workarounds;
import net.caffeinemc.mods.sodium.client.gui.options.FullscreenMode;
import net.caffeinemc.mods.sodium.client.gui.options.InactivityFpsLimitMode;
import net.caffeinemc.mods.sodium.client.gui.options.Toggle;
import net.caffeinemc.mods.sodium.client.gui.options.control.ControlValueFormatterImpls;
import net.caffeinemc.mods.sodium.client.gpu.device.vulkan.capabilities.UpscalerType;
import net.caffeinemc.mods.sodium.client.render.chunk.DeferMode;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.QuadSplittingMode;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.FrameGenMode;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.upscale.UpscaleQuality;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.rt.RayTracingMode;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.latency.LowLatencyMode;
import net.caffeinemc.mods.sodium.client.render.chunk.vulkan.vrs.VrsMode;
import net.minecraft.SharedConstants;
import net.minecraft.client.*;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;

// TODO: get initialValue from the vanilla options (it's private)
public class SodiumConfigBuilder implements ConfigEntryPoint {
    private static final Identifier SODIUM_ICON = Identifier.fromNamespaceAndPath("sodium", "textures/gui/config-icon.png");
    private static final SodiumOptions DEFAULTS = SodiumOptions.defaults();

    private final Options vanillaOpts;
    private final StorageEventHandler vanillaStorage;
    private final SodiumOptions sodiumOpts;
    private final StorageEventHandler sodiumStorage;

    private final @Nullable Window window;

    public SodiumConfigBuilder() {
        var minecraft = Minecraft.getInstance();
        this.window = minecraft.getWindow();

        this.vanillaOpts = minecraft.options;
        this.vanillaStorage = this.vanillaOpts == null ? null : () -> {
            this.vanillaOpts.save();

            SodiumClientMod.logger().info("Flushed changes to Minecraft configuration");
        };

        this.sodiumOpts = SodiumClientMod.options();
        this.sodiumStorage = () -> {
            try {
                SodiumOptions.writeToDisk(this.sodiumOpts);
            } catch (IOException e) {
                throw new RuntimeException("Couldn't save configuration changes", e);
            }

            SodiumClientMod.logger().info("Flushed changes to Sodium configuration");
        };
    }

    private Monitor getMonitor() {
        if (this.window == null) {
            return null;
        }
        return this.window.findBestMonitor();
    }

    public static void registerIcon(TextureManager textureManager) {
        textureManager.registerAndLoad(SODIUM_ICON, new SodiumLogo());
    }

    static class SodiumLogo extends ReloadableTexture {
        public SodiumLogo() {
            super(SODIUM_ICON);
        }

        @Override
        public TextureContents loadContents(ResourceManager resourceManager) throws IOException {
            try (InputStream inputStream = SodiumConfigBuilder.class.getResourceAsStream("/config-icon.png")) {
                return new TextureContents(NativeImage.read(inputStream), new TextureMetadataSection(false, false, MipmapStrategy.AUTO, 0.1f));
            }
        }
    }

    @Override
    public void registerConfigEarly(ConfigBuilder builder) {
        new SodiumConfigBuilder().buildEarlyConfig(builder);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        new SodiumConfigBuilder().buildFullConfig(builder);
    }

    private static ModOptionsBuilder createModOptionsBuilder(ConfigBuilder builder) {
        return builder.registerOwnModOptions()
                .setName("Vuldium")
                .setIcon(SODIUM_ICON)
                .formatVersion(version -> {
                    String base = "0.9.3";
                    if (version != null && !version.isEmpty()) {
                        base = version.split("[+-]")[0];
                    }
                    String mcVersion = SharedConstants.getCurrentVersion().name();
                    return base + "+" + mcVersion;
                });
    }

    private void buildEarlyConfig(ConfigBuilder builder) {
        createModOptionsBuilder(builder).addPage(
                builder.createOptionPage()
                        .setName(Component.translatable("sodium.options.pages.performance"))
                        .addOptionGroup(
                                builder.createOptionGroup()
                                        .addOption(this.buildNoErrorContextOption(builder))));
    }

    private void buildFullConfig(ConfigBuilder builder) {
        createModOptionsBuilder(builder)
                .setColorTheme(builder.createColorTheme().setFullThemeRGB(
                        Colors.THEME, Colors.THEME_LIGHTER, Colors.THEME_DARKER))
                .addPage(this.buildGeneralPage(builder))
                .addPage(this.buildQualityPage(builder))
                .addPage(this.buildPerformancePage(builder))
                .addPage(this.buildVuldiumPage(builder))
                .addPage(this.buildAnimationsPage(builder))
                .addPage(this.buildParticlesPage(builder))
                .addPage(this.buildDetailsPage(builder))
                .addPage(this.buildRenderPage(builder))
                .addPage(this.buildExtraPage(builder));
    }

    private OptionPageBuilder buildGeneralPage(ConfigBuilder builder) {
        var generalPage = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.general"));
        generalPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        // TODO: make RD option respect Vanilla's >16 RD only allowed if memory >1GB constraint
                        builder.createIntegerOption(Identifier.parse("sodium:general.render_distance"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.renderDistance"))
                                .setTooltip(Component.translatable("sodium.options.view_distance.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.translateVariable("options.chunks"))
                                .setRange(2, 32, 1)
                                .setDefaultValue(12)
                                .setBinding(this.vanillaOpts.renderDistance()::set, this.vanillaOpts.renderDistance()::get)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:general.simulation_distance"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.simulationDistance"))
                                .setTooltip(Component.translatable("sodium.options.simulation_distance.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.translateVariable("options.chunks"))
                                .setRange(5, 32, 1)
                                .setDefaultValue(12)
                                .setBinding(this.vanillaOpts.simulationDistance()::set, this.vanillaOpts.simulationDistance()::get)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:general.gamma"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.gamma"))
                                .setTooltip(Component.translatable("sodium.options.brightness.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.brightness())
                                .setRange(0, 100, 1)
                                .setDefaultValue(50)
                                .setBinding(value -> this.vanillaOpts.gamma().set(value * 0.01D), () -> (int) (this.vanillaOpts.gamma().get() / 0.01D))
                )
        );
        generalPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:general.gui_scale"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.guiScale"))
                                .setTooltip(Component.translatable("sodium.options.gui_scale.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.guiScale())
                                .setValidatorProvider((state) -> {
                                    var savedValue = state.readIntOption(Identifier.parse("sodium:general.gui_scale"));
                                    var realMax = this.window.calculateScale(0, Minecraft.getInstance().isEnforceUnicode());
                                    var presentationMax = Math.max(savedValue, realMax);
                                    return new GUIScaleRange(presentationMax);
                                }, ConfigState.UPDATE_ON_REBUILD, ConfigState.UPDATE_ON_APPLY)
                                .setDefaultValue(0)
                                .setBinding(this.vanillaOpts.guiScale()::set, this.vanillaOpts.guiScale()::get)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:general.fullscreen_mode"), FullscreenMode.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("sodium.options.fullscreen_mode.name"))
                                .setTooltip(Component.translatable("sodium.options.fullscreen_mode.tooltip"))
                                .setElementNameProvider(mode -> switch (mode) {
                                    case OFF -> Component.translatable("sodium.options.fullscreen_mode.off");
                                    case EXCLUSIVE ->
                                            Component.translatable("sodium.options.fullscreen_mode.exclusive");
                                    case BORDERLESS ->
                                            Component.translatable("sodium.options.fullscreen_mode.borderless");
                                })
                                .setDefaultValue(FullscreenMode.OFF)
                                .setImpact(OptionImpact.HIGH)
                                .setBinding(
                                        // modifies fullscreen and exclusive fullscreen together since they are interdependent in Vanilla's implementation
                                        value -> {
                                            switch (value) {
                                                case OFF -> this.vanillaOpts.fullscreen().set(false);
                                                case EXCLUSIVE -> {
                                                    this.vanillaOpts.fullscreen().set(true);
                                                    this.vanillaOpts.exclusiveFullscreen().set(true);
                                                }
                                                case BORDERLESS -> {
                                                    this.vanillaOpts.fullscreen().set(true);
                                                    this.vanillaOpts.exclusiveFullscreen().set(false);
                                                }
                                            }
                                        },
                                        () -> {
                                            boolean fullscreen = this.vanillaOpts.fullscreen().get();
                                            boolean exclusive = this.vanillaOpts.exclusiveFullscreen().get();
                                            if (fullscreen && exclusive) {
                                                return FullscreenMode.EXCLUSIVE;
                                            } else if (fullscreen) {
                                                return FullscreenMode.BORDERLESS;
                                            } else {
                                                return FullscreenMode.OFF;
                                            }
                                        })
                                .setApplyHook((_) -> {
                                })
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:general.fullscreen_resolution"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.fullscreen.resolution"))
                                .setTooltip(Component.translatable("sodium.options.fullscreen_resolution.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.resolution())
                                // the max value of 1 when the monitor is not available prevents an exception from being thrown
                                .setValidator(new FullscreenResolutionRange())
                                .setDefaultValue(0)
                                .setBinding(value -> {
                                    var monitor = this.getMonitor();
                                    if (monitor != null) {
                                        this.window.setPreferredFullscreenVideoMode(0 == value ? Optional.empty() : Optional.of(monitor.mode(value - 1)));
                                    }
                                }, () -> {
                                    var monitor = this.getMonitor();
                                    if (monitor == null) {
                                        return 0;
                                    } else {
                                        Optional<VideoMode> optional = this.window.getPreferredFullscreenVideoMode();
                                        return optional.map((videoMode) -> monitor.indexOfMode(videoMode) + 1).orElse(0);
                                    }
                                })
                                .setEnabledProvider(
                                        (state) -> {
                                            var monitor = this.getMonitor();
                                            if (monitor == null || monitor.modeCount() <= 0) {
                                                return false;
                                            }
                                            var fullscreenMode = state.readEnumOption(Identifier.parse("sodium:general.fullscreen_mode"), FullscreenMode.class);
                                            return fullscreenMode == FullscreenMode.EXCLUSIVE;
                                        },
                                        Identifier.parse("sodium:general.fullscreen_mode"))
                                .setFlags(OptionFlag.REQUIRES_VIDEOMODE_RELOAD)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:general.vsync"))
                                .setStorageHandler(() -> {
                                    if (this.vanillaStorage != null) {
                                        this.vanillaStorage.afterSave();
                                    }
                                    Minecraft.getInstance().invalidateSurfaceConfiguration();
                                })
                                .setName(Component.translatable("options.vsync"))
                                .setTooltip(Component.translatable("sodium.options.v_sync.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(
                                        value -> {
                                            this.vanillaOpts.enableVsync().set(value);
                                            Minecraft.getInstance().invalidateSurfaceConfiguration();
                                        },
                                        this.vanillaOpts.enableVsync()::get
                                )
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:general.framerate_limit"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.framerateLimit"))
                                .setTooltip(Component.translatable("sodium.options.fps_limit.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.fpsLimit())
                                .setRange(10, 260, 10)
                                .setDefaultValue(60)
                                .setBinding(this.vanillaOpts.framerateLimit()::set, this.vanillaOpts.framerateLimit()::get)
                )
        );
        generalPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:general.attack_indicator"), AttackIndicatorStatus.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.attackIndicator"))
                                .setTooltip(Component.translatable("sodium.options.attack_indicator.tooltip"))
                                .setDefaultValue(AttackIndicatorStatus.CROSSHAIR)
                                .setElementNameProvider(AttackIndicatorStatus::caption)
                                .setBinding(this.vanillaOpts.attackIndicator()::set, this.vanillaOpts.attackIndicator()::get)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:general.autosave_indicator"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.autosaveIndicator"))
                                .setTooltip(Component.translatable("sodium.options.autosave_indicator.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.showAutosaveIndicator()::set, this.vanillaOpts.showAutosaveIndicator()::get)
                )
        );

        var platformGroup = builder.createOptionGroup().addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath("sodium", "general.graphics_api"),
                        PreferredGraphicsApi.class)
                .setStorageHandler(this.vanillaStorage)
                .setName(Component.translatable("options.graphicsApi"))
                .setTooltip(i -> {
                    if (i == PreferredGraphicsApi.VULKAN) {
                        return Component.translatable("options.graphicsApi.tooltip.vulkan");
                    } else {
                        return Component.translatable("options.graphicsApi.tooltip");
                    }
                })
                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                        Component.translatable("options.graphicsApi.default"),
                        Component.translatable("options.graphicsApi.opengl"),
                        Component.literal("Prefer Vulkan")))
                .setDefaultValue(PreferredGraphicsApi.DEFAULT)
                .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                .setBinding((value) -> this.vanillaOpts.preferredGraphicsBackend().set(value), () -> this.vanillaOpts.preferredGraphicsBackend().get()));

        generalPage.addOptionGroup(platformGroup);

        return generalPage;
    }

    private OptionPageBuilder buildQualityPage(ConfigBuilder builder) {
        var qualityPage = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.quality"));

        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:quality.graphics"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.improvedTransparency"))
                                .setTooltip(Component.translatable("options.improvedTransparency.oit.tooltip"))
                                .setDefaultValue(false)
                                .setBinding(this.vanillaOpts.improvedTransparency()::set, this.vanillaOpts.improvedTransparency()::get)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
        );

        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.clouds"), CloudStatus.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.renderClouds"))
                                .setTooltip(Component.translatable("sodium.options.clouds_quality.tooltip"))
                                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                        Component.translatable("options.off"),
                                        Component.translatable("options.clouds.fast"),
                                        Component.translatable("options.clouds.fancy")))
                                .setDefaultValue(CloudStatus.FANCY)
                                .setBinding((value) -> {
                                    this.vanillaOpts.cloudStatus().set(value);
                                }, () -> this.vanillaOpts.cloudStatus().get())
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.render_cloud_distance"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.renderCloudsDistance"))
                                .setTooltip(Component.translatable("sodium.options.clouds_distance.tooltip"))
                                .setRange(2, 128, 2)
                                .setDefaultValue(128)
                                .setBinding((value) -> {
                                    this.vanillaOpts.cloudRange().set(value);

                                    Minecraft.getInstance().levelRenderer.cloudRenderer().markForRebuild();
                                }, () -> this.vanillaOpts.cloudRange().get())
                                .setImpact(OptionImpact.LOW)
                                .setValueFormatter(ControlValueFormatterImpls.translateVariable("options.chunks"))
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.weather"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.weatherRadius"))
                                .setTooltip(Component.translatable("options.weatherRadius.tooltip"))
                                .setDefaultValue(10)
                                .setRange(new Range(3, 10, 1))
                                .setValueFormatter(ControlValueFormatterImpls.number())
                                .setBinding(this.vanillaOpts.weatherRadius()::set, this.vanillaOpts.weatherRadius()::get)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:quality.leaves"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.cutoutLeaves"))
                                .setTooltip(Component.translatable("options.cutoutLeaves.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.cutoutLeaves()::set, this.vanillaOpts.cutoutLeaves()::get)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.particles"), ParticleStatus.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.particles"))
                                .setTooltip(Component.translatable("sodium.options.particle_quality.tooltip"))
                                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                        Component.translatable("options.particles.all"),
                                        Component.translatable("options.particles.decreased"),
                                        Component.translatable("options.particles.minimal")
                                ))
                                .setDefaultValue(ParticleStatus.ALL)
                                .setBinding(this.vanillaOpts.particles()::set, this.vanillaOpts.particles()::get)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:quality.ao"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.ao"))
                                .setTooltip(Component.translatable("sodium.options.smooth_lighting.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.ambientOcclusion()::set, this.vanillaOpts.ambientOcclusion()::get)
                                .setImpact(OptionImpact.LOW)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.biome_blend"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.biomeBlendRadius"))
                                .setValueFormatter(ControlValueFormatterImpls.biomeBlend())
                                .setTooltip(Component.translatable("sodium.options.biome_blend.tooltip"))
                                .setRange(0, 7, 1)
                                .setDefaultValue(2)
                                .setBinding(this.vanillaOpts.biomeBlendRadius()::set, this.vanillaOpts.biomeBlendRadius()::get)
                                .setImpact(OptionImpact.LOW)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.entity_distance"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.entityDistanceScaling"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setTooltip(Component.translatable("sodium.options.entity_distance.tooltip"))
                                .setRange(50, 500, 25)
                                .setDefaultValue(100)
                                .setBinding((value) -> this.vanillaOpts.entityDistanceScaling().set(value / 100.0), () -> Math.round(this.vanillaOpts.entityDistanceScaling().get().floatValue() * 100.0F))
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:quality.entity_shadows"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.entityShadows"))
                                .setTooltip(Component.translatable("sodium.options.entity_shadows.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.entityShadows()::set, this.vanillaOpts.entityShadows()::get)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:quality.vignette"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.vignette"))
                                .setTooltip(Component.translatable("options.vignette.tooltip"))
                                .setDefaultValue(true)
                                .setBinding(this.vanillaOpts.vignette()::set, this.vanillaOpts.vignette()::get)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.fade_time"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.chunkFade"))
                                .setTooltip(Component.translatable("options.chunkFade.tooltip"))
                                .setDefaultValue(750)
                                .setValueFormatter(ControlValueFormatterImpls.chunkFade())
                                .setRange(new Range(0, 2000, 50))
                                .setBinding(fade -> this.vanillaOpts.chunkSectionFadeInTime().set((double) fade / 1000.0), () -> (int) (this.vanillaOpts.chunkSectionFadeInTime().get() * 1000.0))
                )
        );

        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.mipmap_levels"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.mipmapLevels"))
                                .setValueFormatter(ControlValueFormatterImpls.multiplier())
                                .setTooltip(Component.translatable("sodium.options.mipmap_levels.tooltip"))
                                .setRange(0, 4, 1)
                                .setDefaultValue(4)
                                .setBinding(this.vanillaOpts.mipmapLevels()::set, this.vanillaOpts.mipmapLevels()::get)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                )
        );

        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.filtering_mode"), TextureFilteringMethod.class)
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.textureFiltering"))
                                .setTooltip(i -> Component.translatable("options.textureFiltering." + i.name().toLowerCase(Locale.ROOT) + ".tooltip"))
                                .setElementNameProvider(name -> {
                                    return Component.translatable("options.textureFiltering." + name.name().toLowerCase(Locale.ROOT));
                                })
                                .setDefaultValue(TextureFilteringMethod.RGSS)
                                .setBinding(this.vanillaOpts.textureFiltering()::set, this.vanillaOpts.textureFiltering()::get)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:quality.anisotropy_bit"))
                                .setStorageHandler(this.vanillaStorage)
                                .setName(Component.translatable("options.maxAnisotropy"))
                                .setRange(new Range(0, 3, 1))
                                .setTooltip(Component.translatable("options.maxAnisotropy.tooltip"))
                                .setDefaultValue(0)
                                .setValueFormatter(ControlValueFormatterImpls.anisotropyBit())
                                .setBinding(this.vanillaOpts.maxAnisotropyBit()::set, this.vanillaOpts.maxAnisotropyBit()::get)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                                .setEnabledProvider(i -> {
                                    return i.readEnumOption(Identifier.parse("sodium:quality.filtering_mode"), TextureFilteringMethod.class) == TextureFilteringMethod.ANISOTROPIC;
                                }, Identifier.parse("sodium:quality.filtering_mode"))
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.pixel_filtering_mode"), FilterMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.pixel_filtering_mode.name"))
                                .setTooltip(Component.translatable("sodium.options.pixel_filtering_mode.tooltip"))
                                .setElementNameProvider(filterMode ->
                                        Component.translatable("sodium.options.pixel_filtering_mode." + filterMode.name().toLowerCase(Locale.ROOT))
                                )
                                .setDefaultValue(FilterMode.NEAREST)
                                .setBinding(filterMode -> {
                                    this.sodiumOpts.quality.pixelFilteringMode = filterMode;
                                    Minecraft.getInstance().levelExtractor.resetSampler();
                                }, () -> this.sodiumOpts.quality.pixelFilteringMode)
                                .setImpact(OptionImpact.MEDIUM)
                )
        );

        qualityPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.hidden_fluid_culling"), Toggle.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.hidden_fluid_culling.name"))
                                .setTooltip(Component.translatable("sodium.options.hidden_fluid_culling.tooltip"))
                                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                        Component.translatable("sodium.options.hidden_fluid_culling.default"),
                                        Component.translatable("sodium.options.hidden_fluid_culling.optimized")))
                                .setImpact(OptionImpact.MEDIUM)
                                .setDefaultValue(Toggle.fromBoolean(DEFAULTS.quality.hiddenFluidCulling))
                                .setBinding(value -> this.sodiumOpts.quality.hiddenFluidCulling = value.toBoolean(), () -> Toggle.fromBoolean(this.sodiumOpts.quality.hiddenFluidCulling))
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.improved_fluid_shaping"), Toggle.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.improved_fluid_shaping.name"))
                                .setTooltip(Component.translatable("sodium.options.improved_fluid_shaping.tooltip"))
                                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                        Component.translatable("sodium.options.improved_fluid_shaping.default"),
                                        Component.translatable("sodium.options.improved_fluid_shaping.alternative")))
                                .setDefaultValue(Toggle.fromBoolean(DEFAULTS.quality.improvedFluidShaping))
                                .setBinding(value -> this.sodiumOpts.quality.improvedFluidShaping = value.toBoolean(), () -> Toggle.fromBoolean(this.sodiumOpts.quality.improvedFluidShaping))
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:quality.closest_point_entity_sort"), Toggle.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.closest_point_entity_sort.name"))
                                .setTooltip(Component.translatable("sodium.options.closest_point_entity_sort.tooltip"))
                                .setElementNameProvider(EnumOptionBuilder.nameProviderFrom(
                                        Component.translatable("sodium.options.closest_point_entity_sort.default"),
                                        Component.translatable("sodium.options.closest_point_entity_sort.enhanced")))
                                .setImpact(OptionImpact.MEDIUM)
                                .setDefaultValue(Toggle.fromBoolean(DEFAULTS.quality.useClosestPointEntitySort))
                                .setBinding(value -> this.sodiumOpts.quality.useClosestPointEntitySort = value.toBoolean(), () -> Toggle.fromBoolean(this.sodiumOpts.quality.useClosestPointEntitySort))
                )
        );
        return qualityPage;
    }

    private OptionPageBuilder buildPerformancePage(ConfigBuilder builder) {
        var performancePage = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.performance"));

        performancePage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:performance.chunk_update_threads"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.chunk_update_threads.name"))
                                .setValueFormatter(ControlValueFormatterImpls.quantityOrDisabled(
                                        (v) -> Component.translatable("sodium.options.chunk_update_threads.value", v),
                                        Component.translatable("sodium.options.default")
                                ))
                                .setTooltip(Component.translatable("sodium.options.chunk_update_threads.tooltip"))
                                .setRange(0, Runtime.getRuntime().availableProcessors(), 1)
                                .setDefaultValue(DEFAULTS.performance.chunkBuilderThreads)
                                .setBinding(value -> this.sodiumOpts.performance.chunkBuilderThreads = value, () -> this.sodiumOpts.performance.chunkBuilderThreads)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:performance.always_defer_chunk_updates"), DeferMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.defer_chunk_updates.name"))
                                .setTooltip(Component.translatable("sodium.options.defer_chunk_updates.tooltip"))
                                .setDefaultValue(DEFAULTS.performance.chunkBuildDeferMode)
                                .setBinding(value -> this.sodiumOpts.performance.chunkBuildDeferMode = value, () -> this.sodiumOpts.performance.chunkBuildDeferMode)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                )
        );

        performancePage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:performance.use_block_face_culling"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.use_block_face_culling.name"))
                                .setTooltip(Component.translatable("sodium.options.use_block_face_culling.tooltip"))
                                .setDefaultValue(DEFAULTS.performance.useBlockFaceCulling)
                                .setBinding(value -> this.sodiumOpts.performance.useBlockFaceCulling = value, () -> this.sodiumOpts.performance.useBlockFaceCulling)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:performance.use_fog_occlusion"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.use_fog_occlusion.name"))
                                .setTooltip(Component.translatable("sodium.options.use_fog_occlusion.tooltip"))
                                .setDefaultValue(DEFAULTS.performance.useFogOcclusion)
                                .setBinding(value -> this.sodiumOpts.performance.useFogOcclusion = value, () -> this.sodiumOpts.performance.useFogOcclusion)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:performance.use_entity_culling"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.use_entity_culling.name"))
                                .setTooltip(Component.translatable("sodium.options.use_entity_culling.tooltip"))
                                .setDefaultValue(DEFAULTS.performance.useEntityCulling)
                                .setBinding(value -> this.sodiumOpts.performance.useEntityCulling = value, () -> this.sodiumOpts.performance.useEntityCulling)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:performance.animate_only_visible_textures"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animate_only_visible_textures.name"))
                                .setTooltip(Component.translatable("sodium.options.animate_only_visible_textures.tooltip"))
                                .setDefaultValue(DEFAULTS.performance.animateOnlyVisibleTextures)
                                .setBinding(value -> this.sodiumOpts.performance.animateOnlyVisibleTextures = value, () -> this.sodiumOpts.performance.animateOnlyVisibleTextures)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_UPDATE)
                )
                .addOption(
                        this.buildNoErrorContextOption(builder)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:performance.inactivity_fps_limit"), InactivityFpsLimitMode.class)
                                .setStorageHandler(() -> {
                                    this.sodiumStorage.afterSave();
                                    if (this.vanillaStorage != null) {
                                        this.vanillaStorage.afterSave();
                                    }
                                })
                                .setName(Component.translatable("options.inactivityFpsLimit"))
                                .setElementNameProvider(mode -> switch (mode) {
                                    case OFF -> Component.translatable("sodium.options.inactivity_fps_limit.off");
                                    case MINIMIZED -> Component.translatable("options.inactivityFpsLimit.minimized");
                                    case AFK -> Component.translatable("options.inactivityFpsLimit.afk");
                                })
                                .setTooltip((state) -> switch (state) {
                                    case OFF -> Component.translatable("sodium.options.inactivity_fps_limit.off.tooltip");
                                    case MINIMIZED -> Component.translatable("options.inactivityFpsLimit.minimized.tooltip");
                                    case AFK -> Component.translatable("options.inactivityFpsLimit.afk.tooltip");
                                })
                                .setDefaultValue(InactivityFpsLimitMode.AFK)
                                .setBinding(
                                        mode -> {
                                            this.sodiumOpts.performance.inactivityFpsLimit = mode;
                                            if (mode == InactivityFpsLimitMode.AFK) {
                                                this.vanillaOpts.inactivityFpsLimit().set(InactivityFpsLimit.AFK);
                                            } else if (mode == InactivityFpsLimitMode.MINIMIZED) {
                                                this.vanillaOpts.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
                                            }
                                        },
                                        () -> {
                                            if (this.sodiumOpts.performance.inactivityFpsLimit == InactivityFpsLimitMode.OFF) {
                                                return InactivityFpsLimitMode.OFF;
                                            }
                                            return this.vanillaOpts.inactivityFpsLimit().get() == InactivityFpsLimit.MINIMIZED ?
                                                    InactivityFpsLimitMode.MINIMIZED : InactivityFpsLimitMode.AFK;
                                        }
                                )
                )
        );

        performancePage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("sodium:performance.quad_splitting"), QuadSplittingMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.quad_splitting.name"))
                                .setTooltip(Component.translatable("sodium.options.quad_splitting.tooltip"))
                                .setImpact(OptionImpact.MEDIUM)
                                .setDefaultValue(DEFAULTS.performance.quadSplittingMode)
                                .setBinding(value -> this.sodiumOpts.performance.quadSplittingMode = value, () -> this.sodiumOpts.performance.quadSplittingMode)
                                .setEnabled(SodiumClientMod.options().debug.terrainSortingEnabled)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
        );

        return performancePage;
    }

    private OptionPageBuilder buildVuldiumPage(ConfigBuilder builder) {
        var vuldiumPage = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.vuldium"));

        // Group 1: AI Super Resolution & Reconstruction
        vuldiumPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("vuldium:pipeline.super_resolution"), UpscaleQuality.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.super_resolution.name"))
                                .setTooltip(Component.translatable("vuldium.options.super_resolution.tooltip"))
                                .setElementNameProvider(quality -> Component.literal(quality.getDisplayName()))
                                .setDefaultValue(DEFAULTS.vuldium.superResolution)
                                .setBinding(value -> this.sodiumOpts.vuldium.superResolution = value, () -> this.sodiumOpts.vuldium.superResolution)
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("vuldium:pipeline.upscaler"), UpscalerType.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.upscaler.name"))
                                .setTooltip(Component.translatable("vuldium.options.upscaler.tooltip"))
                                .setElementNameProvider(upscaler -> Component.literal(upscaler.getDisplayName()))
                                .setDefaultValue(DEFAULTS.vuldium.upscaler)
                                .setBinding(value -> this.sodiumOpts.vuldium.upscaler = value, () -> this.sodiumOpts.vuldium.upscaler)
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("vuldium:pipeline.sharpness"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.sharpness.name"))
                                .setTooltip(Component.translatable("vuldium.options.sharpness.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setRange(0, 100, 5)
                                .setDefaultValue(DEFAULTS.vuldium.sharpness)
                                .setBinding(value -> this.sodiumOpts.vuldium.sharpness = value, () -> this.sodiumOpts.vuldium.sharpness)
                                .setImpact(OptionImpact.LOW)
                )
        );

        // Group 2: Ray Tracing & Real-Time Lighting (Hardware Ray Query)
        vuldiumPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("vuldium:rt.mode"), RayTracingMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.ray_tracing.name"))
                                .setTooltip(Component.translatable("vuldium.options.ray_tracing.tooltip"))
                                .setElementNameProvider(mode -> Component.literal(mode.getDisplayName()))
                                .setDefaultValue(DEFAULTS.vuldium.rayTracing)
                                .setBinding(value -> this.sodiumOpts.vuldium.rayTracing = value, () -> this.sodiumOpts.vuldium.rayTracing)
                                .setImpact(OptionImpact.VARIES)
                )
        );

        // Group 3: Next-Gen GPU Pipeline & Meshlets
        vuldiumPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("vuldium:pipeline.mesh_shaders"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.mesh_shaders.name"))
                                .setTooltip(Component.translatable("vuldium.options.mesh_shaders.tooltip"))
                                .setDefaultValue(DEFAULTS.vuldium.meshShaders)
                                .setBinding(value -> this.sodiumOpts.vuldium.meshShaders = value, () -> this.sodiumOpts.vuldium.meshShaders)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("vuldium:pipeline.hiz_culling"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.hiz_culling.name"))
                                .setTooltip(Component.translatable("vuldium.options.hiz_culling.tooltip"))
                                .setDefaultValue(DEFAULTS.vuldium.hiZOcclusionCulling)
                                .setBinding(value -> this.sodiumOpts.vuldium.hiZOcclusionCulling = value, () -> this.sodiumOpts.vuldium.hiZOcclusionCulling)
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("vuldium:pipeline.gpu_culling"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.gpu_culling.name"))
                                .setTooltip(Component.translatable("vuldium.options.gpu_culling.tooltip"))
                                .setDefaultValue(DEFAULTS.vuldium.gpuCulling)
                                .setBinding(value -> this.sodiumOpts.vuldium.gpuCulling = value, () -> this.sodiumOpts.vuldium.gpuCulling)
                                .setImpact(OptionImpact.HIGH)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
        );

        // Group 4: Latency & Frame Pacing
        vuldiumPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("vuldium:pipeline.frame_generation"), FrameGenMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.frame_generation.name"))
                                .setTooltip(Component.translatable("vuldium.options.frame_generation.tooltip"))
                                .setElementNameProvider(mode -> Component.literal(mode.getDisplayName()))
                                .setDefaultValue(DEFAULTS.vuldium.frameGen)
                                .setBinding(value -> {
                                    this.sodiumOpts.vuldium.frameGen = value;
                                    this.sodiumOpts.vuldium.frameGeneration = value.isEnabled();
                                }, () -> this.sodiumOpts.vuldium.frameGen)
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createEnumOption(Identifier.parse("vuldium:latency.low_latency"), LowLatencyMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.low_latency.name"))
                                .setTooltip(Component.translatable("vuldium.options.low_latency.tooltip"))
                                .setElementNameProvider(mode -> Component.literal(mode.getDisplayName()))
                                .setDefaultValue(DEFAULTS.vuldium.lowLatency)
                                .setBinding(value -> this.sodiumOpts.vuldium.lowLatency = value, () -> this.sodiumOpts.vuldium.lowLatency)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("vuldium:async.compute_particles"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.async_particles.name"))
                                .setTooltip(Component.translatable("vuldium.options.async_particles.tooltip"))
                                .setDefaultValue(DEFAULTS.vuldium.asyncComputeParticles)
                                .setBinding(value -> this.sodiumOpts.vuldium.asyncComputeParticles = value, () -> this.sodiumOpts.vuldium.asyncComputeParticles)
                                .setImpact(OptionImpact.MEDIUM)
                )
        );

        // Group 5: Advanced VRS & VRAM Management
        vuldiumPage.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createEnumOption(Identifier.parse("vuldium:pipeline.vrs_mode"), VrsMode.class)
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.vrs.name"))
                                .setTooltip(Component.translatable("vuldium.options.vrs.tooltip"))
                                .setElementNameProvider(mode -> Component.literal(mode.getDisplayName()))
                                .setDefaultValue(DEFAULTS.vuldium.vrsMode)
                                .setBinding(value -> {
                                    this.sodiumOpts.vuldium.vrsMode = value;
                                    this.sodiumOpts.vuldium.variableRateShading = value.isEnabled();
                                }, () -> this.sodiumOpts.vuldium.vrsMode)
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("vuldium:memory.virtual_texturing"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("vuldium.options.virtual_texturing.name"))
                                .setTooltip(Component.translatable("vuldium.options.virtual_texturing.tooltip"))
                                .setDefaultValue(DEFAULTS.vuldium.virtualTexturing)
                                .setBinding(value -> this.sodiumOpts.vuldium.virtualTexturing = value, () -> this.sodiumOpts.vuldium.virtualTexturing)
                                .setImpact(OptionImpact.MEDIUM)
                                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                )
        );

        return vuldiumPage;
    }

    private OptionBuilder buildNoErrorContextOption(ConfigBuilder builder) {
        return builder.createBooleanOption(Identifier.parse("sodium:performance.use_no_error_context"))
                .setStorageHandler(this.sodiumStorage)
                .setName(Component.translatable("sodium.options.use_no_error_context.name"))
                .setTooltip(Component.translatable("sodium.options.use_no_error_context.tooltip"))
                .setDefaultValue(DEFAULTS.performance.useNoErrorGLContext)
                .setBinding(value -> this.sodiumOpts.performance.useNoErrorGLContext = value, () -> this.sodiumOpts.performance.useNoErrorGLContext)
                .setEnabledProvider((state) -> {
                    if (!RenderSystem.getDevice().getDeviceInfo().backendName().contains("OpenGL")) return false;
                    GLCapabilities capabilities = GL.getCapabilities();
                    return (capabilities.OpenGL46 || capabilities.GL_KHR_no_error)
                            && !Workarounds.isWorkaroundEnabled(Workarounds.Reference.NO_ERROR_CONTEXT_UNSUPPORTED);
                })
                .setImpact(OptionImpact.LOW)
                .setFlags(OptionFlag.REQUIRES_GAME_RESTART);
    }

    private OptionPageBuilder buildAnimationsPage(ConfigBuilder builder) {
        var page = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.animations"));

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:animations.all_animations"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animations.all_animations.name"))
                                .setTooltip(Component.translatable("sodium.options.animations.all_animations.tooltip"))
                                .setDefaultValue(DEFAULTS.animations.allAnimations)
                                .setBinding(value -> this.sodiumOpts.animations.allAnimations = value, () -> this.sodiumOpts.animations.allAnimations)
                                .setImpact(OptionImpact.HIGH)
                )
        );

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:animations.water"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animations.water.name"))
                                .setTooltip(Component.translatable("sodium.options.animations.water.tooltip"))
                                .setDefaultValue(DEFAULTS.animations.animatedWater)
                                .setBinding(value -> this.sodiumOpts.animations.animatedWater = value, () -> this.sodiumOpts.animations.animatedWater)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:animations.lava"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animations.lava.name"))
                                .setTooltip(Component.translatable("sodium.options.animations.lava.tooltip"))
                                .setDefaultValue(DEFAULTS.animations.animatedLava)
                                .setBinding(value -> this.sodiumOpts.animations.animatedLava = value, () -> this.sodiumOpts.animations.animatedLava)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:animations.fire"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animations.fire.name"))
                                .setTooltip(Component.translatable("sodium.options.animations.fire.tooltip"))
                                .setDefaultValue(DEFAULTS.animations.animatedFire)
                                .setBinding(value -> this.sodiumOpts.animations.animatedFire = value, () -> this.sodiumOpts.animations.animatedFire)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:animations.portal"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animations.portal.name"))
                                .setTooltip(Component.translatable("sodium.options.animations.portal.tooltip"))
                                .setDefaultValue(DEFAULTS.animations.animatedPortal)
                                .setBinding(value -> this.sodiumOpts.animations.animatedPortal = value, () -> this.sodiumOpts.animations.animatedPortal)
                                .setImpact(OptionImpact.MEDIUM)
                )
        );

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:animations.redstone"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animations.redstone.name"))
                                .setTooltip(Component.translatable("sodium.options.animations.redstone.tooltip"))
                                .setDefaultValue(DEFAULTS.animations.animatedRedstone)
                                .setBinding(value -> this.sodiumOpts.animations.animatedRedstone = value, () -> this.sodiumOpts.animations.animatedRedstone)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:animations.explosion"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animations.explosion.name"))
                                .setTooltip(Component.translatable("sodium.options.animations.explosion.tooltip"))
                                .setDefaultValue(DEFAULTS.animations.animatedExplosion)
                                .setBinding(value -> this.sodiumOpts.animations.animatedExplosion = value, () -> this.sodiumOpts.animations.animatedExplosion)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:animations.flame"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animations.flame.name"))
                                .setTooltip(Component.translatable("sodium.options.animations.flame.tooltip"))
                                .setDefaultValue(DEFAULTS.animations.animatedFlame)
                                .setBinding(value -> this.sodiumOpts.animations.animatedFlame = value, () -> this.sodiumOpts.animations.animatedFlame)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:animations.smoke"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.animations.smoke.name"))
                                .setTooltip(Component.translatable("sodium.options.animations.smoke.tooltip"))
                                .setDefaultValue(DEFAULTS.animations.animatedSmoke)
                                .setBinding(value -> this.sodiumOpts.animations.animatedSmoke = value, () -> this.sodiumOpts.animations.animatedSmoke)
                                .setImpact(OptionImpact.LOW)
                )
        );

        return page;
    }

    private OptionPageBuilder buildParticlesPage(ConfigBuilder builder) {
        var page = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.particles"));

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:particles.rain_splash"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.particles.rain_splash.name"))
                                .setTooltip(Component.translatable("sodium.options.particles.rain_splash.tooltip"))
                                .setDefaultValue(DEFAULTS.particles.rainSplash)
                                .setBinding(value -> this.sodiumOpts.particles.rainSplash = value, () -> this.sodiumOpts.particles.rainSplash)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:particles.smoke"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.particles.smoke.name"))
                                .setTooltip(Component.translatable("sodium.options.particles.smoke.tooltip"))
                                .setDefaultValue(DEFAULTS.particles.smoke)
                                .setBinding(value -> this.sodiumOpts.particles.smoke = value, () -> this.sodiumOpts.particles.smoke)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:particles.drips"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.particles.drips.name"))
                                .setTooltip(Component.translatable("sodium.options.particles.drips.tooltip"))
                                .setDefaultValue(DEFAULTS.particles.drips)
                                .setBinding(value -> this.sodiumOpts.particles.drips = value, () -> this.sodiumOpts.particles.drips)
                                .setImpact(OptionImpact.LOW)
                )
        );

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:particles.block_break"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.particles.block_break.name"))
                                .setTooltip(Component.translatable("sodium.options.particles.block_break.tooltip"))
                                .setDefaultValue(DEFAULTS.particles.blockBreak)
                                .setBinding(value -> this.sodiumOpts.particles.blockBreak = value, () -> this.sodiumOpts.particles.blockBreak)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:particles.explosions"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.particles.explosions.name"))
                                .setTooltip(Component.translatable("sodium.options.particles.explosions.tooltip"))
                                .setDefaultValue(DEFAULTS.particles.explosions)
                                .setBinding(value -> this.sodiumOpts.particles.explosions = value, () -> this.sodiumOpts.particles.explosions)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:particles.fireworks"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.particles.fireworks.name"))
                                .setTooltip(Component.translatable("sodium.options.particles.fireworks.tooltip"))
                                .setDefaultValue(DEFAULTS.particles.fireworks)
                                .setBinding(value -> this.sodiumOpts.particles.fireworks = value, () -> this.sodiumOpts.particles.fireworks)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:particles.potions"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.particles.potions.name"))
                                .setTooltip(Component.translatable("sodium.options.particles.potions.tooltip"))
                                .setDefaultValue(DEFAULTS.particles.potions)
                                .setBinding(value -> this.sodiumOpts.particles.potions = value, () -> this.sodiumOpts.particles.potions)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:particles.other"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.particles.other.name"))
                                .setTooltip(Component.translatable("sodium.options.particles.other.tooltip"))
                                .setDefaultValue(DEFAULTS.particles.other)
                                .setBinding(value -> this.sodiumOpts.particles.other = value, () -> this.sodiumOpts.particles.other)
                                .setImpact(OptionImpact.MEDIUM)
                )
        );

        return page;
    }

    private OptionPageBuilder buildDetailsPage(ConfigBuilder builder) {
        var page = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.details"));

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:details.sky"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.details.sky.name"))
                                .setTooltip(Component.translatable("sodium.options.details.sky.tooltip"))
                                .setDefaultValue(DEFAULTS.details.sky)
                                .setBinding(value -> this.sodiumOpts.details.sky = value, () -> this.sodiumOpts.details.sky)
                                .setImpact(OptionImpact.MEDIUM)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:details.stars"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.details.stars.name"))
                                .setTooltip(Component.translatable("sodium.options.details.stars.tooltip"))
                                .setDefaultValue(DEFAULTS.details.stars)
                                .setBinding(value -> this.sodiumOpts.details.stars = value, () -> this.sodiumOpts.details.stars)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:details.sun_moon"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.details.sun_moon.name"))
                                .setTooltip(Component.translatable("sodium.options.details.sun_moon.tooltip"))
                                .setDefaultValue(DEFAULTS.details.sunMoon)
                                .setBinding(value -> this.sodiumOpts.details.sunMoon = value, () -> this.sodiumOpts.details.sunMoon)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:details.weather"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.details.weather.name"))
                                .setTooltip(Component.translatable("sodium.options.details.weather.tooltip"))
                                .setDefaultValue(DEFAULTS.details.weather)
                                .setBinding(value -> this.sodiumOpts.details.weather = value, () -> this.sodiumOpts.details.weather)
                                .setImpact(OptionImpact.MEDIUM)
                )
        );

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:details.vignette"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.details.vignette.name"))
                                .setTooltip(Component.translatable("sodium.options.details.vignette.tooltip"))
                                .setDefaultValue(DEFAULTS.details.vignette)
                                .setBinding(value -> this.sodiumOpts.details.vignette = value, () -> this.sodiumOpts.details.vignette)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:details.held_item_tooltips"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.details.held_item_tooltips.name"))
                                .setTooltip(Component.translatable("sodium.options.details.held_item_tooltips.tooltip"))
                                .setDefaultValue(DEFAULTS.details.heldItemTooltips)
                                .setBinding(value -> this.sodiumOpts.details.heldItemTooltips = value, () -> this.sodiumOpts.details.heldItemTooltips)
                                .setImpact(OptionImpact.LOW)
                )
        );

        return page;
    }

    private OptionPageBuilder buildRenderPage(ConfigBuilder builder) {
        var page = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.render"));

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:render.fog"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.render.fog.name"))
                                .setTooltip(Component.translatable("sodium.options.render.fog.tooltip"))
                                .setDefaultValue(DEFAULTS.render.fog)
                                .setBinding(value -> this.sodiumOpts.render.fog = value, () -> this.sodiumOpts.render.fog)
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:render.fog_distance"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.render.fog_distance.name"))
                                .setTooltip(Component.translatable("sodium.options.render.fog_distance.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setRange(50, 200, 10)
                                .setDefaultValue(DEFAULTS.render.fogDistance)
                                .setBinding(value -> this.sodiumOpts.render.fogDistance = value, () -> this.sodiumOpts.render.fogDistance)
                                .setImpact(OptionImpact.MEDIUM)
                )
        );

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:render.static_entities"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.render.static_entities.name"))
                                .setTooltip(Component.translatable("sodium.options.render.static_entities.tooltip"))
                                .setDefaultValue(DEFAULTS.render.staticEntities)
                                .setBinding(value -> this.sodiumOpts.render.staticEntities = value, () -> this.sodiumOpts.render.staticEntities)
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:render.entity_distance"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.render.entity_distance.name"))
                                .setTooltip(Component.translatable("sodium.options.render.entity_distance.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.percentage())
                                .setRange(50, 500, 25)
                                .setDefaultValue(DEFAULTS.render.entityDistance)
                                .setBinding(value -> {
                                    this.sodiumOpts.render.entityDistance = value;
                                    if (this.vanillaOpts != null) {
                                        this.vanillaOpts.entityDistanceScaling().set(value / 100.0);
                                    }
                                }, () -> this.sodiumOpts.render.entityDistance)
                                .setImpact(OptionImpact.HIGH)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:render.beacon_beams"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.render.beacon_beams.name"))
                                .setTooltip(Component.translatable("sodium.options.render.beacon_beams.tooltip"))
                                .setDefaultValue(DEFAULTS.render.beaconBeams)
                                .setBinding(value -> this.sodiumOpts.render.beaconBeams = value, () -> this.sodiumOpts.render.beaconBeams)
                                .setImpact(OptionImpact.LOW)
                )
        );

        return page;
    }

    private OptionPageBuilder buildExtraPage(ConfigBuilder builder) {
        var page = builder.createOptionPage().setName(Component.translatable("sodium.options.pages.extra"));

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:extra.fps_hud"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.extra.fps_hud.name"))
                                .setTooltip(Component.translatable("sodium.options.extra.fps_hud.tooltip"))
                                .setDefaultValue(DEFAULTS.extra.fpsHud)
                                .setBinding(value -> this.sodiumOpts.extra.fpsHud = value, () -> this.sodiumOpts.extra.fpsHud)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:extra.coords_hud"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.extra.coords_hud.name"))
                                .setTooltip(Component.translatable("sodium.options.extra.coords_hud.tooltip"))
                                .setDefaultValue(DEFAULTS.extra.coordsHud)
                                .setBinding(value -> this.sodiumOpts.extra.coordsHud = value, () -> this.sodiumOpts.extra.coordsHud)
                                .setImpact(OptionImpact.LOW)
                )
        );

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:extra.advancement_toasts"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.extra.advancement_toasts.name"))
                                .setTooltip(Component.translatable("sodium.options.extra.advancement_toasts.tooltip"))
                                .setDefaultValue(DEFAULTS.extra.advancementToasts)
                                .setBinding(value -> this.sodiumOpts.extra.advancementToasts = value, () -> this.sodiumOpts.extra.advancementToasts)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:extra.recipe_toasts"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.extra.recipe_toasts.name"))
                                .setTooltip(Component.translatable("sodium.options.extra.recipe_toasts.tooltip"))
                                .setDefaultValue(DEFAULTS.extra.recipeToasts)
                                .setBinding(value -> this.sodiumOpts.extra.recipeToasts = value, () -> this.sodiumOpts.extra.recipeToasts)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:extra.system_toasts"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.extra.system_toasts.name"))
                                .setTooltip(Component.translatable("sodium.options.extra.system_toasts.tooltip"))
                                .setDefaultValue(DEFAULTS.extra.systemToasts)
                                .setBinding(value -> this.sodiumOpts.extra.systemToasts = value, () -> this.sodiumOpts.extra.systemToasts)
                                .setImpact(OptionImpact.LOW)
                )
        );

        page.addOptionGroup(builder.createOptionGroup()
                .addOption(
                        builder.createBooleanOption(Identifier.parse("sodium:extra.clouds"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.extra.clouds.name"))
                                .setTooltip(Component.translatable("sodium.options.extra.clouds.tooltip"))
                                .setDefaultValue(DEFAULTS.extra.clouds)
                                .setBinding(value -> this.sodiumOpts.extra.clouds = value, () -> this.sodiumOpts.extra.clouds)
                                .setImpact(OptionImpact.LOW)
                )
                .addOption(
                        builder.createIntegerOption(Identifier.parse("sodium:extra.cloud_height"))
                                .setStorageHandler(this.sodiumStorage)
                                .setName(Component.translatable("sodium.options.extra.cloud_height.name"))
                                .setTooltip(Component.translatable("sodium.options.extra.cloud_height.tooltip"))
                                .setValueFormatter(ControlValueFormatterImpls.number())
                                .setRange(-64, 320, 8)
                                .setDefaultValue(DEFAULTS.extra.cloudHeight)
                                .setBinding(value -> this.sodiumOpts.extra.cloudHeight = value, () -> this.sodiumOpts.extra.cloudHeight)
                                .setImpact(OptionImpact.LOW)
                )
        );

        return page;
    }
}