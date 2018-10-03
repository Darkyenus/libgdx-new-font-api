package com.badlogic.gdx.graphics.text.harfbuzz;

import com.badlogic.gdx.graphics.text.GlyphLayout;
import com.badlogic.gdx.graphics.text.GlyphRun;
import com.badlogic.gdx.graphics.text.LayoutText;
import com.badlogic.gdx.graphics.text.LayoutTextRunArray;
import com.badlogic.gdx.graphics.text.LayoutTextRunArray.TextRun;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.Locale;

import static com.badlogic.gdx.graphics.text.GlyphRun.FLAG_ELLIPSIS;
import static com.badlogic.gdx.graphics.text.harfbuzz.HarfBuzz.Buffer.HB_GLYPH_FLAG_UNSAFE_TO_BREAK;
import static com.badlogic.gdx.graphics.text.harfbuzz.HarfBuzz.Font.NO_FEATURES;
import static com.badlogic.gdx.graphics.text.harfbuzz.HarfBuzz.toFloatFrom26p6;

/**
 * Glyph layout for harf-buzz fonts.
 */
public class HBGlyphLayout extends GlyphLayout<HBFont> {

    private static final byte FLAG_GLYPH_RUN_HAS_COLLAPSED_SPACES = (byte) (1 << 6);
    private static final byte FLAG_GLYPH_RUN_IS_PARAGRAPH_START = (byte) (1 << 5);
    private static final byte FLAG_GLYPH_RUN_IS_PARAGRAPH_END = (byte) (1 << 4);

    private float startX;

    /** Borrowed by BitmapGlyphLayout instances when they are doing glyph layout, to store all fonts in the layout. */
    private static final Array<HBFont> usedFonts = new Array<>(true, 10, HBFont.class);

    /** Cached. */
    private static final HarfBuzz.Buffer shapeBuffer = HarfBuzz.Buffer.create();

    private void addLineHeight(float height) {
        lineHeights.add(getHeight() + height);
    }

    private void addLinebreakRunFor(final TextRun<HBFont> textRun, final int line) {
        final int runStart = textRun.start;
        final int runEnd = textRun.end;
        assert runStart < runEnd;

        final GlyphRun<HBFont> run = GlyphRun.obtain(false);

        // Linebreak is represented always as a single character position
        final float[] characterPositions = run.characterPositions.ensureCapacity(runEnd - runStart);
        run.characterPositions.size = runEnd - runStart;
        characterPositions[0] = 0f;
        for (int i = 1; i < runEnd - runStart; i++) {
            characterPositions[i] = Float.NaN;
        }

        // Linebreak never has glyphs, nor width
        run.x = startX; // Y set later
        run.width = 0f;
        run.line = line;
        run.font = textRun.font;
        run.color = textRun.color;
        run.charactersStart = runStart;
        run.charactersEnd = runEnd;
        run.characterFlags |= GlyphRun.FLAG_LINEBREAK;
        runs.add(run);
    }

    private void addTabStopRunFor(final LayoutText<HBFont> text, final TextRun<HBFont> textRun, final int line) {
        final int runStart = textRun.start;
        final int runEnd = textRun.end;
        assert runStart < runEnd;

        final GlyphRun<HBFont> run = GlyphRun.obtain(false);

        // Tab stop is represented always as a single character position
        final float[] characterPositions = run.characterPositions.ensureCapacity(runEnd - runStart);
        run.characterPositions.size = runEnd - runStart;
        characterPositions[0] = 0f;
        for (int i = 1; i < runEnd - runStart; i++) {
            // Unlikely to ever happen
            characterPositions[i] = Float.NaN;
        }

        // Tab never has glyphs, only width
        final float defaultTabAdvance = textRun.font.spaceXAdvance * 8f;
        final int tabIndex = text.tabStopIndexFor(startX, defaultTabAdvance);

        run.x = startX; // Y set later
        run.line = line;
        run.font = textRun.font;
        run.color = textRun.color;
        run.charactersStart = runStart;
        run.charactersEnd = runEnd;

        run.characterFlags |= GlyphRun.FLAG_TAB;
        if (tabIndex == -1) {
            // Ignore it
            run.width = 0f;
        } else {
            final float offset = text.tabStopOffsetFor(tabIndex, defaultTabAdvance);
            run.width = offset - run.x;
            startX += run.width;
        }
        runs.add(run);
    }

