package net.caffeinemc.mods.sodium.client.gui.widgets;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.ExternalPage;
import net.caffeinemc.mods.sodium.client.config.structure.ModOptions;
import net.caffeinemc.mods.sodium.client.config.structure.OptionPage;
import net.caffeinemc.mods.sodium.client.config.structure.Page;
import net.caffeinemc.mods.sodium.client.gui.ColorTheme;
import net.caffeinemc.mods.sodium.client.gui.Colors;
import net.caffeinemc.mods.sodium.client.gui.Layout;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.caffeinemc.mods.sodium.client.gui.options.control.AbstractScrollable;
import net.caffeinemc.mods.sodium.client.gui.options.control.ExternalButtonControl;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;

public class PageListWidget extends AbstractScrollable {
    private final VideoSettingsScreen parent;
    private EntryWidget selected;
    private final Reference2ReferenceMap<Page, PageEntryWidget<?>> pageToWidget = new Reference2ReferenceOpenHashMap<>();

    public PageListWidget(Dim2i position, VideoSettingsScreen parent) {
        super(position);
        this.parent = parent;
        this.rebuild();
    }

    private void rebuild() {
        int x = this.getX();
        int y = this.getY();
        int width = this.getWidth();
        int height = this.getHeight();

        this.clearChildren();
        this.scrollbar = this.addRenderableChild(new ScrollbarWidget(new Dim2i(this.getLimitX() - Layout.SCROLLBAR_WIDTH, y, Layout.SCROLLBAR_WIDTH, height), false, false));

        int entryHeight = Layout.entryHeight(this.font);
        var headerHeight = Layout.pageHeaderHeight(this.font);
        int listHeight = 0;
        for (var modOptions : ConfigManager.CONFIG.getModOptions()) {
            if (modOptions.pages().isEmpty()) {
                continue;
            }

            var theme = modOptions.theme();

            // spacing above the mod title
            listHeight += Layout.TEXT_LINE_SPACING;
            var headerDim = new Dim2i(x, y + listHeight, width, headerHeight);
            var modHeaderStart = headerDim.y();
            CenteredFlatWidget header = new HeaderEntryWidget(headerDim, modOptions, theme);
            listHeight += headerHeight;

            this.addRenderableChild(header);

            for (Page page : modOptions.pages()) {
                PageEntryWidget<?> pageWidget;
                Dim2i widgetDim = new Dim2i(x, y + listHeight, width, entryHeight);

                var scrollTargetStart = widgetDim.y();
                if (modHeaderStart != -1) {
                    scrollTargetStart = modHeaderStart; // scroll to the mod header if the page is the first in the mod
                    modHeaderStart = -1;
                }

                if (page instanceof OptionPage optionPage) {
                    pageWidget = new OptionPageEntryWidget(widgetDim, optionPage, theme, scrollTargetStart);
                } else if (page instanceof ExternalPage externalPage) {
                    pageWidget = new ExternalPageEntryWidget(widgetDim, externalPage, theme, scrollTargetStart);
                } else {
                    throw new IllegalStateException("Unknown page type: " + page.getClass());
                }

                this.pageToWidget.put(page, pageWidget);
                listHeight += entryHeight;

                this.addRenderableChild(pageWidget);
            }
        }

        this.scrollbar.setScrollbarContext(listHeight + Layout.INNER_MARGIN);
    }

    @Override
    public void extractRenderState(@NonNull GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        renderBackgroundGradient(graphics, this.getX(), this.getY(), this.getLimitX(), this.getLimitY());
        graphics.enableScissor(this.getX(), this.getY(), this.getLimitX(), this.getLimitY());
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.disableScissor();

        // Тонкий вертикальный акцентный разделитель справа
        graphics.fill(this.getLimitX() - 1, this.getY(), this.getLimitX(), this.getLimitY(), Colors.PANEL_DIVIDER);
    }

