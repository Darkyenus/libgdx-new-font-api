package com.badlogic.gdx.graphics.text.bitmap;

import com.badlogic.gdx.graphics.text.*;
import com.badlogic.gdx.graphics.text.LayoutTextRunIterable.TextRun;
import com.badlogic.gdx.graphics.text.util.CharArrayIterator;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ByteArray;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ObjectMap;

import java.text.Bidi;
import java.text.BreakIterator;
import java.util.Locale;

/**
 * Lays out codepoints for {@link BitmapFontSystem}.
 */
public class BitmapGlyphLayout extends GlyphLayout<BitmapFont> {

    private static final byte FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH = (byte) (1 << 7);

    private static final Array<BitmapFont> _fonts = new Array<>(true, 10, BitmapFont.class);

    private static final char COLLAPSIBLE_SPACE = ' ';

    private float startX;

    private void addLineHeight(float height) {
        lineHeights.add(getHeight() + height);
    }

    private void addLinebreakRunFor(final TextRun<BitmapFont> textRun, final int line) {
        final int runStart = textRun.start;
        final int runEnd = textRun.end;
        assert runStart < runEnd;

        final GlyphRun<BitmapFont> run = GlyphRun.<BitmapFont>pool().obtain();

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

        final GlyphRun<BitmapFont> run = GlyphRun.<BitmapFont>pool().obtain();

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

    /** @return number of added runs (must be >= 1) */
    private int addRunsFor(final char[] chars, final int runStart, final int runEnd, final byte level,
                           final BitmapFont font, final float color, final int line, int insertIndex,
                           boolean ellipsis) {
        assert runStart < runEnd;
        final boolean ltr = TextRun.isLevelLtr(level);

        final GlyphRun<BitmapFont> run = GlyphRun.<BitmapFont>pool().obtain();
        run.ensureGlyphCapacity(runEnd - runStart);

        final float[] characterPositions;

        run.x = startX; // Y set later
        run.line = line;
        run.font = font;
        run.color = color;
        run.charactersLevel = level;
        if (ellipsis) {
            run.charactersStart = -1;
            run.charactersEnd = -1;
            run.characterFlags |= GlyphRun.FLAG_ELLIPSIS;
            characterPositions = null;
        } else {
            run.charactersStart = runStart;
            run.charactersEnd = runEnd;
            characterPositions = run.characterPositions.ensureCapacity(runEnd - runStart);
            run.characterPositions.size = runEnd - runStart;
        }

        final Array<Glyph> glyphs = run.glyphs;
        final FloatArray glyphX = run.glyphX;
        final FloatArray glyphY = run.glyphY;

        BitmapFont.BitmapGlyph lastGlyph = null;
        {
            int previousRunIndex = insertIndex - 1;
            while (previousRunIndex >= 0) {
                GlyphRun<BitmapFont> previousRun = runs.items[insertIndex - 1];
                if (previousRun.line != line || previousRun.charactersLevel != level || previousRun.font != font
                        || (previousRun.characterFlags & FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH) == 0) {
                    break;
                }

                // We should take kern glyph from this, run, but does it have it?
                if (previousRun.glyphs.size > 0) {
                    lastGlyph = (BitmapFont.BitmapGlyph) previousRun.glyphs.items[previousRun.glyphs.size - 1];
                    break;
                }

                // No, try the run before it
                previousRunIndex--;
            }
        }

        float penX = 0;
        for (int i = ltr ? runStart : runEnd-1; ltr ? i < runEnd : i >= runStart; i += ltr ? 1 : -1) {
            final int codepoint;
            {
                final char c = chars[i];
                if (Character.isSurrogate(c)) {
                    if (ltr && Character.isHighSurrogate(c) && i + 1 < runEnd && Character.isLowSurrogate(chars[i+1])) {
                        // LTR: Valid surrogate pair
                        if (ellipsis) {
                            i++;
                        } else {
                            characterPositions[i - runStart] = penX;
                            i++;
                            characterPositions[i - runStart] = Float.NaN;
                        }
                        codepoint = Character.toCodePoint(c, chars[i]);
                    } else if (!ltr && Character.isLowSurrogate(c) && i - 1 >= runStart && Character.isHighSurrogate(chars[i-1])) {
                        // RTL: Valid surrogate pair
                        if (ellipsis) {
                            i--;
                        } else {
                            characterPositions[i - runStart] = Float.NaN;
                            i--;
                            characterPositions[i - runStart] = penX;
                        }
                        codepoint = Character.toCodePoint(c, chars[i]);
                    } else {
                        // Either unexpected low surrogate or incomplete high surrogate, so this is a broken character
                        codepoint = '\uFFFD'; // https://en.wikipedia.org/wiki/Specials_(Unicode_block)#Replacement_character
                    }
                } else {
                    codepoint = c;
                    if (!ellipsis) {
                        characterPositions[i - runStart] = penX;
                    }
                }
            }

            // We need to guarantee that space doesn't have a glyph, because of space collapsing when line-wrapping
            if (codepoint == COLLAPSIBLE_SPACE) {
                penX += font.spaceXAdvance;
                lastGlyph = null;
                continue;
            }
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

            glyphs.add(glyph);
            if (lastGlyph != null) {
                penX += font.getKerning(lastGlyph, glyph);
            }
            glyphX.add(penX);
            penX += glyph.xAdvance;
            glyphY.add(-font.base);

            lastGlyph = glyph;
        }

        startX += penX;

        run.width = penX;
        if (lastGlyph != null) {
            run.characterFlags |= FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH;
        }
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
        final Array<BitmapFont> fonts = _fonts;
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
            run.y = lineStartY - topToBaseline + run.font.base;
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
        } else if (splitIndex >= splitRun.charactersEnd) {
            // No splitting necessary
            return runIndex + 1;
        }

        runs.removeIndex(runIndex);

        startX = splitRun.x;
        int insertIndex = runIndex;
        insertIndex += addRunsFor(chars, splitRun.charactersStart, splitIndex, splitRun.charactersLevel,
                splitRun.font, splitRun.color, splitRun.line, insertIndex, false);

        startX = 0f;// Not really needed, but cleaner
        addRunsFor(chars, splitIndex, splitRun.charactersEnd, splitRun.charactersLevel,
                splitRun.font, splitRun.color, splitRun.line, insertIndex, false);

        GlyphRun.<BitmapFont>pool().free(splitRun);
        return insertIndex;
    }