    private void addEllipsisRunFor(String chars, final byte level,
                                   final HBFont font, final float color, final int line, int insertIndex) {
        final HarfBuzz.Buffer shapeBuffer = HBGlyphLayout.shapeBuffer;
        shapeBuffer.reset();

        // Set flags and properties
        {
            shapeBuffer.setContentType(HarfBuzz.Buffer.ContentType.UNICODE);

            int runFlags = HarfBuzz.Buffer.HB_BUFFER_FLAG_DEFAULT;
            runFlags |= HarfBuzz.Buffer.HB_BUFFER_FLAG_BOT;
            runFlags |= HarfBuzz.Buffer.HB_BUFFER_FLAG_EOT;
            shapeBuffer.setFlags(runFlags);
        }


        // Add the text to the buffer
        shapeBuffer.add(chars, 0, chars.length(), 0, chars.length());

        shapeBuffer.guessSegmentProperties();
        shapeBuffer.setDirection(TextRun.isLevelLtr(level) ? HarfBuzz.Direction.LTR : HarfBuzz.Direction.RTL);

        // Shape with default features
        final float densityScale = font.densityScale;
        font.hbFont.shape(shapeBuffer, NO_FEATURES);// TODO(jp): Examine what features can/should we use

        // Create runs
        final int shapedGlyphCount = shapeBuffer.getLength();

        final IntArray glyphInfoArray = HBGlyphLayout.addRunsForTextRuns_glyphInfo;
        shapeBuffer.getGlyphInfos(glyphInfoArray);
        assert shapedGlyphCount * 3 == glyphInfoArray.size;
        final int[] glyphInfo = glyphInfoArray.items;

        final IntArray glyphPositionsArray = HBGlyphLayout.addRunsForTextRuns_glyphPositions;
        shapeBuffer.getGlyphPositions(glyphPositionsArray);
        assert shapedGlyphCount * 4 == glyphPositionsArray.size;
        final int[] glyphPositions = glyphPositionsArray.items;

        GlyphRun<HBFont> currentGlyphRun = GlyphRun.obtain(true);
        currentGlyphRun.x = startX;
        currentGlyphRun.line = line;
        currentGlyphRun.font = font;
        currentGlyphRun.color = color;
        currentGlyphRun.charactersLevel = level;
        currentGlyphRun.charactersStart = -1;
        currentGlyphRun.charactersEnd = -1;
        currentGlyphRun.characterFlags |= FLAG_ELLIPSIS;

        final float fontBase = font.base;

        float penX = 0f;
        for (int i = 0, gi = 0, gp = 0; i < shapedGlyphCount; i++, gi += 3, gp += 4) {
            final int glyphId = glyphInfo[gi];
            final int glyphFlags = glyphInfo[gi + 1];
            final int originalIndex = glyphInfo[gi + 2];

            final float xAdvance = toFloatFrom26p6(glyphPositions[gp]) * densityScale;
            final float xOffset = toFloatFrom26p6(glyphPositions[gp + 2]) * densityScale;
            final float yOffset = toFloatFrom26p6(glyphPositions[gp + 3]) * densityScale;

            if ((glyphFlags & HB_GLYPH_FLAG_UNSAFE_TO_BREAK) == 0) {
                currentGlyphRun.createCheckpoint(originalIndex, currentGlyphRun.glyphs.size);
            }
            currentGlyphRun.glyphs.add(font.getGlyph(glyphId));
            currentGlyphRun.glyphX.add(penX + xOffset);
            currentGlyphRun.glyphY.add(yOffset - fontBase);

            penX += xAdvance;
        }

        currentGlyphRun.width = penX;

        startX += penX;
        runs.insert(insertIndex, currentGlyphRun);
    }

    private static final IntArray addRunsForTextRuns_glyphInfo = new IntArray();
    private static final IntArray addRunsForTextRuns_glyphPositions = new IntArray();

    private int addRunsFor(final char[] chars, final int charsLength, final int runStart, final int runEnd, final byte level,
                           final HBFont font, final float color, final int line, int insertIndex,
                           boolean paragraphStart, boolean paragraphEnd) {
        final HarfBuzz.Buffer shapeBuffer = HBGlyphLayout.shapeBuffer;
        shapeBuffer.reset();

        // Set flags and properties
        {
            shapeBuffer.setContentType(HarfBuzz.Buffer.ContentType.UNICODE);
            shapeBuffer.setClusterLevel(HarfBuzz.Buffer.ClusterLevel.MONOTONE_CHARACTERS);

            int runFlags = HarfBuzz.Buffer.HB_BUFFER_FLAG_DEFAULT;
            if (paragraphStart) {
                runFlags |= HarfBuzz.Buffer.HB_BUFFER_FLAG_BOT;
            }
            if (paragraphEnd) {
                runFlags |= HarfBuzz.Buffer.HB_BUFFER_FLAG_EOT;
            }
            shapeBuffer.setFlags(runFlags);
        }


        // Add the text to the buffer
        shapeBuffer.add(chars,
                0, charsLength,
                runStart, runEnd - runStart);

        shapeBuffer.guessSegmentProperties();
        final boolean ltr = TextRun.isLevelLtr(level);
        shapeBuffer.setDirection(ltr ? HarfBuzz.Direction.LTR : HarfBuzz.Direction.RTL);

        // Shape with default features
        final float densityScale = font.densityScale;
        font.hbFont.shape(shapeBuffer, NO_FEATURES);// TODO(jp): Examine what features can/should we use

        // Create runs
        final int shapedGlyphCount = shapeBuffer.getLength();

        final IntArray glyphInfoArray = HBGlyphLayout.addRunsForTextRuns_glyphInfo;
        shapeBuffer.getGlyphInfos(glyphInfoArray);
        assert shapedGlyphCount * 3 == glyphInfoArray.size;
        final int[] glyphInfo = glyphInfoArray.items;

        final IntArray glyphPositionsArray = HBGlyphLayout.addRunsForTextRuns_glyphPositions;
        shapeBuffer.getGlyphPositions(glyphPositionsArray);
        assert shapedGlyphCount * 4 == glyphPositionsArray.size;
        final int[] glyphPositions = glyphPositionsArray.items;

        GlyphRun<HBFont> currentGlyphRun = GlyphRun.obtain(true);
        currentGlyphRun.x = startX;
        currentGlyphRun.line = line;
        currentGlyphRun.font = font;
        currentGlyphRun.color = color;
        if (paragraphStart) {
            currentGlyphRun.characterFlags |= FLAG_GLYPH_RUN_IS_PARAGRAPH_START;
        }
        if (paragraphEnd) {
            currentGlyphRun.characterFlags |= FLAG_GLYPH_RUN_IS_PARAGRAPH_END;
        }
        currentGlyphRun.charactersLevel = level;
        currentGlyphRun.charactersStart = runStart;
        currentGlyphRun.charactersEnd = runEnd;
        final int characterCount = runEnd - runStart;
        float[] characterPositions = currentGlyphRun.characterPositions.ensureCapacity(characterCount);
        currentGlyphRun.characterPositions.size = characterCount;

        final float fontBase = font.base;

        float penX = 0f;
        int c = ltr ? 0 : characterCount-1;
        for (int i = 0, gi = 0, gp = 0; i < shapedGlyphCount; i++, gi += 3, gp += 4) {
            final int glyphId = glyphInfo[gi];
            final int glyphFlags = glyphInfo[gi + 1];
            final int originalIndex = glyphInfo[gi + 2];

            final float xAdvance = toFloatFrom26p6(glyphPositions[gp]) * densityScale;
            final float xOffset = toFloatFrom26p6(glyphPositions[gp + 2]) * densityScale;
            final float yOffset = toFloatFrom26p6(glyphPositions[gp + 3]) * densityScale;

            if ((glyphFlags & HB_GLYPH_FLAG_UNSAFE_TO_BREAK) == 0) {
                currentGlyphRun.createCheckpoint(originalIndex, currentGlyphRun.glyphs.size);
            }
            currentGlyphRun.glyphs.add(font.getGlyph(glyphId));
            currentGlyphRun.glyphX.add(penX + xOffset);
            currentGlyphRun.glyphY.add(yOffset - fontBase);

            final int newC = originalIndex - runStart;
            if (ltr) {
                characterPositions[c++] = penX;
                while (c < newC) {
                    characterPositions[c++] = Float.NaN;
                }
            } else {
                while (c > newC) {
                    characterPositions[c--] = Float.NaN;
                }
                characterPositions[c--] = penX;
            }

            penX += xAdvance;
        }

        currentGlyphRun.width = penX;
        if (ltr) {
            while (c < currentGlyphRun.characterPositions.size) {
                characterPositions[c++] = Float.NaN;
            }
        } else {
            while (c >= 0) {
                characterPositions[c--] = Float.NaN;
            }
        }

        startX += penX;
        runs.insert(insertIndex, currentGlyphRun);
        return 1;
    }

