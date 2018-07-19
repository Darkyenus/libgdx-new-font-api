package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.Pool;

/**
 * Contains laid out, colored text, along with some extra information to help {@link GlyphLayout}.
 *
 * All instances are obtained from and freed to {@link #pool()}.
 *
 * @param <F> Font that is used in the run - only for type safety of the API, can be freely changed on single instance when pooling
 */
public final class GlyphRun<F extends Font> implements Pool.Poolable {

    @SuppressWarnings("unchecked")
    private static final Pool<GlyphRun> POOL = new Pool<GlyphRun>() {
        @Override
        protected GlyphRun newObject() {
            return new GlyphRun();
        }
    };

    /**
     * Pool from which {@link GlyphRun} instances can be obtained and should be freed to.
     *
     * Not exposed as field because of problems with generics, but always returns the same instance.
     */
    @SuppressWarnings("unchecked")
    public static <F extends Font> Pool<GlyphRun<F>> pool() {
        return (Pool<GlyphRun<F>>)(Pool)POOL;
    }

    private static final int DEFAULT_SIZE = 32;

    /** Set if this run is a linebreak. Used when positioning caret. */
    public static final byte FLAG_LINEBREAK = 1;
    /** Set if this run is a tab stop specified by {@link LayoutText#TAB_STOP_LEFT}. Has special behavior when wrapping. */
    public static final byte FLAG_TAB_LEFT = 1<<1;
    /** Set if this run is a tab stop specified by {@link LayoutText#TAB_STOP_CENTER}. Has special behavior when wrapping. */
    public static final byte FLAG_TAB_CENTER = 1<<2;
    /** Set if this run is a tab stop specified by {@link LayoutText#TAB_STOP_RIGHT}. Has special behavior when wrapping. */
    public static final byte FLAG_TAB_RIGHT = 1<<3;
    public static final byte FLAG_MASK_TAB = FLAG_TAB_LEFT | FLAG_TAB_CENTER | FLAG_TAB_RIGHT;

    private GlyphRun() {
    }

    /** X,Y coordinate of the run, relative to top-left point of whole {@link GlyphLayout}. */
    public float x, y;
    /** Line on which this run is. First line is 0. */
    public int line;
    /** Width of the run, according to the last pen position. */
    public float width;
    /** Run color. */
    public float color;

    /** Font used by the run. */
    public F font;

    /** Glyph indices. Must not contain nulls. */
    public final Array<Glyph> glyphs = new Array<>(true, DEFAULT_SIZE, Glyph.class);
    /** Where the glyph pen point is, relative to run origin, which is on the top-left-most point of the line.
     * Pen point is usually on the left bottom side of the glyph and typically lies on the baseline. */
    public final FloatArray glyphX = new FloatArray(true, DEFAULT_SIZE), glyphY = new FloatArray(true, DEFAULT_SIZE);

    /** Range of the original text, which is drawn in this run. [start, end) */
    public int charactersStart, charactersEnd;
    /** For each character in [{@link #charactersStart}, {@link #charactersEnd})
     * contains X coordinate of the <i>beginning</i> of the character. That is the left edge of glyph for left-to-right
     * and right edge of glyph for right-to-left.
     * <br>
     * When <code>index+1</code> contains {@link Float#NaN}, it means that the value at <code>index</code> should
     * be used and that these characters are together in a grapheme cluster.
     * (This may be a genuine grapheme cluster, like <code>A + ' = Á</code>, or just UTF-16 surrogate pair.)
     * There may be multiple consecutive {@link Float#NaN}s.
     * <br>
     * Clusters should be treated as one character when moving caret, deleting or selecting.
     * <br>
     * These values generally hold no relation to {@link #glyphs}. To determine the <i>end</i> of the character/cluster,
     * <i>beginning</i> coordinate of next cluster should be used. If there is no such cluster, use position of the next
     * run (which may be on a new line - when drawing such selection, feel free to use {@link #width}).
     * If there is no next run, use {@link #width}, unless the last character of this run is <code>\n</code>
     * (which is signified by {@link GlyphRun#FLAG_LINEBREAK}), in which case beginning of next line should be used.*/
    public final FloatArray characterPositions = new FloatArray(true, DEFAULT_SIZE);
    /** Content related flags.
     * @see GlyphRun#FLAG_LINEBREAK
     * @see GlyphRun#FLAG_TAB_LEFT and friends */
    public byte charactersFlags;
    /** Bidi level of this run. */
    public byte charactersLevel;

    /**
     * @return width of the run, extended by draw bounds of last glyph, if those protrude {@link #width}
     */
    public float getDrawWidth() {
        final int lastIndex = glyphs.size - 1;
        if (lastIndex >= 0) {
            final Glyph lastGlyph = glyphs.items[lastIndex];
            return Math.max(this.width, glyphX.items[lastIndex] + lastGlyph.xOffset + lastGlyph.width);
        }
        return this.width;
    }

    public boolean isLtr() {
        return (charactersLevel & 1) == 0;
    }

    public void ensureGlyphCapacity(int capacity) {
        glyphs.ensureCapacity(capacity);
        glyphX.ensureCapacity(capacity);
        glyphY.ensureCapacity(capacity);
    }

    @Override
    public void reset() {
        line = 0;
        width = 0;
        charactersStart = charactersEnd = -1;
        charactersFlags = 0;

        glyphs.clear();
        glyphX.clear();
        glyphY.clear();
        characterPositions.clear();
    }
}
