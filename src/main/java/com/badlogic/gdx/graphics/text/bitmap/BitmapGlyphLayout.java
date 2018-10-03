package com.badlogic.gdx.graphics.text.bitmap;

import com.badlogic.gdx.graphics.text.*;
import com.badlogic.gdx.graphics.text.LayoutTextRunArray.TextRun;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.Arrays;
import java.util.Locale;

/**
 * Lays out codepoints for {@link BitmapFontSystem}.
 */
public class BitmapGlyphLayout extends GlyphLayout<BitmapFont> {

    private static final byte FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH = (byte) (1 << 7);
    private static final byte FLAG_GLYPH_RUN_HAS_COLLAPSED_SPACES = (byte) (1 << 6);

    /** Borrowed by BitmapGlyphLayout instances when they are doing glyph layout, to store all fonts in the layout. */
    private static final Array<BitmapFont> usedFonts = new Array<>(true, 10, BitmapFont.class);

    private float startX;

    private void addLineHeight(float height) {
        lineHeights.add(getHeight() + height);
    }

    private void addLinebreakRunFor(final TextRun<BitmapFont> textRun, final int line) {
        final int runStart = textRun.start;
        final int runEnd = textRun.end;
        assert runStart < runEnd;

        final GlyphRun<BitmapFont> run = GlyphRun.obtain(false);

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

    private void addTabStopRunFor(final LayoutText<BitmapFont> text, final TextRun<BitmapFont> textRun, final int line) {
        final int runStart = textRun.start;
        final int runEnd = textRun.end;
        assert runStart < runEnd;

        final GlyphRun<BitmapFont> run = GlyphRun.obtain(false);

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

    private BitmapFont.BitmapGlyph findNextKerningGlyph(final int insertIndex, final int line, final byte level, final BitmapFont font) {
        int previousRunIndex = insertIndex - 1;
        while (previousRunIndex >= 0) {
            GlyphRun<BitmapFont> previousRun = runs.items[previousRunIndex];
            if (previousRun.line != line || previousRun.charactersLevel != level || previousRun.font != font
                    || (previousRun.characterFlags & FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH) == 0) {
                break;
            }

            // We should take kern glyph from this, run, but does it have it?
            if (previousRun.glyphs.size > 0) {
                return (BitmapFont.BitmapGlyph) previousRun.glyphs.items[previousRun.glyphs.size - 1];
            }

            // No, try the run before it
            previousRunIndex--;
        }

        return null;
    }

    private void addEllipsisRunFor(String chars, final byte level,
                           final BitmapFont font, final float color, final int line, int insertIndex) {
        final int length = chars.length();
        final boolean ltr = TextRun.isLevelLtr(level);

        final GlyphRun<BitmapFont> run = GlyphRun.obtain(true);
        run.ensureGlyphCapacity(length);

        run.x = startX; // Y set later
        run.line = line;
        run.font = font;
        run.color = color;
        run.charactersLevel = level;
        run.charactersStart = -1;
        run.charactersEnd = -1;
        run.characterFlags |= GlyphRun.FLAG_ELLIPSIS;

        final Array<Glyph> glyphs = run.glyphs;
        final FloatArray glyphX = run.glyphX;

        BitmapFont.BitmapGlyph lastGlyph = findNextKerningGlyph(insertIndex, line, level, font);

        float penX = 0;
        for (int i = ltr ? 0 : length-1; ltr ? i < length : i >= 0; i += ltr ? 1 : -1) {
            int checkpointPosition = i;
            final int codepoint;
            {
                final char c = chars.charAt(i);
                if (Character.isSurrogate(c)) {
                    if (ltr && Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(chars.charAt(i+1))) {
                        // LTR: Valid surrogate pair
                        i++;
                        codepoint = Character.toCodePoint(c, chars.charAt(i));
                    } else if (!ltr && Character.isLowSurrogate(c) && i - 1 >= 0 && Character.isHighSurrogate(chars.charAt(i-1))) {
                        // RTL: Valid surrogate pair
                        i--;
                        codepoint = Character.toCodePoint(c, chars.charAt(i));
                    } else {
                        // Either unexpected low surrogate or incomplete high surrogate, so this is a broken character
                        codepoint = '\uFFFD'; // https://en.wikipedia.org/wiki/Specials_(Unicode_block)#Replacement_character
                    }
                } else {
                    codepoint = c;
                }
            }

            run.createCheckpoint(checkpointPosition, glyphs.size);

            // Normal glyph handling
            BitmapFont.BitmapGlyph glyph = font.getGlyph(codepoint);
            if (glyph == null) {
                byte handling = GlyphLayout.missingGlyphHandling(codepoint);
                if (handling < 0) {
                    glyph = font.getGlyph(Font.MISSING_GLYPH_ID);
                    if (glyph == null) {
                        // Missing glyph and no replacement, ignore it
                        continue;
                    }
                } else if (handling == 0) {
                    // Fully ignored
                    continue;
                } else {
                    // Just advance and continue
                    penX += font.spaceXAdvance * (handling / 8f);
                    lastGlyph = null;
                    continue;
                }
            }

            if (lastGlyph != null) {
                penX += font.getKerning(lastGlyph, glyph);
            }
            glyphs.add(glyph);
            glyphX.add(penX);
            // glyphY is filled in completeLine
            penX += glyph.xAdvance;
            lastGlyph = glyph;
        }

        startX += penX;

        run.width = penX;
        if (lastGlyph != null) {
            run.characterFlags |= FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH;
        }
        runs.insert(insertIndex, run);
    }

    private void addRunsFor_doAddGlyphs(final GlyphRun<BitmapFont> run, final char[] chars, final int runStart, final int runEnd,
                                           BitmapFont.BitmapGlyph lastGlyph, final float[] characterPositions,
                                           final BitmapFont font, final boolean ltr) {
        // Preallocate max possible amount and do rest of the glyph value assignment on raw arrays
        // This has surprisingly significant performance impact, as this loop is very tight.
        final Glyph[] glyphs = run.glyphs.ensureCapacity(runEnd - runStart);
        final float[] glyphX = run.glyphX.ensureCapacity(runEnd - runStart);
        int glyphI = 0;

        final long[] checkpoints = run.checkpoints.ensureCapacity(runEnd - runStart);
        run.checkpoints.size = runEnd - runStart;
        int checkpointI = 0;

        float penX = 0;
        // It would be tempting to create separate LTR/RTL variants of this method,
        // but the performance is not improved and the code is less readable.
        for (int i = ltr ? runStart : runEnd - 1, inc = ltr ? 1 : -1; i < runEnd && i >= runStart; i += inc) {
            final int codepoint;
            {
                final char c = chars[i];
                if (Character.isSurrogate(c)) {
                    if (ltr) {
                        if (Character.isHighSurrogate(c) && i + 1 < runEnd && Character.isLowSurrogate(chars[i + 1])) {
                            // LTR: Valid surrogate pair
                            characterPositions[i - runStart] = penX;
                            i++;
                            characterPositions[i - runStart] = Float.NaN;
                            codepoint = Character.toCodePoint(c, chars[i]);
                        } else {
                            // Either unexpected low surrogate or incomplete high surrogate, so this is a broken character
                            codepoint = '\uFFFD'; // https://en.wikipedia.org/wiki/Specials_(Unicode_block)#Replacement_character
                        }
                    } else {
                        if (Character.isLowSurrogate(c) && i - 1 >= runStart && Character.isHighSurrogate(chars[i - 1])) {
                            // RTL: Valid surrogate pair
                            characterPositions[i - runStart] = Float.NaN;
                            i--;
                            characterPositions[i - runStart] = penX;
                            codepoint = Character.toCodePoint(c, chars[i]);
                        } else {
                            // Either unexpected low surrogate or incomplete high surrogate, so this is a broken character
                            codepoint = '\uFFFD'; // https://en.wikipedia.org/wiki/Specials_(Unicode_block)#Replacement_character
                        }
                    }
                } else {
                    codepoint = c;
                    characterPositions[i - runStart] = penX;
                }
            }

            // Inlined run.createCheckpoint
            final long checkpointValue;
            if (ltr) {
                checkpointValue = ((long) checkpointI << 32) | (glyphI & 0xFFFF_FFFFL);
            } else {
                // Slightly different from LTR variant because of different direction
                // TODO(jp): But is it?
                checkpointValue = ((long) (i - runStart) << 32) | (glyphI & 0xFFFF_FFFFL);
            }
            checkpoints[checkpointI++] = checkpointValue;

            // Normal glyph handling
            BitmapFont.BitmapGlyph glyph = font.getGlyph(codepoint);
            if (glyph == null) {
                byte handling = GlyphLayout.missingGlyphHandling(codepoint);
                if (handling < 0) {
                    glyph = font.getGlyph(Font.MISSING_GLYPH_ID);
                    if (glyph == null) {
                        // Missing glyph and no replacement, ignore it
                        continue;
                    }
                } else if (handling == 0) {
                    // Fully ignored
                    continue;
                } else {
                    // Just advance and continue
                    penX += font.spaceXAdvance * (handling / 8f);
                    lastGlyph = null;
                    continue;
                }
            }

            if (lastGlyph != null) {
                penX += font.getKerning(lastGlyph, glyph);
            }

            glyphs[glyphI] = glyph;
            glyphX[glyphI++] = penX;
            penX += glyph.xAdvance;
            lastGlyph = glyph;
        }

        run.glyphs.size = glyphI;
        run.glyphX.size = glyphI;
        run.width = penX;
        if (lastGlyph != null) {
            run.characterFlags |= FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH;
        }
    }

    /** @return number of added runs (must be >= 1) */
    private int addRunsFor(final char[] chars, final int runStart, final int runEnd, final byte level,
                           final BitmapFont font, final float color, final int line, int insertIndex) {
        assert runStart < runEnd;
        final boolean ltr = TextRun.isLevelLtr(level);

        final GlyphRun<BitmapFont> run = GlyphRun.obtain(true);
        run.ensureGlyphCapacity(runEnd - runStart);

        run.x = startX; // Y set later
        run.line = line;
        run.font = font;
        run.color = color;
        run.charactersLevel = level;
        run.charactersStart = runStart;
        run.charactersEnd = runEnd;
        final float[] characterPositions = run.characterPositions.ensureCapacity(runEnd - runStart);
        run.characterPositions.size = runEnd - runStart;

        BitmapFont.BitmapGlyph lastGlyph = findNextKerningGlyph(insertIndex, line, level, font);

        // Contains performance sensitive loop and putting it into separate method measurably improves performance (desktop JRE)
        addRunsFor_doAddGlyphs(run, chars, runStart, runEnd, lastGlyph, characterPositions, font, ltr);

        // Complete
        startX += run.width;
        runs.insert(insertIndex, run);
        return 1;
    }

    /** Reorders run according to BiDi algorithm, computes line height, sets Y of runs on the line,
     * and adjusts variables for next line. */
    private void completeLine(final LayoutText<BitmapFont> text, final int runsStart, final int runsEnd,
                              final BitmapFont defaultFont) {
        assert runsStart <= runsEnd : runsStart + " <= "+ runsEnd;
        final Array<GlyphRun<BitmapFont>> runs = this.runs;

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
                final GlyphRun<BitmapFont> run = runs.items[i];
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
                final GlyphRun<BitmapFont> run = runs.items[i];
                run.x = x;
                x += run.width;
            }
        }

        // Find out, which fonts are on this line
        final Array<BitmapFont> fonts = usedFonts;
        int usedFontsOnLineFrom = fonts.size;
        for (int i = runsStart; i < runsEnd; i++) {
            final GlyphRun<BitmapFont> run = runs.items[i];
            if (run.glyphs.size == 0) {
                continue;
            }

            final BitmapFont font = run.font;
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
                final BitmapFont f = fonts.items[i];
                topToBaseline = Math.max(topToBaseline, f.base);
                baselineToDown = Math.max(baselineToDown, f.lineHeight - f.base);
            }
        }

