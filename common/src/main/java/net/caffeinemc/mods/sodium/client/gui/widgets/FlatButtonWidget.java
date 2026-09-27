package net.caffeinemc.mods.sodium.client.gui.widgets;

import com.mojang.blaze3d.platform.InputConstants;
import net.caffeinemc.mods.sodium.client.gui.ButtonTheme;
import net.caffeinemc.mods.sodium.client.gui.Colors;
import net.caffeinemc.mods.sodium.client.gui.Layout;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

public class FlatButtonWidget extends AbstractWidget implements Renderable {
    public static final ButtonTheme DEFAULT_THEME = new ButtonTheme(
            Colors.FOREGROUND, Colors.FOREGROUND, Colors.FOREGROUND_DISABLED,
            Colors.BACKGROUND_HOVER, Colors.BACKGROUND_DEFAULT, Colors.BACKGROUND_LIGHT);

    private final Runnable action;
    private final boolean drawBackground;
    private final boolean drawFrame;
    private final boolean leftAlign;
    private final ButtonTheme theme;
    private final Component label;

    private boolean selected;
    private boolean enabled = true;
    private boolean visible = true;

    public FlatButtonWidget(Dim2i dim, Component label, Runnable action, boolean drawBackground, boolean drawFrame, boolean leftAlign, ButtonTheme theme) {
        super(dim);
        this.label = label;
        this.action = action;
        this.drawBackground = drawBackground;
        this.drawFrame = drawFrame;
        this.leftAlign = leftAlign;
        this.theme = theme;
    }

    public FlatButtonWidget(Dim2i dim, Component label, Runnable action, boolean drawBackground, boolean leftAlign, ButtonTheme theme) {
        this(dim, label, action, drawBackground, !drawBackground, leftAlign, theme);
    }

    public FlatButtonWidget(Dim2i dim, Component label, Runnable action, boolean drawBackground, boolean leftAlign) {
        this(dim, label, action, drawBackground, leftAlign, DEFAULT_THEME);
    }

    public FlatButtonWidget(Dim2i dim, Component label, Runnable action, boolean drawBackground, boolean drawFrame, boolean leftAlign) {
        this(dim, label, action, drawBackground, drawFrame, leftAlign, DEFAULT_THEME);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (!this.visible) {
            return;
        }

        this.hovered = this.isMouseOver(mouseX, mouseY);

        int x1 = this.getX();
        int y1 = this.getY();
        int x2 = this.getLimitX();
        int y2 = this.getLimitY();

        int backgroundColor = this.enabled ? (this.hovered ? this.theme.bgHighlight : this.theme.bgDefault) : this.theme.bgInactive;
        int textColor = this.getTextColor();

        if (this.drawBackground) {
            // Майнкрафтовская кнопка с объемной 3D-фаской
            this.drawRect(graphics, x1, y1, x2, y2, backgroundColor);

            // Внешняя пиксельная тень снизу и справа
            graphics.fill(x1, y2 - 1, x2, y2, 0xFF000000);
            graphics.fill(x2 - 1, y1, x2, y2, 0xFF000000);

            // Внутренний световой блик сверху и слева (Minecraft Highlight)
            int highlightColor = this.enabled ? (this.hovered ? Colors.THEME_LIGHTER : 0x55FFFFFF) : 0x20FFFFFF;
            graphics.fill(x1, y1, x2, y1 + 1, highlightColor);
            graphics.fill(x1, y1, x1 + 1, y2, highlightColor);

            // Внутренняя тень снизу и справа (Minecraft Shadow)
            int shadowColor = this.enabled ? (this.hovered ? 0x90000000 : 0xB0000000) : 0x70000000;
            graphics.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, shadowColor);
            graphics.fill(x2 - 2, y1 + 1, x2 - 1, y2 - 1, shadowColor);
        }

        if (this.label != null) {
            Component rendered = this.getRenderedLabel();
            int strWidth = this.font.width(rendered);
            int textX = this.leftAlign ? x1 + Layout.TEXT_LEFT_PADDING : (this.getCenterX() - (strWidth / 2));
            int textY = this.getCenterY() - this.font.lineHeight / 2;
            this.drawString(graphics, rendered, textX, textY, textColor);
        }

        if (this.enabled && this.selected) {
            this.drawRect(graphics, x1, y2 - 2, x2, y2, Colors.THEME);
        }

        if (this.drawFrame || this.enabled && this.isFocused()) {
            this.drawBorder(graphics, x1, y1, x2, y2, Colors.BUTTON_BORDER);
        }
    }

    protected int getTextColor() {
        return this.enabled ? this.theme.themeLighter : this.theme.themeDarker;
    }

    protected Component getRenderedLabel() {
        return this.label;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.enabled || !this.visible) {
            return false;
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.isMouseOver(event.x(), event.y())) {
            this.doAction();

            return true;
        }

        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!this.isFocused())
            return false;

        if (event.isSelection()) {
            this.doAction();
            return true;
        }

        return false;
    }

    protected void doAction() {
        this.action.run();
        this.playClickSound();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent event) {
        if (!this.enabled || !this.visible)
            return null;
        return super.nextFocusPath(event);
    }

    public boolean isVisible() {
        return this.visible;
    }

    public boolean isEnabled() {
        return this.enabled;
    }
}
