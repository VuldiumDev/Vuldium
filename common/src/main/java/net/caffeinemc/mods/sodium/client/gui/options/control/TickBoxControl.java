package net.caffeinemc.mods.sodium.client.gui.options.control;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.caffeinemc.mods.sodium.client.config.structure.BooleanOption;
import net.caffeinemc.mods.sodium.client.config.structure.StatefulOption;
import net.caffeinemc.mods.sodium.client.gui.ColorTheme;
import net.caffeinemc.mods.sodium.client.gui.Colors;
import net.caffeinemc.mods.sodium.client.gui.Layout;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

public class TickBoxControl implements Control {
    private final BooleanOption option;

    public TickBoxControl(BooleanOption option) {
        this.option = option;
    }

    @Override
    public ControlElement createElement(Screen screen, AbstractOptionList list, Dim2i dim, ColorTheme theme) {
        return new TickBoxControlElement(list, this.option, dim, theme);
    }

    @Override
    public int getMaxWidth() {
        return Layout.TICKBOX_CONTROL_WIDTH;
    }

    @Override
    public StatefulOption<Boolean> getOption() {
        return this.option;
    }

    private static class TickBoxControlElement extends StatefulControlElement {
        private final BooleanOption option;

        public TickBoxControlElement(AbstractOptionList list, BooleanOption option, Dim2i dim, ColorTheme theme) {
            super(list, dim, theme);

            this.option = option;
        }

        @Override
        public BooleanOption getOption() {
            return this.option;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);

            if (this.option.shouldHideControl() || this.isResetOverlayActive()) {
                return;
            }

            final int btnWidth = 42;
            final int btnHeight = 15;
            final int x = this.getLimitX() - Layout.OPTION_TEXT_SIDE_PADDING - btnWidth;
            final int y = this.getCenterY() - btnHeight / 2;
            final int xEnd = x + btnWidth;
            final int yEnd = y + btnHeight;

            final boolean enabled = this.option.isEnabled();
            final boolean ticked = this.option.getValidatedValue();