        final int line = lineHeights.size;
        final float lineStartY = -getHeight();

        // Shift each run so that it shares common baseline with all fonts on line
        GlyphRun<BitmapFont> run;
        final float finalLineHeight = topToBaseline + baselineToDown;
        for (int i = runsStart; i < runsEnd; i++) {
            run = runs.items[i];
            assert run.line == line;

            final float fontBase = run.font.base;
            run.y = lineStartY - topToBaseline + fontBase;

            // Fill the glyphY here
            // (it is always one constant value, based on the font, and doing it earlier may lead to copying around
            // extra values when wrapping)
            final int glyphCount = run.glyphX.size;
            Arrays.fill(run.glyphY.ensureCapacity(glyphCount), 0, glyphCount, -fontBase);
            run.glyphY.size = glyphCount;
        }

        addLineHeight(finalLineHeight);

        startX = 0f;
    }

    private int runIndexWithCharIndex(int fromRunIndex, int charIndex) {
        final Array<GlyphRun<BitmapFont>> runs = this.runs;
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
    private int charEndIndexForTargetRunWidth(GlyphRun<BitmapFont> run, float targetRunWidth) {
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

    private int splitRunForWrap(final char[] chars, final int runIndex, int splitIndex) {
        final Array<GlyphRun<BitmapFont>> runs = this.runs;

        if (runIndex >= runs.size) {
            return runs.size;
        } else if (runIndex < 0) {
            return 0;
        }

        final GlyphRun<BitmapFont> splitRun = runs.items[runIndex];
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
            final GlyphRun<BitmapFont> newGlyphRun = GlyphRun.obtain(true);
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
            // glyphY is not filled yet, it is filled in completeLine
            newGlyphRun.glyphs.size = glyphCount;
            newGlyphRun.glyphX.size = glyphCount;

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

            // Trim the split values from the original run
            splitRun.charactersEnd = splitIndex;
            // NOTE(jp): removeRange does not clear removed items to null, so doing just length manipulation
            // has the same effect and is faster
            //splitRun.glyphs.removeRange(runSplitGlyphIndex, splitRun.glyphs.size);
            splitRun.glyphs.size = runSplitGlyphIndex;
            splitRun.glyphX.size = runSplitGlyphIndex;
            // glyphY is filled elsewhere
            splitRun.characterPositions.size = runSplitCharacterIndex;
            splitRun.checkpoints.size = runSplitCheckpointIndex;

            if (runSplitGlyphIndex == 0) {
                // Shouldn't happen
                splitRun.width = 0;
            } else {
                final int lastGlyphIndex = runSplitGlyphIndex - 1;
                splitRun.width = splitRun.glyphX.get(lastGlyphIndex)
                        + ((BitmapFont.BitmapGlyph) splitRun.glyphs.get(lastGlyphIndex)).xAdvance;
            }

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
            insertIndex += addRunsFor(chars, splitRun.charactersStart, splitIndex, splitRun.charactersLevel,
                    splitRun.font, splitRun.color, splitRun.line, insertIndex);

            startX = 0f;// Not really needed as it is reordered later, but cleaner
            addRunsFor(chars, splitIndex, splitRun.charactersEnd, splitRun.charactersLevel,
                    splitRun.font, splitRun.color, splitRun.line + 1, // To prevent kerning with previous run
                    insertIndex);

            GlyphRun.<BitmapFont>pool().free(splitRun);
            return insertIndex;
        }
    }

    /** Returns character index of line-break point at which the characters should fold to next line.
     * Character at index and following characters may be {@link #COLLAPSIBLE_SPACE}, in which case the linebreak should
     * be at the first character which is not that character.
     * @param hitIndex which should already be at the next line
     * @return index in [lineStart, lineEnd) range */
    private int findWrapPointFor(LayoutText<BitmapFont> text, final int lineStart, final int lineEnd, final int hitIndex) {
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
    protected void doLayoutText(LayoutText<BitmapFont> text, LayoutTextRunArray<BitmapFont> textRuns, float availableWidth, float availableHeight, int maxLines, String ellipsis) {
        if (textRuns.size <= 0) {
            // At least one line must be always present, even if there is no text run
            addLineHeight(text.fontAt(0).lineHeight);
            return;
        }

        final Array<GlyphRun<BitmapFont>> runs = this.runs;
        final char[] chars = text.text();

        int line = 0;
        int lineLaidRuns = 0;

        boolean clampLines = false;

        forTextRuns:
        for (TextRun<BitmapFont> textRun : textRuns) {
            final int flags = textRun.flags;
            final boolean lastTextRun = (flags & TextRun.FLAG_LAST_RUN) != 0;
            boolean linebreak = (flags & TextRun.FLAG_LINE_BREAK) != 0;

            // Add the run(s)
            if (linebreak) {
                addLinebreakRunFor(textRun, line);
            } else if ((flags & TextRun.FLAG_TAB_STOP) != 0) {
                addTabStopRunFor(text, textRun, line);
            } else {
                addRunsFor(chars, textRun.start, textRun.end, textRun.level, textRun.font, textRun.color, line, runs.size);
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
                    final GlyphRun<BitmapFont> run = runs.items[i];
                    if (run.x + run.width >= availableWidth) {
                        // Wrap this one
                        runIndexOfWrapPoint = i;
                        break;
                    }
                }

                // Find wrap character index inside that run
                final GlyphRun<BitmapFont> wrapPointRun = runs.items[runIndexOfWrapPoint];
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
                int firstRunOnWrappedLineIndex = splitRunForWrap(chars, splitRunIndex, realWrapIndex);

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
                    final GlyphRun<BitmapFont> linebreakRun = runs.items[firstRunOnWrappedLineIndex];
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
                    GlyphRun<BitmapFont> collapseRun = runs.items[collapseRunIndex];
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
                            final float glyphXAdvance = ((BitmapFont.BitmapGlyph) collapseRun.glyphs.items[i]).xAdvance;
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

                        final GlyphRun<BitmapFont> newCollapseRun = runs.items[collapseRunIndex];
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
                    final GlyphRun<BitmapFont> run = runs.items[i];
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
                // (as of writing this, it should never happen, as BitmapFont does not generate any ligatures,
                // but maybe in the future)
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
        }

        if (clampLines) {
            clampExtraLines(text, availableWidth, availableHeight, maxLines, ellipsis);
        }

        final Array<BitmapFont> fonts = usedFonts;
        for (BitmapFont font : fonts) {
            font.prepareGlyphs();
        }
        fonts.clear();
    }

    private void clampExtraLines(final LayoutText<BitmapFont> text, final float availableWidth, final float availableHeight,
                                 int maxLines, final String ellipsis) {
        final Array<GlyphRun<BitmapFont>> runs = this.runs;
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
            GlyphRun<BitmapFont> run = runs.items[i];

            if (run.line >= maxLines) {
                runs.items[i] = null;
                runs.size = i;
                GlyphRun.<BitmapFont>pool().free(run);
            } else {
                if (run.line == maxLines - 1 && (run.characterFlags & GlyphRun.FLAG_LINEBREAK) != 0) {
                    // This run is on a valid line, but it is a linebreak, so it has to go as well
                    runs.items[i] = null;
                    runs.size = i;
                    GlyphRun.<BitmapFont>pool().free(run);
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
            final GlyphRun<BitmapFont> lastRun = runs.items[ellipsisStart - 1];
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
            final char[] chars = text.text();

            // We need to trim
            int trimmedIndex = ellipsisStart - 1;
            GlyphRun<BitmapFont> trimmedRun;
            while (trimmedIndex >= 0 && (trimmedRun = runs.items[trimmedIndex]).line == lastAllowedLine) {
                if (trimmedRun.x + ellipsisWidth >= availableWidth) {
                    // Even with this removed, ellipsis won't fit, so remove it
                    startX = trimmedRun.x;
                    GlyphRun.<BitmapFont>pool().free(trimmedRun);
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
                    ellipsisStart += addRunsFor(chars, trimmedRun.charactersStart, charactersEnd, trimmedRun.charactersLevel,
                            trimmedRun.font, trimmedRun.color, trimmedRun.line, trimmedIndex);
                }
                GlyphRun.<BitmapFont>pool().free(trimmedRun);
            }

            // Reposition ellipsis according to current startX
            for (int i = ellipsisStart; i < runs.size; i++) {
                GlyphRun<BitmapFont> ellipsisRun = runs.items[i];
                ellipsisRun.x = startX;
                startX += ellipsisRun.width;
            }
        }

        // Re-complete the line
        completeLine(text, lineLaidRuns, runs.size, text.getInitialFont());
    }

    @Override
    public void clear() {
        super.clear();
        startX = 0f;
    }
}