    public static void renderBackgroundGradient(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2) {
        graphics.fillGradient(x1, y1, x2, y2, Colors.BACKGROUND_DEFAULT, Colors.BACKGROUND_DARKER);
        // Тонкая окантовка панели слева и сверху для глубины
        graphics.fill(x1, y1, x2, y1 + 1, Colors.PANEL_BORDER_SUBTLE);
        graphics.fill(x1, y2 - 1, x2, y2, Colors.PANEL_BORDER_SUBTLE);
        graphics.fill(x1, y1, x1 + 1, y2, Colors.PANEL_BORDER_SUBTLE);
    }

    private void switchSelectedWidget(EntryWidget widget) {
        if (widget != this.selected) {
            if (this.selected != null) {
                this.selected.setSelected(false);
            }
            this.selected = widget;
            this.selected.setSelected(true);
        }

        // scroll into view if not currently visible in the page list
        int widgetTop = this.selected.getScrollTargetStart();
        int widgetBottom = widgetTop + this.selected.getHeight();
        int viewTop = this.getY() + this.scrollbar.getScrollAmount();
        int viewBottom = viewTop + this.getHeight();
        if (widgetTop < viewTop) {
            this.scrollbar.scrollTo(widgetTop - this.getY());
        } else if (widgetBottom > viewBottom) {
            this.scrollbar.scrollTo(widgetBottom - this.getY() - this.getHeight());
        }
    }

    public void switchSelected(Page page) {
        this.switchSelectedWidget(this.pageToWidget.get(page));
    }

    private abstract class EntryWidget extends CenteredFlatWidget {
        EntryWidget(Dim2i dim, Component label, boolean isSelectable, ColorTheme theme) {
            super(dim, label, isSelectable, theme);
        }

        EntryWidget(Dim2i dim, Component label, Component subtitle, boolean isSelectable, ColorTheme theme) {
            super(dim, label, subtitle, isSelectable, theme);
        }

        public int getScrollTargetStart() {
            return super.getY();
        }

        @Override
        public int getY() {
            return super.getY() - PageListWidget.this.scrollbar.getScrollAmount();
        }
    }

    private abstract class ClickableEntryWidget extends EntryWidget {
        ClickableEntryWidget(Dim2i dim, Component label, boolean isSelectable, ColorTheme theme) {
            super(dim, label, isSelectable, theme);
        }

        ClickableEntryWidget(Dim2i dim, Component label, Component subtitle, boolean isSelectable, ColorTheme theme) {
            super(dim, label, subtitle, isSelectable, theme);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);