    /** Reorders run according to BiDi algorithm, computes line height, sets Y of runs on the line,
     * and adjusts variables for next line. */
    private void completeLine(final LayoutText<HBFont> text, final int runsStart, final int runsEnd,
                              final HBFont defaultFont) {
        assert runsStart <= runsEnd : runsStart + " <= "+ runsEnd;
        final Array<GlyphRun<HBFont>> runs = this.runs;

        // Find out how the runs need to be reordered
        reordering:
        {
            if (runsStart + 1 >= runsEnd) {
                // Only one run, no need to reorder
                break reordering;
            }

            // Analyze if reordering is needed
            byte and = ~0;
            byte or = 0;
            for (int i = runsStart; i < runsEnd; i++) {
                final GlyphRun<HBFont> run = runs.items[i];
                if ((run.characterFlags & (GlyphRun.FLAG_LINEBREAK | GlyphRun.FLAG_TAB)) != 0) {
                    // Reset level of this to paragraph level
                    run.charactersLevel = (byte) (text.isLeftToRight() ? 0 : 1);
                }

                and &= run.charactersLevel;
                or |= run.charactersLevel;
            }

            if ((or & 1) == 0) {
                // All levels are LTR, no need to reorder
                break reordering;
            }
            if ((and & 1) == 1) {
                // All levels are RTL, simple all-reorder
                reverse(runs.items, runsStart, runsEnd);
            } else {
                // Full bidi reordering needed
                final byte[] levels = bidiLevelsFor(runs.items, runsStart, runsEnd);
                Bidi.reorderVisually(levels, 0, runs.items, runsStart, runsEnd - runsStart);
            }

            // Reorder runs on X
            float x = 0f;
            for (int i = runsStart; i < runsEnd; i++) {
                final GlyphRun<HBFont> run = runs.items[i];
                run.x = x;
                x += run.width;
            }
        }

        // Find out, which fonts are on this line
        final Array<HBFont> fonts = usedFonts;
        int usedFontsOnLineFrom = fonts.size;
        for (int i = runsStart; i < runsEnd; i++) {
            final GlyphRun<HBFont> run = runs.items[i];
            if (run.glyphs.size == 0) {
                continue;
            }

            final HBFont font = run.font;
            final int fontIndex = fonts.indexOf(font, true);
            if (fontIndex < 0) {
                fonts.add(font);
            } else if (fontIndex < usedFontsOnLineFrom) {
                usedFontsOnLineFrom--;
                fonts.swap(fontIndex, usedFontsOnLineFrom);
            }// else: already good
        }

        // Go through fonts on line and check their max extend from top to baseline and from baseline down
        float topToBaseline = 0f;
        float baselineToDown = 0f;
        if (usedFontsOnLineFrom == fonts.size) {
            // No fonts on line, use only current run font
            topToBaseline = defaultFont.base;
            baselineToDown = defaultFont.lineHeight - defaultFont.base;
        } else {
            for (int i = usedFontsOnLineFrom; i < fonts.size; i++) {
                final HBFont f = fonts.items[i];
                topToBaseline = Math.max(topToBaseline, f.base);
                baselineToDown = Math.max(baselineToDown, f.lineHeight - f.base);
            }
        }

        final int line = lineHeights.size;
        final float lineStartY = -getHeight();

        // Shift each run so that it shares common baseline with all fonts on line
        GlyphRun<HBFont> run;
        final float finalLineHeight = topToBaseline + baselineToDown;
        for (int i = runsStart; i < runsEnd; i++) {
            run = runs.items[i];
            assert run.line == line;

            final float fontBase = run.font.base;
            run.y = lineStartY - topToBaseline + fontBase;
        }

        addLineHeight(finalLineHeight);

        startX = 0f;
    }

