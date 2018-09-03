package com.badlogic.gdx.graphics.text.harfbuzz;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.freetype.FreeType;
import com.badlogic.gdx.graphics.text.FontSystem;

/**
 * Font system based on FreeType and HarfBuzz.
 *
 * Create only one instance per application!
 */
public class HBFontSystem implements FontSystem<HBGlyphLayout> {

    private final FreeType.Library freeTypeLibrary;

    public HBFontSystem() {
        this.freeTypeLibrary = FreeType.initFreeType();
    }

    /**
     * Create incrementally built font of given dimensions.
     * Needed glyphs will be rasterized from the font on demand.
     *
     * @param font font supported by TrueType, such as ttf or otf
     * @param size of the font (height), in arbitrary units that will be used for public HBGlyphLayout measures
     * @param density physical pixels per one unit of size. Measurements will be aligned to these pixels to achieve
     *               pixel-perfect look.
     * @return created font
     */
    public HBFont createIncrementalFont(FileHandle font, float size, float density) {
        final int pixelSize = Math.round(size * density);

        return null;//TODO
    }

    @Override
    public HBGlyphLayout createGlyphLayout() {
        return new HBGlyphLayout();
    }
}
