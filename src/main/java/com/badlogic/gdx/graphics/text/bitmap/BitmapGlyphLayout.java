package com.badlogic.gdx.graphics.text.bitmap;

import com.badlogic.gdx.graphics.text.CharArrayIterator;
import com.badlogic.gdx.graphics.text.*;
import com.badlogic.gdx.graphics.text.LayoutTextRunIterable.TextRun;
import com.badlogic.gdx.utils.*;

import java.text.Bidi;
import java.text.BreakIterator;

/**
 * Lays out codepoints for {@link BitmapFontSystem}.
 */
public class BitmapGlyphLayout extends GlyphLayout<BitmapFont> {

    private static final byte FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH = (byte) (1 << 7);

    private static final Array<BitmapFont> _fonts = new Array<>(true, 10, BitmapFont.class);

    private static final char COLLAPSIBLE_SPACE = ' ';

    private float startX;
    private float lineStartY;

    private void addLineHeight(float height) {
        lineStartY -= height;
        lineHeights.add(-lineStartY);
        this.height = -lineStartY;
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
        run.charactersFlags |= GlyphRun.FLAG_LINEBREAK;
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

        run.charactersFlags |= GlyphRun.FLAG_TAB;
        if (tabIndex == -1) {
            // Ignore it
            run.width = 0f;
        } else {
            final float offset = text.tabStopOffsetFor(tabIndex, defaultTabAdvance);
            run.width = offset - startX;
            startX += run.width;
        }
        runs.add(run);
    }

