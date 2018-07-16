package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.utils.*;

import java.text.Bidi;
import java.util.Iterator;

/**
 * Allows to effectively iterate through homogenous regions of given text.
 *
 * Text homogeneity is disrupted by:
 * <ul>
 *     <li>Font change</li>
 *     <li>Color change</li>
 *     <li>Direction change</li>
 *     <li>Presence of <code>\n</code> or <code>\t</code></li>
 * </ul>
 */
public final class LayoutTextRunIterable<F extends Font> implements Iterable<LayoutTextRunIterable.TextRun<F>>, Pool.Poolable {

    private static ByteArray bidiEvalLevelCache = null;

    private final Array<TextRun<F>> textRuns = new Array<>(true, 10, TextRun.class);

    /**Obtain iterable of runs in given text.
     * Must be freed after use by {@link LayoutTextRunIterable#free(LayoutTextRunIterable)}.
     *
     * @param text to iterate through */
    public static <F extends Font> LayoutTextRunIterable<F> obtain(LayoutText<F> text) {
        @SuppressWarnings("unchecked")
        final LayoutTextRunIterable<F> iterable = ITERABLE_POOL.obtain();
        iterable.setup(text);
        return iterable;
    }

    /** Free the given iterable. Do not use it after freeing. */
    public static <F extends Font> void free(LayoutTextRunIterable<F> iterable) {
        ITERABLE_POOL.free(iterable);
    }

    /**
     * Add {@link TextRun}s, which are in text in area from start to end, to {@link #textRuns}.
     * @param ltr if the runs should be marked ltr or rtl
     */
    private void addRuns(final LayoutText<F> text, final int start, final int end, final boolean ltr) {
        assert start < end;
        final Array<TextRun<F>> textRuns = this.textRuns;

        // Init
        int index = start;
        int region = text.regionAt(start);
        int regionEndIndex = regionEndIndex(text, region);

        F font;
        float color;
        if (region < 0) {
            font = text.initialFont;
            color = text.initialColor;
        } else {
            font = text.regionFonts.get(region);
            color = text.regionColors.items[region];
        }

        while (true) {
            @SuppressWarnings("unchecked")
            final TextRun<F> run = RUN_POOL.obtain();
            run.start = index;
            run.color = color;
            run.font = font;
            if (ltr) {
                run.flags |= TextRun.FLAG_LTR;
            }

            // Compute new end
            F nextFont = null;
            float nextColor = 0f;
            if (regionEndIndex < end) {
                // If following regions share same font and color, no need to stop here
                // and even if they do, advance into them
                do {
                    region++;
                    assert region < text.regionStarts.size; // Because regionEndIndex < end
                    nextFont = text.regionFonts.get(region);
                    nextColor = text.regionColors.items[region];
                    regionEndIndex = regionEndIndex(text, region);

                    if (font != nextFont || color != nextColor) {
                        break;
                    }
                } while (regionEndIndex < end);
            }

            // Compute new final end index and check whether this run is \n or \t run.
            final int endIndex = Math.min(regionEndIndex, end);
            assert endIndex > index;
            switch (text.text[endIndex - 1]) {
                case '\n':
                    run.flags |= TextRun.FLAG_LINE_BREAK;
                    break;
                case '\t':
                    run.flags |= TextRun.FLAG_TAB_STOP;
                    break;
            }

            run.end = index = endIndex;
            textRuns.add(run);

            if (index < end) {
                assert nextFont != null;
                font = nextFont;
                color = nextColor;
            } else {
                break;
            }
        }
        assert index == end;
    }

