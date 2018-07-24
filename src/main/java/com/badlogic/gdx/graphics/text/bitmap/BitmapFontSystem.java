package com.badlogic.gdx.graphics.text.bitmap;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.text.FontSystem;
import com.badlogic.gdx.utils.GdxRuntimeException;

/**
 * System for simple, "legacy" style, bitmap fonts, described by .fnt files.
 */
public class BitmapFontSystem implements FontSystem<BitmapGlyphLayout> {

    /**
     * This system needs no initialization, so it is exposed as stateless singleton.
     */
    private BitmapFontSystem() {
    }

    /**
     * The only instance of this font system.
     */
    public static final BitmapFontSystem INSTANCE = new BitmapFontSystem();

    /**
     * @param fnt .fnt file describing the font
     * @param pixelsPerPoint see {@link BitmapFont#loadGlyphs(FileHandle, float)}
     * @param fallback font, to use when glyphs are missing, or null
     */
    public BitmapFont createFont(FileHandle fnt, float pixelsPerPoint, BitmapFont fallback) {
        final BitmapFont font = new BitmapFont(fnt.nameWithoutExtension(), fallback);
        // Load glyphs
        final String[] pagePaths = font.loadGlyphs(fnt, pixelsPerPoint);

        // Load textures
        final TextureRegion[] regions = new TextureRegion[pagePaths.length];
        final FileHandle parent = fnt.parent();
        for (int i = 0; i < pagePaths.length; i++) {
            final Texture texture = new Texture(parent.child(pagePaths[i]), Pixmap.Format.RGBA8888, false);
            regions[i] = new TextureRegion(texture);
        }
        font.loadPages(regions, true);

        return font;
    }

    /**
     * @param fnt .fnt file describing the font
     * @param pixelsPerPoint see {@link BitmapFont#loadGlyphs(FileHandle, float)}
     * @param fallback font, to use when glyphs are missing, or null
     * @param imageFiles corresponding to the pages
     */
    public BitmapFont createFont(FileHandle fnt, float pixelsPerPoint, BitmapFont fallback, FileHandle...imageFiles) {
        final BitmapFont font = new BitmapFont(fnt.nameWithoutExtension(), fallback);
        // Load glyphs
        final String[] pagePaths = font.loadGlyphs(fnt, pixelsPerPoint);

        // Load textures
        if (pagePaths.length > imageFiles.length) {
            throw new GdxRuntimeException(pagePaths.length+" pages required, but only "+imageFiles.length+" provided for "+fnt);
        }
        final TextureRegion[] regions = new TextureRegion[pagePaths.length];
        for (int i = 0; i < pagePaths.length; i++) {
            final Texture texture = new Texture(imageFiles[i], Pixmap.Format.RGBA8888, false);
            regions[i] = new TextureRegion(texture);
        }
        font.loadPages(regions, true);

        return font;
    }

    /**
     * Load the font with pages found at given atlas.
     * Pages can appear in the atlas either verbatim or without extension (if .fnt specifies them with extension),
     * and with unique names or indexed.
     * @param fnt .fnt file describing the font
     * @param pixelsPerPoint see {@link BitmapFont#loadGlyphs(FileHandle, float)}
     * @param fallback font, to use when glyphs are missing, or null
     * @param atlas in which texture pages should be searched in
     */
    public BitmapFont createFont(FileHandle fnt, float pixelsPerPoint, BitmapFont fallback, TextureAtlas atlas) {
        final BitmapFont font = new BitmapFont(fnt.nameWithoutExtension(), fallback);
        // Load glyphs
        final String[] pagePaths = font.loadGlyphs(fnt, pixelsPerPoint);

        // Load textures
        final TextureRegion[] regions = new TextureRegion[pagePaths.length];
        for (int i = 0; i < pagePaths.length; i++) {
            String path = pagePaths[i];

            TextureAtlas.AtlasRegion region;
            findRegion: {
                region = atlas.findRegion(path, i);
                if (region != null) break findRegion;

                region = atlas.findRegion(path);
                if (region != null) break findRegion;

                final int extensionStart = path.lastIndexOf('.');
                if (extensionStart != -1) {
                    path = path.substring(0, extensionStart);

                    region = atlas.findRegion(path, i);
                    if (region != null) break findRegion;

                    region = atlas.findRegion(path);
                    if (region != null) break findRegion;
                }

                // Region not found
                throw new GdxRuntimeException("Can't find region named \""+pagePaths[i]+"\" (index: "+i+")");
            }

            regions[i] = region;
        }
        font.loadPages(regions, false);

        return font;
    }

    @Override
    public BitmapGlyphLayout createGlyphLayout() {
        return new BitmapGlyphLayout();
    }
}
