package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Disposable;

/**
 * Provides glyphs for the {@link GlyphLayout} it uses.
 *
 * This font is immutable and may never change properties after construction (or initialization).
 */
public interface Font <SelfFont extends Font<SelfFont>> extends Disposable {

    int MISSING_GLYPH_ID = 0;

    /**
     * Get reference to texture pages of the Font. Array may be empty and trailing elements may be null.
     * Caller MUST NOT modify returned value.
     *
     * @return non-null array
     * @see #getGlyph(int) for further constraints
     * @see #prepareGlyphs() has to be called before this, so that all glyphs are in the returned textures
     */
    Texture[] getPages();

    /**
     * Retrieve glyph for the ID.
     *
     * There is no relation between glyphId and character (unless font establishes it itself).
     * Calling this method multiple times with the same glyphId MUST always return same Glyph.
     *
     * After calling this, possibly multiple times, call {@link #prepareGlyphs()} to ensure that returned glyphs
     * contain valid references to texture page regions from {@link #getPages()}.
     * Until then, regions may be empty or pages may be missing completely. (Not needed if all this returned was null.)
     *
     * glyphId 0 ({@link #MISSING_GLYPH_ID}) should always represent "missing glyph", if not null.
     * When requested glyph is missing, return null, it is a caller responsibility to find replacement glyph (possibly 0).
     *
     * @return glyph representing the glyphId, null if no glyph exists
     * @see #MISSING_GLYPH_ID
     */
    Glyph getGlyph(int glyphId);

    /**
     * Ensure that any {@link Glyph}s returned by {@link #getGlyph(int)} since its last invocation contain valid data
     * and reference valid texture areas.
     */
    void prepareGlyphs();

    /**
     * Get font that should be used when some character is not found in this font.
     * This may form an arbitrarily long chain, but must never cycle.
     * @return fallback font, or null if no such font exists
     */
    SelfFont getFallback();

    /**
     * Create glyph layout for this class of fonts.
     * This is a shortcut, and must be strictly equivalent, to calling {@link FontSystem#createGlyphLayout()} on the
     * font system which created this font.
     * @return glyph layout for this type of fonts, never null
     */
    GlyphLayout<SelfFont> createGlyphLayout();
}
