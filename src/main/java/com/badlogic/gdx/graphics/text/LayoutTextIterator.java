package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.utils.*;

import java.text.Bidi;

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
 *
 * State is accessible through <code>currentX</code> fields.
 * Do not modify them. They hold valid values, only after {@link #start(LayoutText)} which returned <code>true</code>
 * or {@link #next()} which returned non-zero.
 */
public final class LayoutTextIterator<Font extends com.badlogic.gdx.graphics.text.Font> {

    /** This region is valid. */
    public static final int FLAG_VALID = 1;
    /** Next region will be on a new line, relative to current region. (Current region ends with \n) */
    public static final int FLAG_LINE_BREAK = 1<<1;
    /** Next region should be aligned according to a tab stop. (Current region ends with \t) */
    public static final int FLAG_TAB_STOP = 1<<2;
    /** Current region has a different color from the previous one. (Not set for first.) */
    public static final int FLAG_COLOR_CHANGE = 1<<3;
    /** Current region has a different font from the previous one. (Not set for first.) */
    public static final int FLAG_FONT_CHANGE = 1<<4;
    /** Current region belongs to a different run from previous one. (Set for first.) */
    public static final int FLAG_NEW_RUN = 1<<5;
    /** This region is the last region. */
    public static final int FLAG_LAST_REGION = 1<<6;

    private LayoutText<Font> text;
    private final Array<TextRun> directionRuns = new Array<>(true, 10, TextRun.class);

    private int currentRun;
    private int currentRegion;

    /** Index at which the current region starts. */
    public int currentStartIndex;
    /** Index at which the current region ends. */
    public int currentEndIndex;
    /** Font at which the iterator currently is. */
    public Font currentFont;
    /** Color at which the iterator currently is. */
    public float currentColor;
    /** Whether the current region is from left-to-right (true) or right-to-left (false).
     * Iteration is always left-to-right, it is advised to reverse characters when the region ends.
     * Characters with {@link Character#isMirrored} property should be rendered in mirrored.*/
    public boolean currentLtr;

    private int nextRunChangeIndex;
    private int nextRegionStartIndex;

    /**
     * @param text to iterate through
     */
    public void start(LayoutText<Font> text) {
        end();
        if (text.length <= 0) {
            // No point in continuing
            return;
        }
        this.text = text;

        evaluateBidiAndBreaksForRegion(text, directionRuns);

        // Initialize start values to snap to first region on first next()
        this.currentRun = -1;
        this.currentStartIndex = -1;
        this.currentEndIndex = -1;

        this.nextRunChangeIndex = -1;
        this.nextRegionStartIndex = -1;
    }

    private int regionEndIndex(int currentRegion) {
        final IntArray regionStarts = text.regionStarts;
        if (currentRegion + 1 >= regionStarts.size) {
            return text.length;
        } else {
            return regionStarts.items[currentRegion+1];
        }
    }

    /**
     * Move to next region.
     * <code>currentX</code> fields should be accessed only if this returns non-zero.
     * Regions are never empty.
     * @return 0 if iteration ended, bit-flag field with changed properties otherwise
     * @see #FLAG_LINE_BREAK
     * @see #FLAG_TAB_STOP
     * @see #FLAG_COLOR_CHANGE
     * @see #FLAG_FONT_CHANGE
     * @see #FLAG_NEW_RUN
     */
    public int next() {
        final LayoutText<Font> text = this.text;
        if (text == null) {
            return 0;
        }

        int changeFlags = FLAG_VALID;

        assert currentEndIndex <= nextRunChangeIndex;
        if (nextRunChangeIndex == currentEndIndex) {
            // Init or last region was last of the run - advance to next run
            final int runIndex = ++this.currentRun;

            final Array<TextRun> layoutRuns = this.directionRuns;
            if (runIndex >= layoutRuns.size) {
                // Already at the end
                end();
                return 0;
            }

            final TextRun run = layoutRuns.get(runIndex);

            currentStartIndex = run.start;
            final int region = this.currentRegion = text.regionAt(run.start);
            Font newFont;
            float newColor;
            if (region < 0) {
                newFont = text.initialFont;
                newColor = text.initialColor;
            } else {
                newFont = text.regionFonts.get(region);
                newColor = text.regionColors.items[region];
            }

            if (runIndex != 0) {
                if (newFont != currentFont) {
                    changeFlags |= FLAG_FONT_CHANGE;
                }
                if (newColor != currentColor) {
                    changeFlags |= FLAG_COLOR_CHANGE;
                }
            }
            currentFont = newFont;
            currentColor = newColor;
            currentLtr = run.ltr;

            nextRunChangeIndex = run.end;
            nextRegionStartIndex = regionEndIndex(currentRegion);

            changeFlags |= FLAG_NEW_RUN;
        } else {
            // Only advance within run
            currentStartIndex = currentEndIndex;

            assert nextRegionStartIndex == currentEndIndex;
            final int newRegion = ++currentRegion;
            final Font newFont = text.regionFonts.get(newRegion);
            final float newColor = text.regionColors.items[newRegion];
            if (currentFont != newFont) {
                changeFlags |= FLAG_FONT_CHANGE;
                currentFont = newFont;
            }
            if (currentColor != newColor) {
                changeFlags |= FLAG_COLOR_CHANGE;
                currentColor = newColor;
            }
            nextRegionStartIndex = regionEndIndex(newRegion);
        }

        // Compute new end
        final int hardEnd = nextRunChangeIndex;

        // If following regions share same font and color, no need to stop here
        while (nextRegionStartIndex < hardEnd) {
            int nextRegion = currentRegion + 1;
            final Font newFont = text.regionFonts.get(nextRegion);
            final float newColor = text.regionColors.items[nextRegion];

            if (currentFont != newFont || currentColor != newColor) {
                break;
            }

            currentRegion = nextRegion;
            nextRegionStartIndex = regionEndIndex(nextRegion);
        }

        // Compute new final end index and check whether this run is \n or \t run.
        currentEndIndex = Math.min(nextRegionStartIndex, hardEnd);
        assert currentEndIndex > currentStartIndex;
        switch (text.text[currentEndIndex - 1]) {
            case '\n':
                changeFlags |= FLAG_LINE_BREAK;
                break;
            case '\t':
                changeFlags |= FLAG_TAB_STOP;
                break;
        }

        if (currentEndIndex == nextRunChangeIndex && currentRun + 1 >= directionRuns.size) {
            changeFlags |= FLAG_LAST_REGION;
        }

        return changeFlags;
    }


    /**
     * End the iteration. Used only to prevent memory leaks, no need to call otherwise.
     */
    public void end() {
        this.text = null;
        RUN_POOL.freeAll(directionRuns);
        directionRuns.clear();
        this.currentFont = null;

        this.currentRun = 0;
        this.currentStartIndex = 0;
        this.currentEndIndex = 0;

        this.nextRunChangeIndex = 0;
        this.nextRegionStartIndex = 0;
    }

    private static ByteArray bidiEvalLevelCache = null;

    /**
     * Separate text into layout runs, whose boundaries are defined by their bidi levels, tab stops (\n) and by linebreaks (\n).
     * Runs on line are then reordered by their bidi order. At least one {@link TextRun} will be always added.
     * @param out array to which runs are added to. Runs should be freed to {@link #RUN_POOL}
     */
    private static void evaluateBidiAndBreaksForRegion(LayoutText text, Array<TextRun> out) {
        final boolean allLtr;
        Bidi usedBidi = null;

        final char[] chars = text.text;
        final int length = text.length;

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
        if (usedBidi == null) {
            int index = 0;
            do {
                final TextRun run = RUN_POOL.obtain();
                run.start = index;
                run.end = index = runBreakIndex(chars, index, length);
                run.ltr = allLtr;
                out.add(run);
            } while (index < length);

            assert assertLayoutRunsValid(out);
            return;
        }

        ByteArray levels = LayoutTextIterator.bidiEvalLevelCache;
        final int runCount = usedBidi.getRunCount();
        if (levels == null) {
            levels = LayoutTextIterator.bidiEvalLevelCache = new ByteArray(true, runCount);
        }
        levels.ensureCapacity(runCount);
        try {
            int index = 0;
            int runIndex = 0;
            int lineEnd = runBreakIndex(chars, index, length);
            int runEnd = usedBidi.getRunLimit(runIndex);
            int firstLineRunIndex = out.size;

            while (true) {
                if (lineEnd <= runEnd) {
                    // Line break is earlier
                    TextRun run = RUN_POOL.obtain();
                    run.start = index;
                    run.end = lineEnd;
                    int runLevel = usedBidi.getRunLevel(runIndex);
                    run.ltr = levelToLtr(runLevel);
                    out.add(run);
                    levels.add((byte) runLevel);
                    index = lineEnd;

                    // Reorder runs on the line TODO: Should this be done after fit-wrapping? Probably not, as it seems too hard, but spec seems to be unclear.
                    if (levels.size > 1) {
                        Bidi.reorderVisually(levels.items, 0, out.items, firstLineRunIndex, levels.size);
                    }
                    levels.clear();
                    firstLineRunIndex = out.size;

                    if (lineEnd == runEnd) {
                        // Move runEnd as well
                        runIndex++;
                        if (runIndex >= runCount) {
                            break;
                        }
                        runEnd = usedBidi.getRunLimit(runIndex);
                    }

                    // Move line end to next thing
                    lineEnd = runBreakIndex(chars, lineEnd, length);
                } else {
                    // Run-end is earlier
                    TextRun run = RUN_POOL.obtain();
                    run.start = index;
                    run.end = runEnd;
                    int runLevel = usedBidi.getRunLevel(runIndex);
                    run.ltr = levelToLtr(runLevel);
                    out.add(run);
                    levels.add((byte) runLevel);
                    index = runEnd;

                    runIndex++;
                    if (runIndex >= runCount) {
                        break;
                    }
                    runEnd = usedBidi.getRunLimit(runIndex);
                }
            }

            if (firstLineRunIndex < out.size && levels.size > 1) {
                // We did not end on linebreak, so we have to reorder last line
                Bidi.reorderVisually(levels.items, 0, out.items, firstLineRunIndex, levels.size);
            }
        } finally {
            assert assertLayoutRunsValid(out);

            levels.clear();
        }
    }

    private static boolean assertLayoutRunsValid(Array<TextRun> runs) {
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

    private static boolean levelToLtr(int level) {
        return (level & 1) == 0;
    }

    private static final class TextRun {
        /** Positions into the original text. Never 0-length. */
        public int start, end;
        /** If false, text should be laid out left-to-right. */
        public boolean ltr;
    }

    private static final Pool<TextRun> RUN_POOL = Pools.get(TextRun.class, 20);
}