    private int runIndexWithCharIndex(int fromRunIndex, int charIndex) {
        final Array<GlyphRun<HBFont>> runs = this.runs;
        assert fromRunIndex < runs.size && fromRunIndex >= 0;

        for (int i = fromRunIndex; i < runs.size; i++) {
            if (charIndex < runs.items[i].charactersEnd) {
                return i;
            }
        }

        return runs.size;
    }

    /** @return {@link GlyphRun#charactersEnd} value with which the run would have to be constructed with,
     * so that its {@link GlyphRun#width} would be <= <code>runWidth</code>. */
    private int charEndIndexForTargetRunWidth(GlyphRun<HBFont> run, float targetRunWidth) {
        final int charPositionCount = run.characterPositions.size;
        final float[] charPositions = run.characterPositions.items;

        int characterIndexInWrapRun = 0;
        if (run.isLtr()) {
            for (int i = 0; i < charPositionCount; i++) {
                final float position = charPositions[i];
                if (Float.isNaN(position)) {
                    continue;
                }
                if (position >= targetRunWidth) {
                    break;
                }
                characterIndexInWrapRun = i;
            }
        } else {
            final float runWidth = run.width;
            for (int i = 0; i < charPositionCount; i++) {
                float position = charPositions[i];
                if (Float.isNaN(position)) {
                    continue;
                }

                position = runWidth - position;

                if (position >= targetRunWidth) {
                    break;
                }
                characterIndexInWrapRun = i;
            }
        }
        return run.charactersStart + characterIndexInWrapRun;
    }

