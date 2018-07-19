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

    private static final Array<BitmapFont> _fonts = new Array<>(true, 10, BitmapFont.class);

    private static final char COLLAPSIBLE_SPACE = ' ';

    private int lastCodepoint = -1; // For kerning
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

        if (tabIndex == -1) {
            // Ignore it
            run.charactersFlags |= GlyphRun.FLAG_TAB_LEFT;
            run.width = 0f;
        } else {
            switch (text.tabStopTypeFor(tabIndex)) {
                default:
                    assert false;
                    // Fallthrough
                case LayoutText.TAB_STOP_LEFT:
                    run.charactersFlags |= GlyphRun.FLAG_TAB_LEFT;
                    break;
                case LayoutText.TAB_STOP_CENTER:
                    run.charactersFlags |= GlyphRun.FLAG_TAB_CENTER;
                    break;
                case LayoutText.TAB_STOP_RIGHT:
                    run.charactersFlags |= GlyphRun.FLAG_TAB_RIGHT;
                    break;
            }

            final float offset = text.tabStopOffsetFor(tabIndex, defaultTabAdvance);
            run.width = offset - startX;
            startX += run.width;
        }
        runs.add(run);
    }

    private void addRunsFor(final char[] chars, final TextRun<BitmapFont> textRun, final int line) {
        final int runStart = textRun.start;
        final int runEnd = textRun.end;
        final boolean ltr = textRun.isLtr();
        final BitmapFont font = textRun.font;

        final GlyphRun<BitmapFont> run = GlyphRun.<BitmapFont>pool().obtain();
        run.ensureGlyphCapacity(runEnd - runStart);

        final float[] characterPositions = run.characterPositions.ensureCapacity(runEnd - runStart);
        run.characterPositions.size = runEnd - runStart;

        run.x = startX; // Y set later
        run.line = line;
        run.font = font;
        run.color = textRun.color;
        run.charactersStart = runStart;
        run.charactersEnd = runEnd;
        run.charactersLevel = textRun.level;

        final Array<Glyph> glyphs = run.glyphs;
        final FloatArray glyphX = run.glyphX;
        final FloatArray glyphY = run.glyphY;

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
                lastCodepoint = -1;
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
                    lastCodepoint = -1;
                    continue;
                }
            }

            glyphs.add(glyph);
            if (lastCodepoint >= 0) {
                penX += font.getKerning(lastCodepoint, codepoint);
            }
            glyphX.add(penX);
            penX += glyph.xAdvance;
            glyphY.add(-font.base);

            lastCodepoint = codepoint;
        }

        startX += penX;

        run.width = penX;
        runs.add(run);
    }

    /** Reorders run according to BiDi algorithm,
     * computes line height, sets Y of runs on the line, and adjusts variables for next line. */
    private void completeLine(final LayoutText<BitmapFont> text, final int runsStart, final int runsEnd,
                              final BitmapFont defaultFont, final Array<BitmapFont> fonts, final int lineFontsFrom) {
        assert runsStart < runsEnd;
        final Array<GlyphRun<BitmapFont>> runs = this.runs;
        lastCodepoint = -1;

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
                if ((run.charactersFlags & (GlyphRun.FLAG_LINEBREAK | GlyphRun.FLAG_MASK_TAB)) != 0) {
                    // Reset level of this to paragraph level
                    run.charactersLevel = (byte) (text.isLeftToRight() ? 0 : 1);
                }

                and &= run.charactersFlags;
                or |= run.charactersFlags;
            }

            if ((or & 1) == 0) {
                // All levels are LTR, no need to reorder
                break reordering;
            }
            if ((and & 1) == 0) {
                // All levels are RTL, simple all-reorder
                reverse(runs.items, runsStart, runsEnd);
                break reordering;
            }

            // Full bidi reordering needed
            final byte[] levels = bidiLevelsFor(runs.items, runsStart, runsEnd);
            Bidi.reorderVisually(levels, 0, runs.items, runsStart, runsEnd - runsStart);
        }

        // Go through fonts on line and check their max extend from top to baseline and from baseline down
        float topToBaseline = 0f;
        float baselineToDown = 0f;
        if (lineFontsFrom == fonts.size) {
            // No fonts on line, use only current run font
            topToBaseline = defaultFont.base;
            baselineToDown = defaultFont.lineHeight - defaultFont.base;
        } else {
            for (int i = lineFontsFrom; i < fonts.size; i++) {
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

    @Override
    public void layoutText(LayoutText<BitmapFont> text, float availableWidth, float availableHeight, int horizontalAlign, String elipsis) {
        clear();

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

        Array<BitmapFont> fonts = _fonts;
        int lineFontsFrom = 0;

        boolean hadTextRuns = false;
        BitmapFont lastFont = null;
        byte lastLevel = 0;

        final LayoutTextRunIterable<BitmapFont> iterable = LayoutTextRunIterable.obtain(text);
        for (TextRun<BitmapFont> textRun : iterable) {
            hadTextRuns = true;

            final int flags = textRun.flags;
            final boolean lastTextRun = (flags & TextRun.FLAG_LAST_RUN) != 0;
            final boolean linebreak = (flags & TextRun.FLAG_LINE_BREAK) != 0;
            boolean completeLine = lastTextRun || linebreak;

            if (lastFont != textRun.font || lastLevel != textRun.level) {
                // Do not kern between fonts, nor between bidi levels
                lastCodepoint = -1;
                lastFont = textRun.font;
                lastLevel = textRun.level;
            }

            final int newRunsStart = runs.size;
            // Add the run(s)
            if (linebreak) {
                addLinebreakRunFor(textRun, line);
            } else if ((flags & TextRun.FLAG_TAB_STOP) != 0) {
                addTabStopRunFor(text, textRun, line);
            } else {
                addRunsFor(chars, textRun, line);
            }

            // Ensure that fonts of line contain fonts of lastly added runs
            for (int i = newRunsStart; i < runs.size; i++) {
                final GlyphRun<BitmapFont> run = runs.items[i];
                if (run.glyphs.size == 0) {
                    continue;
                }
                final BitmapFont font = run.font;

                final int fontIndex = fonts.indexOf(font, true);
                if (fontIndex < 0) {
                    fonts.add(font);
                } else if (fontIndex < lineFontsFrom) {
                    lineFontsFrom--;
                    fonts.swap(fontIndex, lineFontsFrom);
                }// else: already good
            }

            // Wrapping
            wrapping:
            if (startX >= availableWidth) {
                assert lineLaidRuns < runs.size;
                assert newRunsStart >= lineLaidRuns;

                // Find character index from which to wrap
                int runIndexOfWrapPoint = runs.size - 1;
                for (int i = newRunsStart; i < runs.size - 1; i++) {
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
                for (int i = 0; i < wrapPointRun.characterPositions.size; i++) {
                    final float position = wrapPointRun.characterPositions.items[i];
                    if (Float.isNaN(position)) {
                        continue;
                    }
                    if (wrapPointRun.x + position >= availableWidth) {
                        break;
                    }
                    characterIndexInWrapRun = i;
                }
                final int wrapCharacterIndex = wrapPointRun.charactersStart + characterIndexInWrapRun;

                // Find suitable breaking point
                final int lineCharactersStart = runs.items[lineLaidRuns].charactersStart;
                final int lineCharactersEnd = runs.items[runs.size-1].charactersEnd;
                final BreakIterator lineBreakIterator = getLineBreakIterator(
                        text, lineCharactersStart, lineCharactersEnd);
                int wrapIndex = lineBreakIterator.preceding(wrapCharacterIndex);
                if (wrapIndex == BreakIterator.DONE) {
                    // Fallback to char-based breaking
                    wrapIndex = wrapCharacterIndex;
                }

                if (wrapIndex <= lineCharactersStart) {
                    // At least one character must be on line
                    wrapIndex = lineCharactersStart + 1;
                    if (wrapIndex == lineCharactersEnd) {
                        // Not much to do here, we only have one character to work with
                        //TODO Resolve
                        break wrapping;
                    }
                }

                int realWrapIndex = wrapIndex;
                // Collapse any spaces at the end of the line
                while (realWrapIndex < lineCharactersEnd && chars[realWrapIndex] == COLLAPSIBLE_SPACE) {
                    realWrapIndex++;
                }

                // Do the actual wrapping
                //TODO
            }

            if (linebreak || lastTextRun) {
                completeLine(text, lineLaidRuns, runs.size, textRun.font, fonts, lineFontsFrom);
                lineLaidRuns = runs.size;
                lineFontsFrom = fonts.size;
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
        lastCodepoint = -1;
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
