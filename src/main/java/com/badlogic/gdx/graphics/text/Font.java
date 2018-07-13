package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;

/**
 * Provides glyphs for the {@link GlyphLayout} it uses.
 *
 * This font is immutable and may never change properties after construction (or initialization).
 */
public interface Font<GlyphLayout extends com.badlogic.gdx.graphics.text.GlyphLayout> extends Disposable {

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
     * Until then, regions may be empty or pages may be missing completely.
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

    /** @return x-advance used for missing whitespace glyphs and for tab-stop related computations */
    float getSpaceXAdvance();

    /** @return Y distance between two consecutive lines */
    float getLineHeight();
}