    private void setup(LayoutText<F> text) {
        if (text.length <= 0) {
            // No point in continuing
            return;
        }
        final char[] chars = text.text;
        final int length = text.length;

        // Separate text into layout runs, whose boundaries are defined by their bidi levels, tab stops (\n) and by linebreaks (\n).
        // Runs on line are then reordered by their bidi order. At least one {@link TextRun} will be always added.

        final boolean allLtr;
        Bidi usedBidi = null;

        //TODO: For GWT, Bidi will have to be emulated
        if (Bidi.requiresBidi(chars, 0, length)) {
            // Do bidi analysis of the block. It is still possible, that whole text has homogenous direction.

            /*
            Note: Support for various unicode features, notably isolate marks, depends on Unicode version.
            For example, isolate marks were added in unicode 6.3, Java 8 supports only Unicode 6.2, so they will
            work correctly only on Java 9 and up.
             */
            final Bidi bidi = new Bidi(chars, 0,
                    null, 0,
                    length,
                    text.leftToRight ? Bidi.DIRECTION_LEFT_TO_RIGHT : Bidi.DIRECTION_RIGHT_TO_LEFT);

            if (bidi.isLeftToRight()) {
                allLtr = true;
            } else if (bidi.isRightToLeft()) {
                allLtr = false;
            } else if (bidi.getRunCount() < 1) {
                // Should not happen
                allLtr = text.leftToRight;
            } else if (bidi.getRunCount() == 1) {
                allLtr = (bidi.getLevelAt(0) & 1) == 0;
            } else {
                // Not homogenous.
                usedBidi = bidi;
                allLtr = true;// dummy
            }
        } else {
            allLtr = true;
        }

        // Create runs
        final Array<TextRun<F>> textRuns = this.textRuns;
        if (usedBidi == null) {
            // Simple variant
            int index = 0;
            do {
                final int endIndex = runBreakIndex(chars, index, length);
                final int reorderStart = textRuns.size;
                addRuns(text, index, endIndex, allLtr);
                if (!allLtr) {
                    // Reverse items on part TODO THIS MAY MESS UP TAB STOPS in RTL
                    reverse(textRuns.items, reorderStart, textRuns.size);
                }
                index = endIndex;
            } while (index < length);
        } else {
            // Full bidi variant
            ByteArray levels = LayoutTextRunIterable.bidiEvalLevelCache;
            final int runCount = usedBidi.getRunCount();
            if (levels == null) {
                levels = LayoutTextRunIterable.bidiEvalLevelCache = new ByteArray(true, runCount);
            } else {
                levels.clear();
            }
            levels.ensureCapacity(runCount);

            int index = 0;
            int runIndex = 0;
            int lineEnd = runBreakIndex(chars, index, length);
            int runEnd = usedBidi.getRunLimit(runIndex);
            int reorderLineFrom = 0;

            while (true) {
                final int runLevel = usedBidi.getRunLevel(runIndex);
                final int runCountBefore = textRuns.size;
                final int endIndex = Math.min(lineEnd, runEnd);
                addRuns(text, index, endIndex, (runLevel & 1) == 0);
                for (int i = runCountBefore; i < textRuns.size; i++) {
                    levels.add((byte) runLevel);
                }
                index = endIndex;

                if (endIndex == lineEnd) {
                    // Reorder line TODO: Should this be done after fit-wrapping? Probably not, as it seems too hard, but spec seems to be unclear.
                    //TODO THIS MAY MESS UP TAB STOPS in RTL
                    Bidi.reorderVisually(levels.items, 0, textRuns.items, reorderLineFrom, levels.size);
                    levels.clear();

                    reorderLineFrom = textRuns.size;
                    lineEnd = runBreakIndex(chars, lineEnd, length);
                }

                if (endIndex == runEnd) {
                    runIndex++;
                    if (runIndex >= runCount) {
                        break;
                    }
                    runEnd = usedBidi.getRunLimit(runIndex);
                }
            }

            assert reorderLineFrom == textRuns.size; // All should be reordered correctly
        }

        if (textRuns.size > 0) {
            textRuns.items[textRuns.size - 1].flags |= TextRun.FLAG_LAST_RUN;
        }

        assert assertLayoutRunsValid(textRuns);
    }

    private static <F extends Font> int regionEndIndex(LayoutText<F> text, int currentRegion) {
        final IntArray regionStarts = text.regionStarts;
        if (currentRegion + 1 >= regionStarts.size) {
            return text.length;
        } else {
            return regionStarts.items[currentRegion+1];
        }
    }

    private static <F extends Font> boolean assertLayoutRunsValid(Array<TextRun<F>> runs) {
        for (TextRun run : runs) {
            assert run.start >= 0;
            assert run.start < run.end;
        }
        return true;
    }

    private static int runBreakIndex(char[] text, int from, int end) {
        for (int i = from; i < end; i++) {
            switch (text[i]) {
                case '\n':
                case '\t':
                    return i + 1;
            }
        }
        return end;
    }

    private static <T> void reverse(T[] items, int start, int end) {
        for (int first = start, last = end - 1; first < last; first++, last--) {
            T temp = items[first];
            items[first] = items[last];
            items[last] = temp;
        }
    }

    /**
     * Do not remove or modify iterated elements.
     */
    @Override
    public Iterator<TextRun<F>> iterator() {
        return textRuns.iterator();
    }

    /**
     * Do not call directly.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void reset() {
        RUN_POOL.freeAll((Array<TextRun>)(Array)textRuns);
        textRuns.clear();
    }

    /**
     * Continuous range of characters in original text with the same properties.
     */
    public static final class TextRun<F extends Font> implements Pool.Poolable {
        /** This run's glyphs should be laid out left to right, opposed to right to left. */
        public static final int FLAG_LTR = 1;
        /** Next run will be on a new line, relative to this run. (Current run ends with \n) */
        public static final int FLAG_LINE_BREAK = 1<<1;
        /** Next run should be aligned according to a tab stop. (Current run ends with \t) */
        public static final int FLAG_TAB_STOP = 1<<2;
        /** Set on the last run of the iterable. Users may want to do end of line cleanup based on this. */
        public static final int FLAG_LAST_RUN = 1<<3;

        /** Positions into the original text. Never 0-length. */
        public int start, end;
        /** Font used in this run */
        public F font;
        /** Color used in this run */
        public float color;
        /** If false, text should be laid out left-to-right. */
        public int flags;

        @Override
        public void reset() {
            start = end = -1;
            font = null;
            color = 0f;
            flags = 0;
        }
    }

    private static final Pool<TextRun> RUN_POOL = Pools.get(TextRun.class, 20);
    /* Not exposed directly because of problems with generics */
    private static final Pool<LayoutTextRunIterable> ITERABLE_POOL = Pools.get(LayoutTextRunIterable.class, 10);
}