    private int splitRunForWrap(final char[] chars, final int charsLength, final int runIndex, int splitIndex) {
        final Array<GlyphRun<HBFont>> runs = this.runs;

        if (runIndex >= runs.size) {
            return runs.size;
        } else if (runIndex < 0) {
            return 0;
        }

        final GlyphRun<HBFont> splitRun = runs.items[runIndex];
        // No splitting necessary?
        if (splitIndex <= splitRun.charactersStart) {
            return runIndex;
        } else if (splitIndex >= splitRun.charactersEnd
                || (splitRun.characterFlags & (GlyphRun.FLAG_LINEBREAK | GlyphRun.FLAG_TAB)) != 0) {
            // No splitting necessary
            return runIndex + 1;
        }

        assert splitRun.checkpoints != null;

        final int runSplitCharacterIndex = splitIndex - splitRun.charactersStart;
        final int runSplitCheckpointIndex = splitRun.getCheckpointIndexOfCharacter(runSplitCharacterIndex);

        if (runSplitCheckpointIndex >= 0) {
            // Do the optimized split
            final int runSplitGlyphIndex = GlyphRun.checkpointGetGlyph(splitRun.checkpoints.get(runSplitCheckpointIndex));

            // Create new run and copy the split values into it
            final GlyphRun<HBFont> newGlyphRun = GlyphRun.obtain(true);
            // newGlyphRun.x, y -> no need to set, set elsewhere
            newGlyphRun.line = splitRun.line + 1;
            newGlyphRun.width = splitRun.width;
            newGlyphRun.color = splitRun.color;
            newGlyphRun.font = splitRun.font;

            newGlyphRun.charactersStart = splitIndex;
            newGlyphRun.charactersEnd = splitRun.charactersEnd;
            newGlyphRun.charactersLevel = splitRun.charactersLevel;
            newGlyphRun.characterFlags = splitRun.characterFlags;// NOTE(jp): At the time of writing, this may copy only FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH

            // optimized (0.1ms)
            final int glyphCount = splitRun.glyphs.size - runSplitGlyphIndex;
            System.arraycopy(splitRun.glyphs.items, runSplitGlyphIndex, newGlyphRun.glyphs.ensureCapacity(glyphCount), 0, glyphCount);
            System.arraycopy(splitRun.glyphX.items, runSplitGlyphIndex, newGlyphRun.glyphX.ensureCapacity(glyphCount), 0, glyphCount);
            System.arraycopy(splitRun.glyphY.items, runSplitGlyphIndex, newGlyphRun.glyphY.ensureCapacity(glyphCount), 0, glyphCount);
            newGlyphRun.glyphs.size = glyphCount;
            newGlyphRun.glyphX.size = glyphCount;
            newGlyphRun.glyphY.size = glyphCount;

            final int characterCount = splitRun.characterPositions.size - runSplitCharacterIndex;
            assert characterCount == newGlyphRun.charactersEnd - newGlyphRun.charactersStart;
            System.arraycopy(splitRun.characterPositions.items, runSplitCharacterIndex, newGlyphRun.characterPositions.ensureCapacity(characterCount), 0, characterCount);
            newGlyphRun.characterPositions.size = characterCount;

            newGlyphRun.setCheckpointsEnabled(true);
            final int checkpointCount = splitRun.checkpoints.size - runSplitCheckpointIndex;
            System.arraycopy(splitRun.checkpoints.items, runSplitCheckpointIndex, newGlyphRun.checkpoints.ensureCapacity(checkpointCount), 0, checkpointCount);
            newGlyphRun.checkpoints.size = checkpointCount;

            // Adjust new values (shift x coordinate)
            final float glyphZeroX = splitRun.glyphX.get(runSplitGlyphIndex);
            newGlyphRun.width -= glyphZeroX;
            for (int i = 0; i < newGlyphRun.glyphX.size; i++) {
                newGlyphRun.glyphX.items[i] -= glyphZeroX;
            }

            final float characterZeroX = splitRun.characterPositions.get(runSplitCharacterIndex);
            for (int i = 0; i < newGlyphRun.characterPositions.size; i++) {
                newGlyphRun.characterPositions.items[i] -= characterZeroX;
            }

            if (runSplitGlyphIndex == 0) {
                // Shouldn't happen
                splitRun.width = 0;
            } else {
                splitRun.width = splitRun.glyphX.get(runSplitGlyphIndex);
            }

            // Trim the split values from the original run
            splitRun.charactersEnd = splitIndex;
            // NOTE(jp): removeRange does not clear removed items to null, so doing just length manipulation
            // has the same effect and is faster
            //splitRun.glyphs.removeRange(runSplitGlyphIndex, splitRun.glyphs.size);
            splitRun.glyphs.size = runSplitGlyphIndex;
            splitRun.glyphX.size = runSplitGlyphIndex;
            splitRun.glyphY.size = runSplitGlyphIndex;
            splitRun.characterPositions.size = runSplitCharacterIndex;
            splitRun.checkpoints.size = runSplitCheckpointIndex;

            // Add the run to layout
            final int insertIndex = runIndex + 1;
            runs.insert(insertIndex, newGlyphRun);
            return insertIndex;
        } else {
            // Do the O(n^2) split
            // (the checkpoint check above probably hit a ligature or something, this shouldn't happen often)
            runs.removeIndex(runIndex);

            startX = splitRun.x;
            int insertIndex = runIndex;
            insertIndex += addRunsFor(chars, charsLength, splitRun.charactersStart, splitIndex, splitRun.charactersLevel,
                    splitRun.font, splitRun.color, splitRun.line, insertIndex, (splitRun.characterFlags & FLAG_GLYPH_RUN_IS_PARAGRAPH_START) != 0, true);

            startX = 0f;// Not really needed as it is reordered later, but cleaner
            addRunsFor(chars, charsLength, splitIndex, splitRun.charactersEnd, splitRun.charactersLevel,
                    splitRun.font, splitRun.color, splitRun.line + 1, // To prevent kerning with previous run
                    insertIndex, true, (splitRun.characterFlags & FLAG_GLYPH_RUN_IS_PARAGRAPH_END) != 0);

            GlyphRun.<HBFont>pool().free(splitRun);
            return insertIndex;
        }
    }

    /** Returns character index of line-break point at which the characters should fold to next line.
     * Character at index and following characters may be {@link #COLLAPSIBLE_SPACE}, in which case the linebreak should
     * be at the first character which is not that character.
     * @param hitIndex which should already be at the next line
     * @return index in [lineStart, lineEnd) range */
    private int findWrapPointFor(LayoutText<HBFont> text, final int lineStart, final int lineEnd, final int hitIndex) {
        assert hitIndex >= lineStart && hitIndex < lineEnd;

        if (hitIndex <= lineStart + 1) {
            // We have to wrap at at least one character, at all times, so no reason to search further
            return lineStart + 1;
        }

        final char[] chars = text.text();
        final Locale locale = text.getLocale();
        if (locale == null) {
            int i = hitIndex;
            if (!Character.isWhitespace(chars[i])) {
                // Shift left, one after the nearest whitespace
                while (i > lineStart && !Character.isWhitespace(chars[i - 1])) {
                    i--;
                }
            } // else: Don't do anything, we can break here

            if (i <= lineStart) {
                // There is no whitespace to hang on, fallback to by-char mode
                i = hitIndex;
            }
            return i;
        } else {
            // Use break iterator
            BreakIterator lineBreakIterator = getLineBreakIterator(text, lineStart, lineEnd, locale);

            if (lineBreakIterator.isBoundary(hitIndex)) {
                // It is already perfect.
                return hitIndex;
            }
            // Can we use hitIndex anyway because of collapsing?
            collapseToNextBreak:
            {
                final int following = lineBreakIterator.following(hitIndex);
                if (following == BreakIterator.DONE) {
                    break collapseToNextBreak;
                }
                for (int i = hitIndex; i < following; i++) {
                    if (chars[i] != COLLAPSIBLE_SPACE) {
                        break collapseToNextBreak;
                    }
                }
                return hitIndex;
            }

            final int preceding = lineBreakIterator.preceding(hitIndex);
            if (preceding == BreakIterator.DONE || preceding <= lineStart) {
                // Fall back to char mode
                return hitIndex;
            }

            return preceding;
        }
    }

