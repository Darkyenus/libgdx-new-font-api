package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;

/**
 * Class responsible for laying out the glyphs constructed from fonts of this font system.
 * Also stores the laid out glyphs.
 */
public abstract class GlyphLayout<Self extends GlyphLayout<Self, Font>, Font extends com.badlogic.gdx.graphics.text.Font<Self>> {

    /** Values set by */
    protected final Array<GlyphRun<Font>> runs = new Array<>(GlyphRun.class);
    protected float width, height;

    /**
     * Sets this text layout to contain specified text, laid out in a virtual rectangle
     * of availableWidth x infinite height. This overwrites any previously added text.
     *
     * Then, the text is aligned according to horizontalAlign in that rectangle.
     * (If availableWidth is infinite, alignment is computed with respect to the maximal width of all laid out lines.)
     *
     * Should set values of {@link #runs}, {@link #width} and {@link #height}.
     *
     * @param text to lay out (may be modified after this call is done, it will not reflect in the layout)
     * @param availableWidth to which the text must fit. Values <= 0 are same as {@link Float#POSITIVE_INFINITY}
     * @param availableHeight to which the text must fit to not be truncated with elipsis.
     *                        Represents units when positive, negative amount of lines when negative (rounded to integer),
     *                        or no limit when 0. Note that at least one line will be always rendered.
     * @param horizontalAlign one of horizontal alignments from {@link com.badlogic.gdx.utils.Align}
     * @param elipsis to use when the text is too long and doesn't fit available space. Will be rendered using
     *                the {@link LayoutText#initialFont} and {@link LayoutText#initialColor} of the text.
     *                May be null, in which case default value is used, if needed.
     */
    public abstract void layoutText(LayoutText<Font> text, float availableWidth, float availableHeight, int horizontalAlign, String elipsis);

    /**
     * @return total width of the currently laid out text
     */
    public final float width() {
        return width;
    }

    /**
     * @return total height of the currently laid out text
     */
    public final float height() {
        return height;
    }


    //TODO Methods for inspection of laid out text, i.e. positions of caret, etc.

    /**
     * Render the laid out text to the given cache.
     * What is currently in the cache will be kept.
     *
     * @param to not null
     * @param x of the upper left corner at which text should be rendered to
     * @param y of the upper left corner at which text should be rendered to
     */
    public void render(FontRenderCache to, float x, float y) {
        Font lastFont = null;
        FloatArray[] pageVertices = null;

        for (GlyphRun<Font> run : runs) {
            final Font font = run.font;
            assert font != null;
            if (font != lastFont) {
                lastFont = font;
                pageVertices = to.preparePageMappingForFont(font);
            }

            final int glyphAmount = run.glyphs.size;
            final Glyph[] glyphs = run.glyphs.items;
            final float[] glyphX = run.glyphX.items;
            final float[] glyphY = run.glyphY.items;

            final float baseX = x + run.x;
            final float baseY = y + run.y;

            for (int i = 0; i < glyphAmount; i++) {
                final Glyph glyph = glyphs[i];
                final int page = glyph.page;
                if (page == -1) {
                    continue;
                }

                final FloatArray vertexArray = pageVertices[page];

                final float[] vertices = vertexArray.ensureCapacity(20);
                int idx = vertexArray.size;
                vertexArray.size += 20;

                final float gX = baseX + glyphX[i] + glyph.xOffset;
                final float gY = baseY + glyphY[i] + glyph.yOffset;
                final float gX2 = gX + glyph.width;
                final float gY2 = gY + glyph.height;

                final float u = glyph.u, u2 = glyph.u2, v = glyph.v, v2 = glyph.v2;
                final float color = run.color;

                vertices[idx++] = gX;
                vertices[idx++] = gY;
                vertices[idx++] = color;
                vertices[idx++] = u;
                vertices[idx++] = v;

                vertices[idx++] = gX;
                vertices[idx++] = gY2;
                vertices[idx++] = color;
                vertices[idx++] = u;
                vertices[idx++] = v2;

                vertices[idx++] = gX2;
                vertices[idx++] = gY2;
                vertices[idx++] = color;
                vertices[idx++] = u2;
                vertices[idx++] = v2;

                vertices[idx++] = gX2;
                vertices[idx++] = gY;
                vertices[idx++] = color;
                vertices[idx++] = u2;
                vertices[idx] = v;
            }
        }

    }

    /**
     * Deletes any previously laid out text, so that the memory can be freed.
     */
    @SuppressWarnings("unchecked")
    public void clear() {
        width = 0f;
        height = 0f;

        GlyphRun.<Font>pool().freeAll(runs);
        runs.clear();
    }

