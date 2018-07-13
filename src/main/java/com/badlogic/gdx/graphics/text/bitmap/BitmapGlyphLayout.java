package com.badlogic.gdx.graphics.text.bitmap;

import com.badlogic.gdx.graphics.text.*;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.Pool;

/**
 * Lays out codepoints for {@link BitmapFontSystem}.
 */
public class BitmapGlyphLayout extends GlyphLayout<BitmapGlyphLayout, BitmapFont> {

    private static final LayoutTextIterator<BitmapFont> _textIterator = new LayoutTextIterator<>();
    private static final Array<BitmapFont> _fonts = new Array<>(true, 10, BitmapFont.class);

    @Override
    public void layoutText(LayoutText<BitmapFont> text, float availableWidth, float availableHeight, int horizontalAlign, String elipsis) {
        clear();
        final char[] chars = text.text();

        int line = 0;
        int lineLaidRuns = 0;
        float startX = 0f;
        float lineStartY = 0f;

        Pool<GlyphRun<BitmapFont>> glyphRunPool = GlyphRun.pool();
        LayoutTextIterator<BitmapFont> textIterator = BitmapGlyphLayout._textIterator;
        textIterator.start(text);

        Array<BitmapFont> fonts = _fonts;
        int lineFontsFrom = 0;

        int lastCodepoint = -1; // For kerning

        int flags;
        while ((flags = textIterator.next()) != 0) {
            final int runStart = textIterator.currentStartIndex;
            final int runEnd = textIterator.currentEndIndex;

            final BitmapFont font = textIterator.currentFont;
            final boolean ltr = textIterator.currentLtr;

            //noinspection unchecked
            final GlyphRun<BitmapFont> run = glyphRunPool.obtain();
            run.ensureCapacity(runEnd - runStart);
            run.x = startX; // Y set later
            run.line = line;
            run.font = font;
            run.color = textIterator.currentColor;

            if ((flags & LayoutTextIterator.FLAG_FONT_CHANGE) != 0) {
                lastCodepoint = -1;// Do not kern between fonts
            }

            final Array<Glyph> glyphs = run.glyphs;
            final FloatArray glyphX = run.glyphX;
            final FloatArray glyphY = run.glyphY;

            boolean lastOnLine = (flags & (LayoutTextIterator.FLAG_LINE_BREAK | LayoutTextIterator.FLAG_LAST_REGION)) != 0;

            int penX = 0;
            for (int i = ltr ? runStart : runEnd-1; ltr ? i < runEnd : i >= runStart; i += ltr ? 1 : -1) {
                final int codepoint;
                {
                    final char c = chars[i];
                    if (Character.isSurrogate(c)){
                        if (ltr && Character.isHighSurrogate(c) && i + 1 < runEnd && Character.isLowSurrogate(chars[i+1])) {
                            // LTR: Valid surrogate pair
                            i++;
                            codepoint = Character.toCodePoint(c, chars[i]);
                        } else if (!ltr && Character.isLowSurrogate(c) && i - 1 >= runStart && Character.isHighSurrogate(chars[i-1])) {
                            // RTL: Valid surrogate pair
                            i--;
                            codepoint = Character.toCodePoint(c, chars[i]);
                        } else {
                            // Either unexpected low surrogate or incomplete high surrogate, so this is a broken character
                            codepoint = '\uFFFD'; // https://en.wikipedia.org/wiki/Specials_(Unicode_block)#Replacement_character
                        }
                    } else {
                        codepoint = c;
                    }
                }

                // TODO: Note codepoint position

                if (codepoint == '\n' || codepoint == '\t') {
                    assert i + 1 == runEnd;
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
            this.width = Math.max(this.width, startX);

            //TODO Line wrapping, \n and \t handling

            if (run.glyphs.size > 0) {
                run.width = penX;
                this.runs.add(run);

                final int fontIndex = fonts.indexOf(font, true);
                if (fontIndex < 0) {
                    fonts.add(font);
                } else if (fontIndex < lineFontsFrom) {
                    lineFontsFrom--;
                    fonts.swap(fontIndex, lineFontsFrom);
                }// else: already good
            } else {
                glyphRunPool.free(run);
            }

            if (lastOnLine) {
                // Lay out runs on line, vertically
                lastCodepoint = -1;

                // Go through fonts on line and check their max extend from top to baseline and from baseline down
                float topToBaseline = 0f;
                float baselineToDown = 0f;
                if (lineFontsFrom == fonts.size) {
                    // No fonts on line, use only current run font
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
                final float finalLineHeight = topToBaseline + baselineToDown;
                for (int i = lineLaidRuns; i < runs.size; i++) {
                    final GlyphRun<BitmapFont> r = runs.items[i];
                    assert r.line == line;
                    r.y = lineStartY - topToBaseline + r.font.base;
                }
                lineStartY -= finalLineHeight;
                height= -lineStartY;

                lineLaidRuns = runs.size;
                line++;
                startX = 0f;
            }
        }

        for (BitmapFont font : fonts) {
            font.prepareGlyphs();
        }
        fonts.clear();

        textIterator.end();
    }
}