    @Override
    protected void doLayoutText(LayoutText<HBFont> text, LayoutTextRunArray<HBFont> textRuns, float availableWidth, float availableHeight, int maxLines, String ellipsis) {
        final Array<GlyphRun<HBFont>> runs = this.runs;

        int line = 0;
        int lineLaidRuns = 0;

        boolean clampLines = false;

        if (textRuns.size <= 0) {
            // At least one line must be always present, even if there is no text run
            addLineHeight(text.fontAt(0).lineHeight);
            return;
        }

        final char[] chars = text.text();
        final int charsLength = text.length();

        boolean paragraphStart = true;
        forTextRuns:
        for (int textRunIndex = 0; textRunIndex < textRuns.size; textRunIndex++) {
            final boolean lastTextRun = textRunIndex + 1 == textRuns.size;
            final TextRun<HBFont> textRun = textRuns.items[textRunIndex];

            final int flags = textRun.flags;
            boolean linebreak = (flags & TextRun.FLAG_LINE_BREAK) != 0;

            if (linebreak) {
                addLinebreakRunFor(textRun, line);
                paragraphStart = true;
            } else if ((textRun.flags & TextRun.FLAG_TAB_STOP) != 0) {
                addTabStopRunFor(text, textRun, line);
            } else {
                addRunsFor(chars, charsLength, textRun.start, textRun.end,
                        textRun.level, textRun.font, textRun.color,
                        line, runs.size,
                        paragraphStart,
                        lastTextRun || (textRuns.items[textRunIndex + 1].flags & TextRun.FLAG_LINE_BREAK) != 0);
                paragraphStart = false;
            }

            // Wrapping
            wrapping:
            while (startX >= availableWidth) {
                assert lineLaidRuns < runs.size;

                if (line + 1 >= maxLines) {
                    // Wrapping this is unnecessary, as it will be ellipsized later.
                    clampLines = true;
                    break forTextRuns;
                }

                // Find character index from which to wrap
                int runIndexOfWrapPoint = runs.size - 1;
                for (int i = lineLaidRuns; i < runs.size - 1; i++) {
                    // TODO(jp): optimize with binary search?
                    final GlyphRun<HBFont> run = runs.items[i];
                    if (run.x + run.width >= availableWidth) {
                        // Wrap this one
                        runIndexOfWrapPoint = i;
                        break;
                    }
                }

                // Find wrap character index inside that run
                final GlyphRun<HBFont> wrapPointRun = runs.items[runIndexOfWrapPoint];
                final int wrapPointCharacterIndex = charEndIndexForTargetRunWidth(wrapPointRun, availableWidth - wrapPointRun.x);

                // Find suitable breaking point
                final int lineCharactersStart = runs.items[lineLaidRuns].charactersStart;
                final int lineCharactersEnd = runs.items[runs.size - 1].charactersEnd;
                final int wrapIndex = findWrapPointFor(text, lineCharactersStart, lineCharactersEnd, wrapPointCharacterIndex);

                // There may be some whitespace after the wrap index, which is not wrapped to the new line, but rather
                // shrunk to 0-width on the last line. This is the index, where such whitespace ends (it starts at wrapIndex).
                int realWrapIndex = wrapIndex;
                // Collapse any spaces at the end of the line
                while (realWrapIndex < lineCharactersEnd && chars[realWrapIndex] == COLLAPSIBLE_SPACE) {
                    realWrapIndex++;
                }

                // Do the actual wrapping
                // From wrapIndex to realWrapIndex collapse spaces and move everything that remains to next line
                // for the normal line-ending code to handle it

                // Find where actual split happens and split it there
                final int splitRunIndex = runIndexWithCharIndex(lineLaidRuns, realWrapIndex);
                int firstRunOnWrappedLineIndex = splitRunForWrap(chars, charsLength, splitRunIndex, realWrapIndex);

                final boolean wrappedBecauseOfCollapsedText = firstRunOnWrappedLineIndex == runs.size;
                // When collapsed spaces end with a newline, it won't be moved to a next line,
                // as a normal letter would do, but it will stay on this line. (This applies only to the first newline.)
                // (This is what happens when editing text on Mac and presumably on other OSes too)

                // wrappedBecauseOfCollapsedText is true when the currently laid text ends with collapsible space,
                // which makes it go over the width, but can be collapsed so it doesn't. Now three things can happen:
                // 1. Normal text run goes after this, in which case it should start on a new line
                //      -> We defer the collapsing and wrapping logic to the next iteration and bail
                // 2. Newline goes after this - this newline should still be part of this line
                //      -> Same as case 1, but the collapsing logic has a special case for this
                // 3. This is the last line
                //      -> We have to do the wrapping now, because there is no other iteration to defer it to,
                //         but we won't increment the line variable, because the cursor should stay on this collapsed line

                if (wrappedBecauseOfCollapsedText) {
                    if (!lastTextRun) {
                        break wrapping;
                    }
                } else {
                    final GlyphRun<HBFont> linebreakRun = runs.items[firstRunOnWrappedLineIndex];
                    if ((linebreakRun.characterFlags & GlyphRun.FLAG_LINEBREAK) != 0) {
                        // This is the special case mentioned in situation 2,
                        // we leave the newline together with the collapsed chars

                        // This special case is really only expected to happen after linebreak has been just added.
                        assert firstRunOnWrappedLineIndex + 1 == runs.size;
                        assert linebreak;

                        firstRunOnWrappedLineIndex++;
                        //realWrapIndex = linebreakRun.charactersEnd;
                        // (above line commented out because realWrapIndex is no longer used after this,
                        // but technically that is what happens)
                    }
                }

                // Collapse everything between wrapIndex and firstRunOnWrappedLineIndex run
                int collapseRunIndex = runIndexWithCharIndex(lineLaidRuns, wrapIndex);
                if (collapseRunIndex < firstRunOnWrappedLineIndex) {
                    // There is something to truncate
                    GlyphRun<HBFont> collapseRun = runs.items[collapseRunIndex];
                    int collapseFromIndex = wrapIndex - collapseRun.charactersStart;
                    assert (collapseRun.characterFlags & GlyphRun.FLAG_TAB) == 0;
                    assert (collapseRun.characterFlags & GlyphRun.FLAG_LINEBREAK) == 0 || collapseRunIndex + 1 == firstRunOnWrappedLineIndex;

                    float collapseToPos;
                    {// Ensure that we don't overflow the available width
                        final int collapseToCharacterIndex = wrapIndex - collapseRun.charactersStart + 1;
                        if (collapseToCharacterIndex >= collapseRun.characterPositions.size) {
                            collapseToPos = collapseRun.width;
                        } else {
                            collapseToPos = collapseRun.characterPositions.items[collapseToCharacterIndex];
                        }

                        final float maxCollapseToPos = availableWidth - collapseRun.x;
                        if (maxCollapseToPos < collapseToPos) {
                            collapseToPos = maxCollapseToPos;
                        }
                    }

                    while (true) {
                        // Set positions of glyphs (space glyphs, presumably, from back to front)
                        for (int i = collapseRun.glyphs.size-1; i > 0; i--) {
                            // NOTE(jp): should technically be x advance, not width, but we don't have any info on that
                            final float glyphXAdvance = collapseRun.glyphs.items[i].width;
                            final float originalX = collapseRun.glyphX.items[i];
                            if (originalX + glyphXAdvance <= collapseToPos) {
                                // Glyphs that were not collapsed start here
                                break;
                            }
                            // This glyph should be moved so that it does not extend the size of the layout
                            collapseRun.glyphX.items[i] = collapseToPos - glyphXAdvance;
                        }

                        // Set positions of characters
                        for (int i = collapseFromIndex; i < collapseRun.characterPositions.size; i++) {
                            collapseRun.characterPositions.items[i] = collapseToPos;
                        }

                        // Trim width
                        collapseRun.width = collapseToPos;

                        // Set flag indicating that this has collapsed spaces
                        collapseRun.characterFlags |= FLAG_GLYPH_RUN_HAS_COLLAPSED_SPACES;

                        // Advance to next run for truncation
                        if (++collapseRunIndex >= firstRunOnWrappedLineIndex) {
                            break;
                        }

                        final GlyphRun<HBFont> newCollapseRun = runs.items[collapseRunIndex];
                        newCollapseRun.x = collapseRun.x + collapseRun.width;
                        // This new run should not have any glyphs
                        assert newCollapseRun.glyphs.size == 0;
                        collapseRun = newCollapseRun;
                        collapseFromIndex = 0;
                        collapseToPos = 0f;
                    }
                }

                // Complete the wrapped line
                completeLine(text, lineLaidRuns, firstRunOnWrappedLineIndex, runs.items[firstRunOnWrappedLineIndex - 1].font);
                lineLaidRuns = firstRunOnWrappedLineIndex;
                line++;

                // Reposition runs whose line was changed
                for (int i = firstRunOnWrappedLineIndex; i < runs.size; i++) {
                    final GlyphRun<HBFont> run = runs.items[i];
                    run.x = startX;
                    startX += run.width;
                    run.line = line;
                }

                // Check vertical limits
                if (line > 0 && getHeight() > availableHeight) {
                    // The line we just completed is too high and will be scrapped, with previous line ellipsized
                    clampLines = true;
                    break forTextRuns;
                }

                // Line wrapping is done. Since this is a while-loop, it may run multiple times, because the wrapped
                // runs may still be too long to fit. This can degrade to "slow" O(n^2) when the font/text is extremely
                // ligature heavy, see splitRunForWrap. Though this shouldn't happen often.
            }

            if (lastTextRun || linebreak) {
                // Line probably has to be completed, unless it has been completed before by wrapping algo

                if (lineLaidRuns < runs.size) {
                    completeLine(text, lineLaidRuns, runs.size, textRun.font);
                    lineLaidRuns = runs.size;
                    line++;
                }

                // Check vertical limits
                if (line >= 2 && getHeight() > availableHeight) {
                    // This line is too high, it will be discarded
                    clampLines = true;
                    break;
                }

                if (line >= maxLines) {
                    // Following lines will be clamped, are there any?
                    if (!lastTextRun || linebreak) {
                        // There is something more, clamp now
                        clampLines = true;
                        break;
                    } // else: It is not a problem, as there is nothing more
                }

                if (lastTextRun && linebreak) {
                    // Last line ends with \n, there should be additional new line with the height of current font
                    addLineHeight(textRun.font.lineHeight);

                    // This definitely leads on to the non-first line. Check if it still fits height requirements.
                    if (getHeight() > availableHeight) {
                        clampLines = true;
                        break;
                    }
                }
            }
        }// end for text runs

        // All relevant text runs were added, ensure that we fit with number of lines/height
        if (clampLines) {
            clampExtraLines(text, availableWidth, availableHeight, maxLines, ellipsis);
        }

        final Array<HBFont> fonts = usedFonts;
        for (HBFont font : fonts) {
            font.prepareGlyphs();
        }
        fonts.clear();
    }

