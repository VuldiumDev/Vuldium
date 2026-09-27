package net.caffeinemc.mods.sodium.client.gui;

import java.util.stream.Stream;

public class ColorTheme {
    public final int theme;
    public final int themeLighter;
    public final int themeDarker;
    
    public static final ColorTheme[] PRESETS = Stream.of(
            0xFFFF4D15, 0xFFFF7043, 0xFFFF3D00, 0xFFE64A19, 0xFFFF8A65
    ).map(ColorTheme::new).toArray(ColorTheme[]::new);

    public ColorTheme(int theme, int themeLighter, int themeDarker) {
        this.theme = theme;
        this.themeLighter = themeLighter;
        this.themeDarker = themeDarker;
    }

    public ColorTheme(int theme) {
        this(theme, Colors.lighten(theme), Colors.darken(theme));
    }
}
