package com.badlogic.gdx.graphics.text.bitmap;

import com.badlogic.gdx.graphics.text.*;
import com.badlogic.gdx.utils.*;

/**
 * Lays out codepoints for {@link BitmapFontSystem}.
 */
public class BitmapGlyphLayout extends GlyphLayout<BitmapFont> {

    private static final LayoutTextIterator<BitmapFont> _textIterator = new LayoutTextIterator<>();
    private static final Array<BitmapFont> _fonts = new Array<>(true, 10, BitmapFont.class);

    private int lastCodepoint = -1; // For kerning
    private float startX;

    private void addRunsFor(final char[] chars, final int runStart, final int runEnd,
                            int line,
                            final BitmapFont font, final boolean ltr, final float color) {
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

            if (codepoint == '\n' || codepoint == '\t') {
                assert i + 1 == runEnd;
                run.charactersLinebreak = true;
                continue;
            }

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

    @Override
    public void layoutText(LayoutText<BitmapFont> text, float availableWidth, float availableHeight, int horizontalAlign, String elipsis) {
        clear();
        lastCodepoint = -1;

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
        startX = 0f;
        float lineStartY = 0f;

        LayoutTextIterator<BitmapFont> textIterator = BitmapGlyphLayout._textIterator;
        textIterator.start(text);

        Array<BitmapFont> fonts = _fonts;
        int lineFontsFrom = 0;

        int flags;
        boolean hadRegions = false;
        while ((flags = textIterator.next()) != 0) {
            hadRegions = true;
            final int runStart = textIterator.currentStartIndex;
            final int runEnd = textIterator.currentEndIndex;

            final boolean lastRegion = (flags & LayoutTextIterator.FLAG_LAST_REGION) != 0;
            final boolean lastOnLine = (flags & LayoutTextIterator.FLAG_LINE_BREAK) != 0;
            if ((flags & LayoutTextIterator.FLAG_FONT_CHANGE) != 0) {
                lastCodepoint = -1;// Do not kern between fonts
            }

            final int newRunsStart = runs.size;
            // Add the run(s)
            addRunsFor(chars, runStart, runEnd, line, textIterator.currentFont, textIterator.currentLtr, textIterator.currentColor);

            //TODO Line wrapping, \n and \t handling

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

            if (lastOnLine || lastRegion) {
                // Lay out runs on line, vertically
                lastCodepoint = -1;

                // Go through fonts on line and check their max extend from top to baseline and from baseline down
                float topToBaseline = 0f;
                float baselineToDown = 0f;
                if (lineFontsFrom == fonts.size) {
                    // No fonts on line, use only current run font
                    final BitmapFont font = textIterator.currentFont;
                    topToBaseline = font.base;
                    baselineToDown = font.lineHeight - font.base;
                } else {
                    for (int i = lineFontsFrom; i < fonts.size; i++) {
                        final BitmapFont f = fonts.items[i];
                        topToBaseline = Math.max(topToBaseline, f.base);
                        baselineToDown = Math.max(baselineToDown, f.lineHeight - f.base);
                    }
                    lineFontsFrom = fonts.size;
                }

                // Shift each run so that it shares common baseline with all fonts on line
                GlyphRun<BitmapFont> run = null;
                final float finalLineHeight = topToBaseline + baselineToDown;
                for (int i = lineLaidRuns; i < runs.size; i++) {
                    run = runs.items[i];
                    assert run.line == line;
                    run.y = lineStartY - topToBaseline + run.font.base;
                }
                lineStartY -= finalLineHeight;
                height = -lineStartY;
                if (run != null) {
                    // This line had some runs, adjust our overall width
                    this.width = Math.max(this.width, run.x + run.getDrawWidth());
                }

                lineLaidRuns = runs.size;
                lineHeights.add(-lineStartY);
                line++;
                startX = 0f;

                if (lastRegion && lastOnLine) {
                    // Last line ends with \n, there should be additional new line with the height of current font
                    final float trailingLineHeight = textIterator.currentFont.lineHeight;
                    lineStartY -= trailingLineHeight;
                    lineHeights.add(-lineStartY);
                    height = -lineStartY;
                }
            }
        }

        if (hadRegions) {
            for (BitmapFont font : fonts) {
                font.prepareGlyphs();
            }
            fonts.clear();
        } else {
            // At least one line must be always present, even if there is no region
            final BitmapFont initialFont = text.fontAt(0);
            lineHeights.add(height = initialFont.lineHeight);
        }

        textIterator.end();

        buildCharPositions();
    }
}