    private void clampExtraLines(final LayoutText<HBFont> text, final float availableWidth, final float availableHeight,
                                 int maxLines, final String ellipsis) {
        final Array<GlyphRun<HBFont>> runs = this.runs;
        final FloatArray lineHeights = this.lineHeights;

        // Discard any line information about the last line or any following lines
        if (getHeight() > availableHeight) {
            // Convert to max lines problem
            // Remove lines that overflow
            while (lineHeights.size > 0 && lineHeights.items[lineHeights.size - 1] > availableHeight) {
                lineHeights.size--;
            }
            // Now we know how many lines we can fit
            maxLines = lineHeights.size;
            // Discard lineHeight of the last (valid) line, because we will recompute it later
            lineHeights.size--;
        } else {
            // Drop extra line heights
            assert lineHeights.size >= maxLines - 1;
            // Last line will be added later
            lineHeights.size = maxLines - 1;
        }
        final int lastAllowedLine = maxLines - 1;

        // Drop extra runs, if any
        for (int i = runs.size - 1; i >= 0; i--) {
            GlyphRun<HBFont> run = runs.items[i];

            if (run.line >= maxLines) {
                runs.items[i] = null;
                runs.size = i;
                GlyphRun.<HBFont>pool().free(run);
            } else {
                if (run.line == maxLines - 1 && (run.characterFlags & GlyphRun.FLAG_LINEBREAK) != 0) {
                    // This run is on a valid line, but it is a linebreak, so it has to go as well
                    runs.items[i] = null;
                    runs.size = i;
                    GlyphRun.<HBFont>pool().free(run);
                }
                break;
            }
        }

        // Update lineLaidRuns to a valid value, as it may be wrong and point to a different line
        int lineLaidRuns = runs.size;
        while (lineLaidRuns > 0 && runs.items[lineLaidRuns - 1].line == lastAllowedLine) {
            lineLaidRuns--;
        }

        // Generate ellipsis and trim the last line to fit it
        int ellipsisStart = runs.size;

        // Reset startX, it may be in inconsistent state
        if (ellipsisStart <= lineLaidRuns) {
            startX = 0f;
        } else {
            final GlyphRun<HBFont> lastRun = runs.items[ellipsisStart - 1];
            startX = lastRun.x + lastRun.width;
        }

        // Add the ellipsis run (no ellipsis = 0-width ellipsis with no runs)
        final float ellipsisWidth;
        if (!ellipsis.isEmpty()) {
            final float startXBeforeEllipsis = startX;
            addEllipsisRunFor(ellipsis, (byte) (text.isLeftToRight() ? 0 : 1),
                    text.getInitialFont(), text.getInitialColor(), lastAllowedLine, ellipsisStart);
            ellipsisWidth = startX - startXBeforeEllipsis;
        } else {
            ellipsisWidth = 0f;
        }

        // Trim previous runs, so that ellipsis fits on the line width-wise
        if (startX > availableWidth) {
            // We need to trim
            int trimmedIndex = ellipsisStart - 1;
            GlyphRun<HBFont> trimmedRun;
            while (trimmedIndex >= 0 && (trimmedRun = runs.items[trimmedIndex]).line == lastAllowedLine) {
                if (trimmedRun.x + ellipsisWidth >= availableWidth) {
                    // Even with this removed, ellipsis won't fit, so remove it
                    startX = trimmedRun.x;
                    GlyphRun.<HBFont>pool().free(trimmedRun);
                    runs.removeIndex(trimmedIndex);
                    ellipsisStart--;
                    trimmedIndex--;
                    continue;
                } else if (trimmedRun.x + trimmedRun.width + ellipsisWidth <= availableWidth) {
                    // This somehow fits, don't do anything
                    break;
                }
                // This run does not need to be removed, but we need to trim it
                startX = trimmedRun.x;
                final boolean trimmedRunLtr = trimmedRun.isLtr();
                int charactersEnd = charEndIndexForTargetRunWidth(trimmedRun, availableWidth - ellipsisWidth - trimmedRun.x);
                runs.removeIndex(trimmedIndex);
                ellipsisStart--;
                if (trimmedRunLtr ? charactersEnd <= trimmedRun.charactersStart : charactersEnd > trimmedRun.charactersEnd) {
                    // Run has to be removed completely anyway
                    break;
                } else {
                    // Run can be re-added, with less characters
                    ellipsisStart += addRunsFor(text.text(), text.length(), trimmedRun.charactersStart, charactersEnd, trimmedRun.charactersLevel,
                            trimmedRun.font, trimmedRun.color, trimmedRun.line, trimmedIndex, (trimmedRun.characterFlags & FLAG_GLYPH_RUN_IS_PARAGRAPH_START) != 0, true);
                }
                GlyphRun.<HBFont>pool().free(trimmedRun);
            }

            // Reposition ellipsis according to current startX
            for (int i = ellipsisStart; i < runs.size; i++) {
                GlyphRun<HBFont> ellipsisRun = runs.items[i];
                ellipsisRun.x = startX;
                startX += ellipsisRun.width;
            }
        }

        // Re-complete the line
        completeLine(text, lineLaidRuns, runs.size, text.getInitialFont());
    }
}