    /** @return number of added runs (must be >= 1) */
    private int addRunsFor(final char[] chars, final int runStart, final int runEnd, final byte level,
                           final BitmapFont font, final float color, final int line, int insertIndex) {
        assert runStart < runEnd;
        final boolean ltr = TextRun.isLevelLtr(level);

        final GlyphRun<BitmapFont> run = GlyphRun.<BitmapFont>pool().obtain();
        run.ensureGlyphCapacity(runEnd - runStart);

        final float[] characterPositions = run.characterPositions.ensureCapacity(runEnd - runStart);
        run.characterPositions.size = runEnd - runStart;

        run.x = startX; // Y set later
        run.line = line;
        run.font = font;
        run.color = color;
        run.charactersStart = runStart;
        run.charactersEnd = runEnd;
        run.charactersLevel = level;

        final Array<Glyph> glyphs = run.glyphs;
        final FloatArray glyphX = run.glyphX;
        final FloatArray glyphY = run.glyphY;

        BitmapFont.BitmapGlyph lastGlyph = null;
        {
            int previousRunIndex = insertIndex - 1;
            while (previousRunIndex >= 0) {
                GlyphRun<BitmapFont> previousRun = runs.items[insertIndex - 1];
                if (previousRun.line != line || previousRun.charactersLevel != level || previousRun.font != font
                        || (previousRun.charactersFlags & FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH) == 0) {
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
                        characterPositions[i - runStart] = penX;
                        i++;
                        characterPositions[i - runStart] = Float.NaN;
                        codepoint = Character.toCodePoint(c, chars[i]);
                    } else if (!ltr && Character.isLowSurrogate(c) && i - 1 >= runStart && Character.isHighSurrogate(chars[i-1])) {
                        // RTL: Valid surrogate pair
                        characterPositions[i - runStart] = Float.NaN;
                        i--;
                        characterPositions[i - runStart] = penX;
                        codepoint = Character.toCodePoint(c, chars[i]);
                    } else {
                        // Either unexpected low surrogate or incomplete high surrogate, so this is a broken character
                        codepoint = '\uFFFD'; // https://en.wikipedia.org/wiki/Specials_(Unicode_block)#Replacement_character
                    }
                } else {
                    codepoint = c;
                    characterPositions[i - runStart] = penX;
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
            run.charactersFlags |= FLAG_GLYPH_RUN_KERN_TO_LAST_GLYPH;
        }
        runs.insert(insertIndex, run);
        return 1;
    }

    /** Reorders run according to BiDi algorithm,
     * computes line height, sets Y of runs on the line, and adjusts variables for next line. */
    private void completeLine(final LayoutText<BitmapFont> text, final int runsStart, final int runsEnd,
                              final BitmapFont defaultFont) {
        assert runsStart < runsEnd : runsStart + " < "+ runsEnd;
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
                if ((run.charactersFlags & (GlyphRun.FLAG_LINEBREAK | GlyphRun.FLAG_TAB)) != 0) {
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

        // Shift each run so that it shares common baseline with all fonts on line
        GlyphRun<BitmapFont> run = null;
        final float finalLineHeight = topToBaseline + baselineToDown;
        for (int i = runsStart; i < runsEnd; i++) {
            run = runs.items[i];
            assert run.line == line;
            run.y = lineStartY - topToBaseline + run.font.base;
        }

        addLineHeight(finalLineHeight);

        // This line had some runs, adjust our overall width
        this.width = Math.max(this.width, run.x + run.getDrawWidth());

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
                splitRun.font, splitRun.color, splitRun.line, insertIndex);

        startX = 0f;// Not really needed, but cleaner
        addRunsFor(chars, splitIndex, splitRun.charactersEnd, splitRun.charactersLevel,
                splitRun.font, splitRun.color, splitRun.line, insertIndex);

        GlyphRun.<BitmapFont>pool().free(splitRun);
        return insertIndex;
    }

    @Override
    public void layoutText(LayoutText<BitmapFont> text, float availableWidth, float availableHeight, int horizontalAlign, String elipsis) {
        clear();
        final Array<BitmapFont> fonts = _fonts;
        fonts.clear();

        if (availableWidth <= 0) {
            availableWidth = Float.POSITIVE_INFINITY;
        }
        int maxLines = Integer.MAX_VALUE;
        if (availableHeight == 0f) {
            availableHeight = Float.POSITIVE_INFINITY;
        } else if (availableHeight < 0f) {
            maxLines = Math.max(Math.round(-availableHeight), 1);
            availableHeight = Float.POSITIVE_INFINITY;
        }

        final char[] chars = text.text();
        final Array<GlyphRun<BitmapFont>> runs = this.runs;

        int line = 0;
        int lineLaidRuns = 0;

        boolean hadTextRuns = false;

        final LayoutTextRunIterable<BitmapFont> iterable = LayoutTextRunIterable.obtain(text);
        for (TextRun<BitmapFont> textRun : iterable) {
            hadTextRuns = true;

            final int flags = textRun.flags;
            final boolean lastTextRun = (flags & TextRun.FLAG_LAST_RUN) != 0;
            final boolean linebreak = (flags & TextRun.FLAG_LINE_BREAK) != 0;
            boolean completeLine = lastTextRun || linebreak;

            // Add the run(s)
            if (linebreak) {
                addLinebreakRunFor(textRun, line);
            } else if ((flags & TextRun.FLAG_TAB_STOP) != 0) {
                addTabStopRunFor(text, textRun, line);
            } else {
                addRunsFor(chars, textRun.start, textRun.end, textRun.level, textRun.font, textRun.color, line, runs.size);
            }

            //TODO Tab stops

            // Wrapping
            while (startX >= availableWidth) {
                assert lineLaidRuns < runs.size;

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
                int characterIndexInWrapRun = 0;
                for (int i = 0; i < wrapPointRun.characterPositions.size; i++) {//TODO optimize with binary search?
                    final float position = wrapPointRun.characterPositions.items[i];
                    if (Float.isNaN(position)) {
                        continue;
                    }
                    if (wrapPointRun.x + position >= availableWidth) {
                        break;
                    }
                    characterIndexInWrapRun = i;
                }
                final int wrapPointCharacterIndex = wrapPointRun.charactersStart + characterIndexInWrapRun;

                // Find suitable breaking point
                final int lineCharactersStart = runs.items[lineLaidRuns].charactersStart;
                final int lineCharactersEnd = runs.items[runs.size - 1].charactersEnd;
                final BreakIterator lineBreakIterator = getLineBreakIterator(
                        text, lineCharactersStart, lineCharactersEnd);
                // Character wrap index denotes the first character, which should already be on a new line
                int wrapIndex = lineBreakIterator.preceding(wrapPointCharacterIndex);
                if (wrapIndex == BreakIterator.DONE || wrapIndex <= lineCharactersStart) {
                    // Fallback to char-based breaking
                    wrapIndex = wrapPointCharacterIndex;
                }

                if (wrapIndex <= lineCharactersStart) {
                    // At least one character must be on line
                    wrapIndex = lineCharactersStart + 1;
                    if (wrapIndex == lineCharactersEnd) {
                        // Not much to do here, we only have one character to work with and we can't wrap that.
                        // Just mark the line as ended for normal line-ending code below
                        completeLine = true;
                        break;// no more wrapping
                    }
                }

                // There may be some whitespace after the wrap index, which is not wrapped to the new line, but rather
                // shrunk to 0-width on the last line. This is the index, where such whitespace ends (it starts at wrapIndex).
                int realWrapIndex = wrapIndex;
                // Collapse any spaces at the end of the line
                while (realWrapIndex < lineCharactersEnd && chars[realWrapIndex] == COLLAPSIBLE_SPACE) {
                    //TODO Is this triggered correctly?
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
                    assert (truncateRun.charactersFlags & (GlyphRun.FLAG_LINEBREAK | GlyphRun.FLAG_TAB)) == 0;
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

                // Line wrapping is done. Since this is a while-loop, it may run multiple times, because the wrapped
                // runs may still be too long to fit. This is sadly O(n^2), but will be pretty hard to optimize.
                // If this turns out to be a problem, reducing the length of text in runs should help, either by changing
                // styling or by introducing explicit linebreaks.
            }

            if (completeLine) {
                completeLine(text, lineLaidRuns, runs.size, textRun.font);
                lineLaidRuns = runs.size;
                line++;

                if (lastTextRun && linebreak) {
                    // Last line ends with \n, there should be additional new line with the height of current font
                    addLineHeight(textRun.font.lineHeight);
                }
            }
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

        buildCharPositions();
    }

    @Override
    public void clear() {
        super.clear();
        startX = 0f;
        lineStartY = 0f;
    }

    private static BreakIterator getLineBreakIterator_lineBreakIteratorCache;
    private static CharArrayIterator getLineBreakIterator_charIteratorCache;

    private static <F extends Font> BreakIterator getLineBreakIterator(LayoutText<F> text, int start, int end) {
        //TODO Should something be done about locale?
        BreakIterator breakIterator = BitmapGlyphLayout.getLineBreakIterator_lineBreakIteratorCache;
        CharArrayIterator charIterator = getLineBreakIterator_charIteratorCache;
        if (breakIterator == null) {
            breakIterator = getLineBreakIterator_lineBreakIteratorCache = BreakIterator.getLineInstance();
            charIterator = getLineBreakIterator_charIteratorCache = new CharArrayIterator();
        }
        charIterator.reset(text.text(), start, end);
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

    private static <F extends Font> byte[] bidiLevelsFor(GlyphRun<F>[] runs, int runsStart, int runsEnd) {
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
