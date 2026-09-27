package net.caffeinemc.mods.sodium.client.gui.options.control;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.caffeinemc.mods.sodium.client.config.structure.Option;
import net.caffeinemc.mods.sodium.client.gui.ColorTheme;
import net.caffeinemc.mods.sodium.client.gui.Colors;
import net.caffeinemc.mods.sodium.client.gui.Layout;
import net.caffeinemc.mods.sodium.client.util.Dim2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.Validate;

public class CyclingControl<T extends Enum<T>> implements Control {
    private final EnumOption<T> option;

    public CyclingControl(EnumOption<T> option, Class<T> enumType) {
        T[] universe = enumType.getEnumConstants();

        Validate.notEmpty(universe, "The enum universe must contain at least one item");

        this.option = option;
    }

    @Override
    public Option getOption() {
        return this.option;
    }

    @Override
    public ControlElement createElement(Screen screen, AbstractOptionList list, Dim2i dim, ColorTheme theme) {
        return new CyclingControlElement<>(list, this.option, dim, theme);
    }

    @Override
    public int getMaxWidth() {
        return Layout.CYCLING_CONTROL_WIDTH;
    }

    private static class CyclingControlElement<T extends Enum<T>> extends StatefulControlElement {
        private final EnumOption<T> option;
        private final T[] baseValues;

        public CyclingControlElement(AbstractOptionList list, EnumOption<T> option, Dim2i dim, ColorTheme theme) {
            super(list, dim, theme);

            this.option = option;
            this.baseValues = option.enumClass.getEnumConstants();
        }

        @Override
        public EnumOption<T> getOption() {
            return this.option;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            super.extractRenderState(graphics, mouseX, mouseY, delta);

            if (this.option.shouldHideControl() || this.isResetOverlayActive()) {
                return;
            }

            var value = this.option.getValidatedValue();
            Component name = this.option.getElementName(value);

            final int chipWidth = Layout.CYCLING_CONTROL_WIDTH;
            final int chipHeight = 15;
            final int chipX = this.getLimitX() - Layout.OPTION_TEXT_SIDE_PADDING - chipWidth;
            final int chipY = this.getCenterY() - chipHeight / 2;

            int chipBg = this.hovered ? 0xEE2A2522 : 0xEE1A1614;
            int highlight = this.hovered ? 0x80FFFFFF : 0x40FFFFFF;
            int shadow = 0xFF0A0706;

            // Внешняя окантовка и фоновая подложка Minecraft кнопки
            graphics.fill(chipX, chipY, chipX + chipWidth, chipY + chipHeight, 0xFF050505);
            graphics.fill(chipX + 1, chipY + 1, chipX + chipWidth - 1, chipY + chipHeight - 1, chipBg);

            // Объемная 3D фаска в стиле Minecraft (свет сверху/слева, тень снизу/справа)
            graphics.fill(chipX + 1, chipY + 1, chipX + chipWidth - 1, chipY + 2, highlight);
            graphics.fill(chipX + 1, chipY + 1, chipX + 2, chipY + chipHeight - 1, highlight);
            graphics.fill(chipX + 1, chipY + chipHeight - 2, chipX + chipWidth - 1, chipY + chipHeight - 1, shadow);
            graphics.fill(chipX + chipWidth - 2, chipY + 1, chipX + chipWidth - 1, chipY + chipHeight - 1, shadow);

            // Стрелочки ‹ и ›
            int arrowCol = this.hovered ? Colors.THEME_LIGHTER : 0xFF888888;
            int textY = this.getCenterY() + Layout.REGULAR_TEXT_BASELINE_OFFSET;
            graphics.text(this.font, "‹", chipX + 4, textY, arrowCol);
            graphics.text(this.font, "›", chipX + chipWidth - 8, textY, arrowCol);

            // Ограничение длины текста значения с добавлением '...' при необходимости
            int maxTextWidth = chipWidth - 22;
            String displayText = this.truncateTextToFit(name.getString(), maxTextWidth);
            int textW = this.font.width(displayText);
            int textX = chipX + (chipWidth - textW) / 2;
            int textCol = this.hovered ? 0xFFFFFFFF : 0xFFE0E0E0;

            // Тень текста и сам текст по центру
            graphics.text(this.font, displayText, textX + 1, textY + 1, 0xFF0F0B09);
            graphics.text(this.font, displayText, textX, textY, textCol);

            if (this.isHovered()) {
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (super.mouseClicked(event, doubleClick)) return true;
            if (this.isResetOverlayActive()) return false;

            if (this.option.isEnabled() && event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.isMouseOver(event.x(), event.y())) {
                this.cycleControl(Minecraft.getInstance().hasShiftDown());
                return true;
            }

            return false;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!this.isFocused()) return false;

            if (event.isSelection()) {
                this.cycleControl(Minecraft.getInstance().hasShiftDown());
                return true;
            }

            return false;
        }

        private void cycleControl(boolean reverse) {
            this.playClickSound();

            var currentValue = this.option.getValidatedValue();
            int startIndex = 0;
            for (; startIndex < this.baseValues.length; startIndex++) {
                if (this.baseValues[startIndex] == currentValue) {
                    break;
                }
            }

            // step through values in the specified direction until a valid one is found
            var currentIndex = startIndex;
            do {
                if (reverse) {
                    currentIndex = (currentIndex + this.baseValues.length - 1) % this.baseValues.length;
                } else {
                    currentIndex = (currentIndex + 1) % this.baseValues.length;
                }

                currentValue = this.baseValues[currentIndex];
            } while (!this.option.isValueAllowed(currentValue));
            this.option.modifyValue(currentValue);
        }
    }
}
