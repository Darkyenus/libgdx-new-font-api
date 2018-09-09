package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.LongArray;
import com.badlogic.gdx.utils.Pool;

import java.util.Arrays;

/**
 * Contains laid out, colored text, along with some extra information to help {@link GlyphLayout}.
 *
 * All instances are obtained from and freed to {@link #pool()}.
 *
 * @param <F> Font that is used in the run - only for type safety of the API, can be freely changed on single instance when pooling
 */
public final class GlyphRun<F extends Font<F>> implements Pool.Poolable {

    @SuppressWarnings("unchecked")
    private static final Pool<GlyphRun> POOL = new Pool<GlyphRun>(64, 2048) {
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
    public static <F extends Font<F>> Pool<GlyphRun<F>> pool() {
        return (Pool<GlyphRun<F>>)(Pool)POOL;
    }

    /**
     * Shortcut to {@code <F>pool().obtain()}, optionally with call to enable {@link #checkpoints}.
     */
    public static <F extends Font<F>> GlyphRun<F> obtain(boolean withCheckpoints) {
        final GlyphRun<F> run = GlyphRun.<F>pool().obtain();
        if (withCheckpoints) {
            run.setCheckpointsEnabled(true);
        }
        return run;
    }

    private static final int DEFAULT_SIZE = 32;

    /** Set if this run is a linebreak. Used when positioning caret. */
    public static final byte FLAG_LINEBREAK = 1;
    /** Set if this run is a tab stop. */
    public static final byte FLAG_TAB = 1<<1;
    /** Set if this run is an ellipsis run and does not hold valid character position/range info.
     * (i.e. {@link #charactersStart}, {@link #charactersEnd} and {@link #characterPositions} is empty) */
    public static final byte FLAG_ELLIPSIS = 1<<2;

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
    public final FloatArray glyphX = new FloatArray(true, DEFAULT_SIZE);
    public final FloatArray glyphY = new FloatArray(true, DEFAULT_SIZE);

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
    /** Bidi level of this run. */
    public byte charactersLevel;
    /** Content related flags. GlyphLayout may put own flags here, allocate them from most significant bit.
     * @see GlyphRun#FLAG_LINEBREAK
     * @see GlyphRun#FLAG_TAB
     * @see GlyphRun#FLAG_ELLIPSIS */
    public byte characterFlags;

    /** FOR USE BY GlyphLayout (and subclasses) ONLY!!!
     * Stores length of {@link #characterPositions} (msb) packed with length of {@link #glyphs}.
     * @see #setCheckpointsEnabled(boolean) to set the backing array of this field
     * @see #createCheckpoint to populate */
    public LongArray checkpoints = null;

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
        return LayoutTextRunArray.TextRun.isLevelLtr(charactersLevel);
    }

    public boolean isEllipsis() {
        return (characterFlags & GlyphRun.FLAG_ELLIPSIS) != 0;
    }

    public void ensureGlyphCapacity(int capacity) {
        glyphs.ensureCapacity(capacity);
        glyphX.ensureCapacity(capacity);
        glyphY.ensureCapacity(capacity);
    }

    /** Controls whether or not {@link #checkpoints} can be used (is not null).
     * @param enabled true = ensure that it is empty and not null, false = ensure that it is null */
    public void setCheckpointsEnabled(boolean enabled) {
        if (enabled) {
            if (checkpoints == null) {
                checkpoints = CHECKPOINTS_POOL.obtain();
            } else {
                checkpoints.clear();
            }
        } else {
            if (checkpoints != null) {
                CHECKPOINTS_POOL.free(checkpoints);
                checkpoints = null;
            }
        }
    }

    /**
     * Add [32 bit characterPositions.size | 32 bit glyphs.size] packed value to {@link #checkpoints} array.
     * @throws NullPointerException when {@link #checkpoints} is null
     */
    public void createCheckpoint(int characterIndex, int glyphIndex) {
        long value = ((long)characterIndex << 32) | (glyphIndex & 0xFFFF_FFFFL);
        checkpoints.add(value);
    }

    public int getCheckpointIndexOfCharacter(int searchedCharacter) {
        final int size = checkpoints.size;
        if (size <= 0) {
            return -1;
        }

        final long key = (long)searchedCharacter << 32;
        final long[] items = checkpoints.items;

        int index = Arrays.binarySearch(items, 0, size, key);
        if (index < 0) {
            index = -index - 1;
        }

        if (checkpointGetCharacter(items[index]) == searchedCharacter) {
            return index;
        }
        return -1;
    }

    public static int checkpointGetCharacter(long checkpoint) {
        return (int) (checkpoint >>> 32);
    }

    public static int checkpointGetGlyph(long checkpoint) {
        return (int) (checkpoint & 0xFFFF_FFFFL);
    }

    @Override
    public void reset() {
        line = 0;
        width = 0;
        charactersStart = charactersEnd = -1;
        characterFlags = 0;

        glyphs.clear();
        glyphX.clear();
        glyphY.clear();
        characterPositions.clear();

        setCheckpointsEnabled(false);
    }

    private final Pool<LongArray> CHECKPOINTS_POOL = new Pool<LongArray>() {
        @Override
        protected LongArray newObject() {
            return new LongArray(true, DEFAULT_SIZE);
        }

        @Override
        protected void reset(LongArray object) {
            object.clear();
        }
    };
}