            if (enabled) {
                if (ticked) {
                    // ВКЛЮЧЕНО: Minecraft 3D изумрудная кнопка с горящим индикатором и тенью текста
                    int bg = this.hovered ? 0xFF243E29 : 0xFF182A1B;
                    int hl = this.hovered ? 0xFF6ED882 : 0xFF4E9E5E;
                    int sh = 0xFF0A180E;

                    // Внешняя темная рамка и основа кнопки
                    graphics.fill(x, y, xEnd, yEnd, 0xFF050505);
                    graphics.fill(x + 1, y + 1, xEnd - 1, yEnd - 1, bg);

                    // 3D-фаска в стиле Minecraft (свет сверху/слева, тень снизу/справа)
                    graphics.fill(x + 1, y + 1, xEnd - 1, y + 2, hl);
                    graphics.fill(x + 1, y + 1, x + 2, yEnd - 1, hl);
                    graphics.fill(x + 1, yEnd - 2, xEnd - 1, yEnd - 1, sh);
                    graphics.fill(xEnd - 2, y + 1, xEnd - 1, yEnd - 1, sh);

                    // Углубленный сокет индикатора
                    graphics.fill(x + 4, y + 4, x + 11, y + 11, 0xFF09160B);

                    // Горящий кристалл/лампа
                    graphics.fill(x + 5, y + 5, x + 10, y + 10, 0xFF3AE265);
                    graphics.fill(x + 5, y + 9, x + 10, y + 10, 0xFF1D8C3E);
                    graphics.fill(x + 9, y + 5, x + 10, y + 10, 0xFF1D8C3E);
                    // Блик лампы
                    graphics.fill(x + 5, y + 5, x + 7, y + 7, 0xFFE0FFE8);

                    // Текст ON с эффектом тени Minecraft
                    String text = "ON";
                    int textW = this.font.width(text);
                    int textX = x + 13 + (btnWidth - 13 - textW) / 2;
                    int textY = this.getCenterY() + Layout.REGULAR_TEXT_BASELINE_OFFSET;
                    graphics.text(this.font, text, textX + 1, textY + 1, 0xFF08220D);
                    graphics.text(this.font, text, textX, textY, this.hovered ? 0xFF88FFA4 : 0xFF55FF55);
                } else {
                    // ВЫКЛЮЧЕНО: Minecraft 3D каменная кнопка с потухшим индикатором
                    int bg = this.hovered ? 0xFF2A2A2A : 0xFF1E1E1E;
                    int hl = this.hovered ? 0xFF777777 : 0xFF555555;
                    int sh = 0xFF0D0D0D;

                    // Внешняя темная рамка и основа кнопки
                    graphics.fill(x, y, xEnd, yEnd, 0xFF050505);
                    graphics.fill(x + 1, y + 1, xEnd - 1, yEnd - 1, bg);

                    // 3D-фаска в стиле Minecraft
                    graphics.fill(x + 1, y + 1, xEnd - 1, y + 2, hl);
                    graphics.fill(x + 1, y + 1, x + 2, yEnd - 1, hl);
                    graphics.fill(x + 1, yEnd - 2, xEnd - 1, yEnd - 1, sh);
                    graphics.fill(xEnd - 2, y + 1, xEnd - 1, yEnd - 1, sh);

                    // Углубленный сокет индикатора
                    graphics.fill(x + 4, y + 4, x + 11, y + 11, 0xFF111111);

                    // Потухшая лампа
                    graphics.fill(x + 5, y + 5, x + 10, y + 10, 0xFF333333);
                    graphics.fill(x + 5, y + 5, x + 10, y + 6, 0xFF484848);
                    graphics.fill(x + 5, y + 5, x + 6, y + 10, 0xFF484848);
                    graphics.fill(x + 5, y + 9, x + 10, y + 10, 0xFF222222);
                    graphics.fill(x + 9, y + 5, x + 10, y + 10, 0xFF222222);

                    // Текст OFF с эффектом тени Minecraft
                    String text = "OFF";
                    int textW = this.font.width(text);
                    int textX = x + 13 + (btnWidth - 13 - textW) / 2;
                    int textY = this.getCenterY() + Layout.REGULAR_TEXT_BASELINE_OFFSET;
                    graphics.text(this.font, text, textX + 1, textY + 1, 0xFF0D0D0D);
                    graphics.text(this.font, text, textX, textY, this.hovered ? 0xFFDDDDDD : 0xFFAAAAAA);
                }
            } else {
                // ОТКЛЮЧЕНО (Disabled)
                graphics.fill(x, y, xEnd, yEnd, 0xFF111111);
                graphics.fill(x, y, xEnd, y + 1, 0x20FFFFFF);
                graphics.fill(x, y, x + 1, yEnd, 0x20FFFFFF);
                graphics.fill(x, yEnd - 1, xEnd, yEnd, 0xFF050505);
                graphics.fill(xEnd - 1, y, xEnd, yEnd, 0xFF050505);
                String text = ticked ? "ON" : "OFF";
                int textW = this.font.width(text);
                graphics.text(this.font, text, x + (btnWidth - textW) / 2, this.getCenterY() + Layout.REGULAR_TEXT_BASELINE_OFFSET, 0xFF555555);
            }

            if (this.isHovered()) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (super.mouseClicked(event, doubleClick)) return true;
            if (this.isResetOverlayActive()) return false;

            if (this.option.isEnabled() && event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.isMouseOver(event.x(), event.y())) {
                this.toggleControl();
                return true;
            }

            return false;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!this.isFocused()) return false;

            if (event.isSelection()) {
                this.toggleControl();
                return true;
            }

            return false;
        }

        private void toggleControl() {
            this.playClickSound();

            this.option.modifyValue(!this.option.getValidatedValue());
        }
    }
}
