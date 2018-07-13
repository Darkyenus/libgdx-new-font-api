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

    private GlyphRun() {
    }

    /** X,Y coordinate of the run, relative to top-left point of whole {@link GlyphLayout}. */
    public float x, y;
    /** Line on which this run is. First line is 0. */
    public int line;
    /** Width of the run. */
    public float width;
    /** Run color. */
    public float color;

    /** Font used by the run. */
    public F font;

    /** Glyph indices. Must not contain nulls. */
    public final Array<Glyph> glyphs = new Array<>(true, 64, Glyph.class);
    /** Where the glyph pen point is, relative to run origin, which is on the top-left-most point of the line.
     * Pen point is usually on the left bottom side of the glyph and typically lies on the baseline. */
    public final FloatArray glyphX = new FloatArray(), glyphY = new FloatArray();

    public void ensureCapacity(int capacity) {
        glyphs.ensureCapacity(capacity);
        glyphX.ensureCapacity(capacity);
        glyphY.ensureCapacity(capacity);
    }

    @Override
    public void reset() {
        line = 0;
        width = 0;

        glyphs.clear();
        glyphX.clear();
        glyphY.clear();
    }
}