    /**
     * Returns character index of line-break point at which the characters should fold to next line.
     * Character at index and following characters may be {@link #COLLAPSIBLE_SPACE}, in which case the linebreak should
     * be at the first character which is not that character.
     * @param hitIndex which should already be at the next line
     * @return index in [lineStart, lineEnd) range
     */
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
    protected void doLayoutText(LayoutText<BitmapFont> text, float availableWidth, float availableHeight, int maxLines, String ellipsis) {
        final Array<GlyphRun<BitmapFont>> runs = this.runs;
        final Array<BitmapFont> fonts = _fonts;
        final char[] chars = text.text();

        int line = 0;
        int lineLaidRuns = 0;

        boolean hadTextRuns = false;
        boolean clampLines = false;

        final LayoutTextRunIterable<BitmapFont> iterable = LayoutTextRunIterable.obtain(text);
        forTextRuns:
        for (TextRun<BitmapFont> textRun : iterable) {
            hadTextRuns = true;

            final int flags = textRun.flags;
            final boolean lastTextRun = (flags & TextRun.FLAG_LAST_RUN) != 0;
            final boolean linebreak = (flags & TextRun.FLAG_LINE_BREAK) != 0;

            // Add the run(s)
            if (linebreak) {
                addLinebreakRunFor(textRun, line);
            } else if ((flags & TextRun.FLAG_TAB_STOP) != 0) {
                addTabStopRunFor(text, textRun, line);
            } else {
                addRunsFor(chars, textRun.start, textRun.end, textRun.level, textRun.font, textRun.color, line, runs.size, false);
            }

            // Wrapping
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
                    //TODO optimize with binary search?
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
                final int firstRunOnWrappedLineIndex = splitRunForWrap(chars, splitRunIndex, realWrapIndex);

                // Truncate everything between wrapIndex and firstRunOnWrappedLineIndex run
                int truncateRunIndex = runIndexWithCharIndex(lineLaidRuns, wrapIndex);
                if (truncateRunIndex < firstRunOnWrappedLineIndex) {
                    // There is something to truncate
                    GlyphRun<BitmapFont> truncateRun = runs.items[truncateRunIndex];
                    int truncateFromIndex = wrapIndex - truncateRun.charactersStart;
                    assert (truncateRun.characterFlags & (GlyphRun.FLAG_LINEBREAK | GlyphRun.FLAG_TAB)) == 0;
                    float truncateToPos = truncateRun.characterPositions.items[wrapIndex - truncateRun.charactersStart];

                    while (true) {
                        // Set positions
                        for (int i = truncateFromIndex; i < truncateRun.characterPositions.size; i++) {
                            truncateRun.characterPositions.items[i] = truncateToPos;
                        }
                        // Trim width
                        truncateRun.width = truncateToPos;

                        // Advance to next run for truncation
                        if (++truncateRunIndex >= firstRunOnWrappedLineIndex) {
                            break;
                        }

                        final GlyphRun<BitmapFont> newTruncateRun = runs.items[truncateRunIndex];
                        newTruncateRun.x = truncateRun.x + truncateRun.width;
                        // This new run should not have any glyphs
                        assert newTruncateRun.glyphs.size == 0;
                        truncateRun = newTruncateRun;
                        truncateFromIndex = 0;
                        truncateToPos = 0f;
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
                    assert run.line == line - 1;
                    run.line = line;
                }

                // Check vertical limits
                if (line > 0 && getHeight() > availableHeight) {
                    // The line we just completed is too high and will be scrapped, with previous line ellipsized
                    clampLines = true;
                    break forTextRuns;
                }

                // Line wrapping is done. Since this is a while-loop, it may run multiple times, because the wrapped
                // runs may still be too long to fit. This is sadly O(n^2), but will be pretty hard to optimize.
                // If this turns out to be a problem, reducing the length of text in runs should help, either by changing
                // styling or by introducing explicit linebreaks.
            }

            if (lastTextRun || linebreak) {
                completeLine(text, lineLaidRuns, runs.size, textRun.font);
                lineLaidRuns = runs.size;

                // Check vertical limits
                if (line > 0 && getHeight() > availableHeight) {
                    // This line is too high, it will be discarded
                    clampLines = true;
                    break;
                }

                line++;

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
            final FloatArray lineHeights = this.lineHeights;

            // Discard any line information about the last line or any following lines
            if (getHeight() > availableHeight) {
                // Convert to max lines problem
                // Remove lines that overflow
                while (lineHeights.size > 0 && lineHeights.items[lineHeights.size-1] > availableHeight) {
                    lineHeights.size--;
                }
                // Now we know how many lines we can fit
                maxLines = lineHeights.size;
                // Discard lineHeight of the last (valid) line, because we will recompute it later
                lineHeights.size--;
            } else {
                // Drop extra line heights
                assert lineHeights.size >= maxLines - 1;
                lineHeights.size = maxLines - 1;
            }
            line = maxLines - 1;

            // Drop extra runs, if any
            for (int i = runs.size-1; i >= 0; i--) {
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
            lineLaidRuns = runs.size;
            while (lineLaidRuns > 0 && runs.items[lineLaidRuns - 1].line == line) {
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
            if (ellipsis != null && !ellipsis.isEmpty()) {
                final float startXBeforeEllipsis = startX;
                addRunsFor(ellipsis.toCharArray(), 0, ellipsis.length(),
                        (byte) (text.isLeftToRight() ? 0 : 1), text.getInitialFont(), text.getInitialColor(),
                        line, ellipsisStart, true);
                ellipsisWidth = startX - startXBeforeEllipsis;
            } else {
                ellipsisWidth = 0f;
            }

            // Trim previous runs, so that ellipsis fits on the line width-wise
            if (startX > availableWidth) {
                // We need to trim
                int trimmedIndex = ellipsisStart - 1;
                GlyphRun<BitmapFont> trimmedRun;
                while (trimmedIndex >= 0 && (trimmedRun = runs.items[trimmedIndex]).line == line) {
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
                                trimmedRun.font, trimmedRun.color, trimmedRun.line, trimmedIndex, false);
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

        LayoutTextRunIterable.free(iterable);

        if (hadTextRuns) {
            for (BitmapFont font : fonts) {
                font.prepareGlyphs();
            }
            fonts.clear();
        } else {
            // At least one line must be always present, even if there is no text run
            addLineHeight(text.fontAt(0).lineHeight);
        }
    }

    @Override
    public void clear() {
        super.clear();
        _fonts.clear();
        startX = 0f;
    }

    private static ObjectMap<Locale, BreakIterator> getLineBreakIterator_lineBreakIteratorCache;
    private static CharArrayIterator getLineBreakIterator_charIteratorCache;

    private static <F extends Font<F>> BreakIterator getLineBreakIterator(LayoutText<F> text, int start, int end, Locale locale) {
        ObjectMap<Locale, BreakIterator> brItMap = BitmapGlyphLayout.getLineBreakIterator_lineBreakIteratorCache;
        CharArrayIterator charIterator = getLineBreakIterator_charIteratorCache;
        if (brItMap == null) {
            brItMap = getLineBreakIterator_lineBreakIteratorCache = new ObjectMap<>();
            charIterator = getLineBreakIterator_charIteratorCache = new CharArrayIterator();
        }
        charIterator.reset(text.text(), start, end);

        BreakIterator breakIterator = brItMap.get(locale);
        if (breakIterator == null) {
            breakIterator = BreakIterator.getLineInstance(locale);
            brItMap.put(locale, breakIterator);
        }
        breakIterator.setText(charIterator);
        return breakIterator;
    }

    private static <T> void reverse(T[] items, int start, int end) {
        for (int first = start, last = end - 1; first < last; first++, last--) {
            T temp = items[first];
            items[first] = items[last];
            items[last] = temp;
        }
    }

    private static ByteArray bidiLevelsFor_levelCache;

    private static <F extends Font<F>> byte[] bidiLevelsFor(GlyphRun<F>[] runs, int runsStart, int runsEnd) {
        ByteArray levels = bidiLevelsFor_levelCache;
        final int runCount = runsEnd - runsStart;
        if (levels == null) {
            levels = bidiLevelsFor_levelCache = new ByteArray(true, runCount);
        } else {
            levels.size = 0;
            levels.ensureCapacity(runCount);
        }
        levels.size = runCount;
        final byte[] levelItems = levels.items;

        for (int i = 0; i < runCount; i++) {
            levelItems[i] = runs[runsStart + i].charactersLevel;
        }
        return levelItems;
    }
}
