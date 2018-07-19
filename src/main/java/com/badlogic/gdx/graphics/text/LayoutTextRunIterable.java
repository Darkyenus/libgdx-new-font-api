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
     * Add special tab stop or linebreak run.
     * This run contains only the tab stop or linebreak, no other characters.
     * Length of the break run is variable, typically 1, but may be more (e.g. CRLF).
     * @param start index of first character of run
     * @param maxEnd run may not end further than this
     * @param level of the run
     * @return length of the run, typically 1 or 2
     */
    private int addBreakRun(final LayoutText<F> text, final int start, final int maxEnd, final byte level) {
        assert start < maxEnd;

        @SuppressWarnings("unchecked")
        final TextRun<F> run = RUN_POOL.obtain();
        run.start = start;
        run.end = start + 1;
        run.level = level;

        // Find some font and color to use, but don't update it. Only one or max 2 characters are expected
        // and neither will probably draw.
        int region = text.regionAt(start);
        if (region < 0) {
            run.font = text.initialFont;
            run.color = text.initialColor;
        } else {
            run.font = text.regionFonts.get(region);
            run.color = text.regionColors.items[region];
        }

        final char[] chars = text.text;
        switch (chars[start]) {
            case '\t':
                run.flags |= TextRun.FLAG_TAB_STOP;
                break;
            case '\r':
                if (start + 1 < maxEnd && chars[start + 1] == '\n') {
                    run.end++;
                }
                // Fallthrough
            case '\n':
                run.flags |= TextRun.FLAG_LINE_BREAK;
                break;
            default:
                assert false;
        }

        textRuns.add(run);

        return run.end - run.start;
    }

    /**
     * Add {@link TextRun}s, which are in text in area from start to end, to {@link #textRuns}.
     * @param level Bidi level of the area
     */
    private void addRuns(final LayoutText<F> text, final int start, final int end, final byte level) {
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
            run.level = level;

            // Compute new end
            F nextFont = null;
            float nextColor = 0f;
            int nextRegionEndIndex = -1;
            if (regionEndIndex < end) {
                // If following regions share same font and color, no need to stop here
                // and even if they do, advance into them
                do {
                    region++;
                    assert region < text.regionStarts.size; // Because regionEndIndex < end
                    nextFont = text.regionFonts.get(region);
                    nextColor = text.regionColors.items[region];
                    nextRegionEndIndex = regionEndIndex(text, region);

                    if (font != nextFont || color != nextColor) {
                        break;
                    }
                    regionEndIndex = nextRegionEndIndex;
                } while (regionEndIndex < end);
            }

            // Compute new final end index
            final int endIndex = Math.min(regionEndIndex, end);
            assert endIndex > index;
            run.end = index = endIndex;
            textRuns.add(run);

            if (index < end) {
                assert nextFont != null;
                font = nextFont;
                color = nextColor;
                regionEndIndex = nextRegionEndIndex;
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
            final byte level = (byte) (allLtr ? 0 : 1);
            int index = 0;
            while (true) {
                final int endIndex = findBreakIndex(chars, index, length);
                if (index != endIndex) {
                    assert index < endIndex;
                    addRuns(text, index, endIndex, level);
                    index = endIndex;
                }

                if (endIndex < length) {
                    index += addBreakRun(text, endIndex, length, level);
                } else {
                    break;
                }
            }
        } else {
            // Full bidi variant
            final int runCount = usedBidi.getRunCount();

            int index = 0;
            int runIndex = 0;
            int breakEnd = findBreakIndex(chars, index, length);
            int runEnd = usedBidi.getRunLimit(runIndex);

            byte runLevel = (byte) usedBidi.getRunLevel(runIndex);
            while (true) {
                final int endIndex = Math.min(breakEnd, runEnd);
                if (index != endIndex) {
                    addRuns(text, index, endIndex, runLevel);
                    index = endIndex;
                }

                if (endIndex == runEnd) {
                    // If breakEnd is also here, it will get invoked on next iteration
                    runIndex++;
                    if (runIndex >= runCount) {
                        break;
                    }
                    runEnd = usedBidi.getRunLimit(runIndex);
                    runLevel = (byte) usedBidi.getRunLevel(runIndex);
                } else {
                    index += addBreakRun(text, index, runEnd, runLevel);
                    breakEnd = findBreakIndex(chars, index, length);
                }
            }
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

    private static int findBreakIndex(char[] text, int from, int end) {
        for (int i = from; i < end; i++) {
            switch (text[i]) {
                case '\n':
                case '\r':
                case '\t':
                    return i;
            }
        }
        return end;
    }

    /**
     * All characters in input text are represented in exactly one {@link TextRun},
     * in the order in which they appear in the text.
     *
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
        /** This run consists solely of a linebreak sequence. Next run will be on a new line, relative to this run. (Current run is \n, \r, or \r\n) */
        public static final int FLAG_LINE_BREAK = 1;
        /** This run consists solely of a tab stop. (Current run is \t) */
        public static final int FLAG_TAB_STOP = 1<<1;
        /** Set on the last run of the iterable. Users may want to do end of line cleanup based on this. */
        public static final int FLAG_LAST_RUN = 1<<2;

        /** Positions into the original text. Never 0-length. */
        public int start, end;
        /** Font used in this run */
        public F font;
        /** Color used in this run */
        public float color;
        /** If false, text should be laid out left-to-right. */
        public byte flags;
        /** Bidi level of the run. */
        public byte level;

        public boolean isLtr() {
            return (level & 1) == 0;
        }

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