    private static boolean isIgnorableCodepoint(int codepoint) {
        // https://www.unicode.org/reports/tr44/#Default_Ignorable_Code_Point
        /*
        Other_Default_Ignorable_Code_Point
        + Cf (format characters)
        + Variation_Selector
        - White_Space (NOTE: Here omitted as handled elsewhere)
        - FFF9..FFFB (annotation characters)
        - 0600..0605, 06DD, 070F, 08E2, 110BD (exceptional Cf characters that should be visible)
         */
        // Based on https://www.unicode.org/Public/11.0.0/ucd/PropList.txt without reserved entries
        /*
        034F          ; Other_Default_Ignorable_Code_Point # Mn       COMBINING GRAPHEME JOINER
        115F..1160    ; Other_Default_Ignorable_Code_Point # Lo   [2] HANGUL CHOSEONG FILLER..HANGUL JUNGSEONG FILLER
        17B4..17B5    ; Other_Default_Ignorable_Code_Point # Mn   [2] KHMER VOWEL INHERENT AQ..KHMER VOWEL INHERENT AA
        3164          ; Other_Default_Ignorable_Code_Point # Lo       HANGUL FILLER
        FFA0          ; Other_Default_Ignorable_Code_Point # Lo       HALFWIDTH HANGUL FILLER
        -- NOTE: Reserved entries omitted

        180B..180D    ; Variation_Selector # Mn   [3] MONGOLIAN FREE VARIATION SELECTOR ONE..MONGOLIAN FREE VARIATION SELECTOR THREE
        FE00..FE0F    ; Variation_Selector # Mn  [16] VARIATION SELECTOR-1..VARIATION SELECTOR-16
        E0100..E01EF  ; Variation_Selector # Mn [240] VARIATION SELECTOR-17..VARIATION SELECTOR-256
         */
        return (codepoint == 0x034F
                || (codepoint >= 0x115F && codepoint <= 0x1160)
                || (codepoint >= 0x17B4 && codepoint <= 0x17B5)
                || codepoint == 0x3164
                || codepoint == 0xFFA0
                || (codepoint >= 0x180B && codepoint <= 0x180D)
                || (codepoint >= 0xFE00 && codepoint <= 0xFE0F)
                || (codepoint >= 0xE0100 && codepoint <= 0xE01EF))
                || (Character.getType(codepoint) == Character.FORMAT)
                && !(
                    (codepoint >= 0xFFF9 && codepoint <= 0xFFFB)
                 || (codepoint >= 0x0600 && codepoint <= 0x0605)
                 || codepoint == 0x06DD
                 || codepoint == 0x070F
                 || codepoint == 0x08E2
                 || codepoint == 0x110BD
                );
    }

    /**
     * Call when glyph for particular unicode codepoint is missing, to determine how it should be handled.
     *
     * If returned value is -1, show .nodef (glyph 0).
     * If returned value is 0, ignore codepoint completely (as a zero-width character)
     * If returned value is positive, divide by eight and multiply by default space advance and use that as X advance
     */
    protected static byte missingGlyphHandling(int codepoint) {
        // https://www.unicode.org/faq/unsup_char.html
        if (Character.isWhitespace(codepoint)) {
            // Try to guess some character widths
            // Values from http://jkorpela.fi/chars/spaces.html
            switch (codepoint) {
                // Unit here is 1em = 32, 1en = 16
                case 0x0020: return 8;//SPACE
                case 0x00A0: return 8;//NO-BREAK SPACE
                case 0x2000: return 16;//EN QUAD
                case 0x2001: return 32;//EM QUAD
                case 0x2002: return 16;//EN SPACE
                case 0x2003: return 32;//EM SPACE
                case 0x2004: return 11;//THREE-PER-EM SPACE
                case 0x2005: return 8;//FOUR-PER-EM SPACE
                case 0x2006: return 5;//SIX-PER-EM SPACE
                case 0x2007: return 8;//FIGURE SPACE (arbitrary)
                case 0x2008: return 4;//PUNCTUATION SPACE (arbitrary)
                case 0x2009: return 6;//THIN SPACE
                case 0x200A: return 3;//HAIR SPACE
                case 0x202F: return 6;//NARROW NO-BREAK SPACE
                case 0x205F: return 7;//MEDIUM MATHEMATICAL SPACE
                case 0x3000: return 10;//IDEOGRAPHIC SPACE (arbitrary)
                default: return 8;
            }
        } else if (isIgnorableCodepoint(codepoint)) {
            return 0;
        } else {
            return -1;
        }
    }
}