            if (this.isHovered()) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }
    }

    private class HeaderEntryWidget extends ClickableEntryWidget {
        private final ModOptions modOptions;
        private final Identifier icon;
        private final boolean iconMonochrome;

        HeaderEntryWidget(Dim2i dim, ModOptions modOptions, ColorTheme theme) {
            super(dim, Component.literal(modOptions.name()), Component.literal(modOptions.version() + " • Vulkan"), false, theme);
            this.modOptions = modOptions;
            this.icon = modOptions.icon();
            this.iconMonochrome = modOptions.iconMonochrome();
        }

        @Override
        protected int renderIcon(GuiGraphicsExtractor graphics, int textColor) {
            if (this.icon == null) {
                return super.renderIcon(graphics, textColor);
            }

            return VideoSettingsScreen.renderIconWithSpacing(graphics, this.icon, textColor, this.iconMonochrome,
                    this.getX(), this.getY(), this.getHeight(), Layout.ICON_MARGIN);
        }

        @Override
        void onAction() {
            var pages = this.modOptions.pages();
            if (pages.isEmpty()) {
                return;
            }

            var firstPage = pages.getFirst();
            var firstPageWidget = PageListWidget.this.pageToWidget.get(firstPage);
            if (firstPageWidget != null) {
                PageListWidget.this.switchSelectedWidget(firstPageWidget);
            }
            PageListWidget.this.parent.jumpToPage(firstPage);
        }
    }

    private abstract class PageEntryWidget<P extends Page> extends ClickableEntryWidget {
        final P page;
        final int scrollTargetStart;

        PageEntryWidget(Dim2i dim, P page, Component label, ColorTheme theme, int scrollTargetStart) {
            super(dim, label, true, theme);
            this.page = page;
            this.scrollTargetStart = scrollTargetStart;
        }
    }

    private class OptionPageEntryWidget extends PageEntryWidget<Page> {
        private static final Identifier ICON_GENERAL = Identifier.fromNamespaceAndPath("sodium", "textures/gui/icons/general.png");
        private static final Identifier ICON_QUALITY = Identifier.fromNamespaceAndPath("sodium", "textures/gui/icons/quality.png");
        private static final Identifier ICON_PERFORMANCE = Identifier.fromNamespaceAndPath("sodium", "textures/gui/icons/performance.png");
        private static final Identifier ICON_VULDIUM = Identifier.fromNamespaceAndPath("sodium", "textures/gui/icons/vuldium.png");
        private static final Identifier ICON_ANIMATIONS = Identifier.fromNamespaceAndPath("sodium", "textures/gui/icons/animations.png");
        private static final Identifier ICON_PARTICLES = Identifier.fromNamespaceAndPath("sodium", "textures/gui/icons/particles.png");
        private static final Identifier ICON_DETAILS = Identifier.fromNamespaceAndPath("sodium", "textures/gui/icons/details.png");
        private static final Identifier ICON_RENDER = Identifier.fromNamespaceAndPath("sodium", "textures/gui/icons/render.png");
        private static final Identifier ICON_EXTRA = Identifier.fromNamespaceAndPath("sodium", "textures/gui/icons/extra.png");
        private static final Identifier ICON_DEFAULT = Identifier.fromNamespaceAndPath("sodium", "textures/gui/icons/default.png");

        private final Identifier iconTexture;

        OptionPageEntryWidget(Dim2i dim, Page page, ColorTheme theme, int scrollTargetStart) {
            super(dim, page, page.name(), theme, scrollTargetStart);
            this.iconTexture = resolvePageIcon(page);
        }

        private static Identifier resolvePageIcon(Page page) {
            String raw = page.name().getString().toLowerCase();
            if (raw.contains("general") || raw.contains("общие")) {
                return ICON_GENERAL;
            } else if (raw.contains("quality") || raw.contains("качество")) {
                return ICON_QUALITY;
            } else if (raw.contains("perf") || raw.contains("производ")) {
                return ICON_PERFORMANCE;
            } else if (raw.contains("vuldium") || raw.contains("вульдиум") || raw.contains("vulkan") || raw.contains("содкам") || raw.contains("vuldium")) {
                return ICON_VULDIUM;
            } else if (raw.contains("anim") || raw.contains("анимац")) {
                return ICON_ANIMATIONS;
            } else if (raw.contains("partic") || raw.contains("частиц")) {
                return ICON_PARTICLES;
            } else if (raw.contains("detail") || raw.contains("детал")) {
                return ICON_DETAILS;
            } else if (raw.contains("render") || raw.contains("рендер") || raw.contains("прорисов")) {
                return ICON_RENDER;
            } else if (raw.contains("extra") || raw.contains("экстра") || raw.contains("дополн")) {
                return ICON_EXTRA;
            }
            return ICON_DEFAULT;
        }

        @Override
        protected int renderIcon(GuiGraphicsExtractor graphics, int textColor) {
            if (this.iconTexture != null) {
                int iconX = this.getX() + 6;
                int iconY = this.getY() + (this.getHeight() - 16) / 2;
                graphics.blit(RenderPipelines.GUI_TEXTURED, this.iconTexture, iconX, iconY, 0, 0, 16, 16, 16, 16);
                return 6 + 16 + 6;
            }
            return super.renderIcon(graphics, textColor);
        }

        @Override
        public int getScrollTargetStart() {
            return this.scrollTargetStart;
        }

        @Override
        void onAction() {
            PageListWidget.this.switchSelectedWidget(this);
            PageListWidget.this.parent.jumpToPage(this.page);
        }
    }

    private class ExternalPageEntryWidget extends PageEntryWidget<ExternalPage> {
        ExternalPageEntryWidget(Dim2i dim, ExternalPage page, ColorTheme theme, int scrollTargetStart) {
            super(dim, page, Component.literal(ExternalButtonControl.EXTERNAL_PAGE_PREFIX).append(page.name()), theme, scrollTargetStart);
        }

        @Override
        void onAction() {
            this.page.currentScreenConsumer().accept(PageListWidget.this.parent);
        }
    }
}
