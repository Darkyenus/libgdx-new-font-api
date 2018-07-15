package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.FlushablePool;
import com.badlogic.gdx.utils.IntArray;

import java.util.Arrays;

/** Class responsible for laying out the glyphs constructed from fonts of this font system.
 * Also stores the laid out glyphs. */
public abstract class GlyphLayout<Font extends com.badlogic.gdx.graphics.text.Font> {

    /** Runs of the layout. Ordered by lines and then by X coordinate (not by char positions).
     * Some runs may not contain any glyphs and serve just to specify which (non-rendered) glyphs are on which line. */
    protected final Array<GlyphRun<Font>> runs = new Array<>(GlyphRun.class);
    /** Values exposed by {@link #width()} and {@link #height()}. */
    protected float width, height;
    /** Contains run character start index (msb 17bits, msb bit 0) with index of run which contains it (lsb 15bits).
     * Ordered for quick binary search of character->run mapping. */
    private final IntArray charRuns = new IntArray();
    /** For each line in the layout (even if it has no GlyphRun), contains its height + <b>height of all previous lines</b>.
     * Size determines the amount of lines. Must have at least one entry. */
    protected final FloatArray lineHeights = new FloatArray();

    /** Sets this text layout to contain specified text, laid out in a virtual rectangle
     * of availableWidth x infinite height. This overwrites any previously added text.
     *
     * Then, the text is aligned according to horizontalAlign in that rectangle.
     * (If availableWidth is infinite, alignment is computed with respect to the maximal width of all laid out lines.)
     *
     * Should set values of {@link #runs}, {@link #width}, {@link #height} and {@link #lineHeights}.
     * When layout is done, it should also call {@link #buildCharPositions()}.
     *
     * @param text to lay out (may be modified after this call is done, it will not reflect in the layout)
     * @param availableWidth to which the text must fit. Values <= 0 are same as {@link Float#POSITIVE_INFINITY}
     * @param availableHeight to which the text must fit to not be truncated with elipsis.
     *                        Represents units when positive, negative amount of lines when negative (rounded to integer),
     *                        or no limit when 0. Note that at least one line will be always rendered.
     * @param horizontalAlign one of horizontal alignments from {@link com.badlogic.gdx.utils.Align}
     * @param elipsis to use when the text is too long and doesn't fit available space. Will be rendered using
     *                the {@link LayoutText#initialFont} and {@link LayoutText#initialColor} of the text.
     *                May be null, in which case default value is used, if needed. */
    public abstract void layoutText(LayoutText<Font> text, float availableWidth, float availableHeight, int horizontalAlign, String elipsis);

    protected final void buildCharPositions() {
        final IntArray charRuns = this.charRuns;

        final GlyphRun<Font>[] glyphRuns = this.runs.items;
        final int runCount = this.runs.size;
        assert (runCount & ~0x7FFF) == 0;

        for (int i = 0; i < runCount; i++) {
            final GlyphRun<Font> run = glyphRuns[i];
            assert (run.charactersStart & ~0xFFFF) == 0;
            charRuns.add(run.charactersStart << 15 | i);
        }

        charRuns.sort();

        //TODO DEBUG Check assertions
        int lastCharStart = -1;
        for (int i = 0; i < charRuns.size; i++) {
            final int item = charRuns.items[i];
            final int characterStart = item >> 15;
            final int runIndex = item & 0x7FFF;
            assert characterStart > lastCharStart;
            lastCharStart = characterStart;

            assert runs.items[runIndex].charactersStart == characterStart;
        }
    }

    /**
     * @param characterIndex index of character whose GlyphRun is searched for
     * @param clamp if true and characterIndex is out of bounds, return closest valid index
     *              (may still return invalid index if there are no runs)
     * @return index into the {@link #runs} array of the run which contains character at given index
     */
    protected final int indexOfRunOf(int characterIndex, boolean clamp) {
        final int[] charRunItems = charRuns.items;
        final Array<GlyphRun<Font>> runs = this.runs;

        if ((characterIndex & ~0xFFFF) != 0 || runs.size == 0) {
            // characterIndex is out of supported bounds or there are no runs
            if (clamp && runs.size > 0) {
                if (characterIndex < 0) {
                    return charRunItems[0] & 0x7FFF;
                } else {
                    return charRunItems[runs.size - 1] & 0x7FFF;
                }
            }
            return -1;
        }

        final int key = characterIndex << 15 | 0x7FFF;
        final int i = Arrays.binarySearch(charRunItems, 0, charRuns.size, key);
        final int runIndex;
        if (i >= 0) {
            // Found key, which assumes 0x7FFF index, so the run index must be 0x7FFF
            runIndex = 0x7FFF;
        } else {
            final int baseIndex = -i - 2;
            if (baseIndex < 0) {
                // This index is before first run
                return clamp ? charRunItems[0] & 0x7FFF : -1;
            }
            runIndex = charRunItems[baseIndex] & 0x7FFF;
        }
        final GlyphRun<Font> run = runs.items[runIndex];
        assert run.charactersStart <= characterIndex;
        if (run.charactersEnd > characterIndex) {
            return runIndex;
        }
        return clamp ? charRunItems[runs.size - 1] & 0x7FFF : -1;
    }

    /** @return total width of the currently laid out text */
    public final float width() {
        return width;
    }

    /** @return total height of the currently laid out text */
    public final float height() {
        return height;
    }

    private static final FlushablePool<Rectangle> SELECTION_POOL = new FlushablePool<Rectangle>(3, 16){
        @Override
        protected Rectangle newObject() {
            return new Rectangle();
        }
    };

    /** @see #getCaretPosition(int) */
    private Rectangle getCaretPosition(int index, Rectangle caret) {
        final Array<GlyphRun<Font>> runs = this.runs;
        if (runs.size == 0) {
            //TODO Consider align? width is probably 0...
            return caret.set(0f, -lineHeights.items[0], 0f, lineHeights.items[0]);
        }
        final int runIndex = indexOfRunOf(index, true);
        final GlyphRun<Font> run = runs.items[runIndex];
        final int line = run.line;
        final float y = -lineHeights.items[line];
        final float height = -y - (line == 0 ? 0 : lineHeights.items[line-1]);

        if (index < run.charactersStart) {
            if (run.charactersLtr) {
                return caret.set(run.x, y, 0f, height);
            } else {
                return caret.set(run.x + run.width, y, 0f, height);
            }
        } else if (index >= run.charactersEnd) {
            // Assuming that this is the last run, because we wouldn't be at the end if it wasn't
            if (run.charactersLinebreak) {
                assert line + 1 <= lineHeights.size;
                final float nY = -lineHeights.items[line+1];
                final float nHeight = -nY - lineHeights.items[line];
                //TODO Consider align
                return caret.set(0f, nY, 0f, nHeight);
            }

            if (run.charactersLtr) {
                return caret.set(run.x + run.width, y, 0f, height);
            } else {
                return caret.set(run.x, y, 0f, height);
            }
        }

        final float[] characterPositions = run.characterPositions.items;
        int posIndex = index - run.charactersStart;
        float characterX;
        while (Float.isNaN(characterX = characterPositions[posIndex])) {
            assert posIndex > 0;
            posIndex--;
        }

        return caret.set(run.x + characterX, y, 0f, height);
    }

    /** Obtain a rectangle, which specifies position and height of caret positioned at given index.
     * @param index into the laid out text
     * @return rectangle that is valid only until next invocation of any {@link GlyphLayout} methods (on any instance)
     * and specifies position of the bottom point of the caret in coordinates relative to the layout origin.
     * Height of the rectangle is the height of the caret. Width is undefined.
     */
    public final Rectangle getCaretPosition(int index) {
        SELECTION_POOL.flush();
        final Rectangle caret = SELECTION_POOL.obtain();
        return getCaretPosition(index, caret);
    }

    public final Array<Rectangle> getSelectionRectangles(int startIndex, int endIndex) {
        return null;//TODO
    }

    /**
     * Returns range of <code>chars</code> that should be deleted from given codepoint.
     * @param index of the caret
     * @param forward true = DELETE, false = BACKSPACE
     */
    public final void getDeletionRange(int index, boolean forward) {
        //TODO This maybe should get implemented elsewhere? But we already have to deal with it, so we may as well expose it...
        //TODO implement and figure out return type
    }

    /**
     * Return index of character whose glyph is at given coordinate.
     * If there is no such glyph, returns -1, unless clamp is true, in which case index of closest glyph is returned.
     * When position is after last glyph, may return index equal to text length.
     * @return index into the laid out text or -1 when no such glyph and clamp is false or when there is no text laid out
     */
    public final int getIndexAt(float x, float y, boolean clamp) {
        final Array<GlyphRun<Font>> runs = this.runs;
        if (runs.size == 0) {
            return -1;
        }

        y = -y;// y is up, but lines grow down

        // Find line
        if (y < 0f) {
            if (clamp) {
                y = 0f;
            } else {
                return -1;
            }
        }
        final FloatArray lineHeights = this.lineHeights;
        int line = Arrays.binarySearch(lineHeights.items, 0, lineHeights.size, y);
        if (line < 0) {
            line = -line - 1;
        }
        if (line >= lineHeights.size) {
            if (clamp) {
                line = lineHeights.size - 1;
            } else {
                return -1;
            }
        }

        final GlyphRun<Font>[] glyphRuns = runs.items;
        GlyphRun<Font> lastRun = runs.items[0];
        for (int i = 0; i < runs.size; i++) {
            final GlyphRun<Font> run = glyphRuns[i];
            if (run.line > line) {
                break;
            }
            if (run.line == line && run.x > x) {
                if (lastRun.line < line) {
                    lastRun = run;
                }
                break;
            }
            lastRun = run;
        }


        x -= lastRun.x;
        if (!clamp) {
            if (x < 0f || x > lastRun.width) {
                return -1;
            }
        }

        final float[] characterPositions = lastRun.characterPositions.items;
        final int characterPositionCount = lastRun.characterPositions.size;
        int leftIndex = 0;
        float leftX = 0f;
        int rightIndex = characterPositionCount;
        float rightX = lastRun.width;
        if (lastRun.charactersLtr) {
            for (int i = 0; i < characterPositionCount; i++) {
                final float pos = characterPositions[i];
                if (Float.isNaN(pos)) {
                    continue;
                }
                if (pos > x) {
                    rightIndex = i;
                    rightX = pos;
                    break;
                }
                leftIndex = i;
                leftX = pos;
            }
        } else {
            for (int i = characterPositionCount-1; i >= 0; i--) {
                final float pos = characterPositions[i];
                if (Float.isNaN(pos)) {
                    continue;
                }
                if (pos >= x) {
                    rightIndex = i;
                    rightX = pos;
                    break;
                }
                leftIndex = i;
                leftX = pos;
            }
        }

        int index = lastRun.charactersStart + (x - leftX < rightX - x ? leftIndex : rightIndex);
        if (index == lastRun.charactersEnd && lastRun.charactersLinebreak
                && lastRun.charactersEnd - 1 >= lastRun.charactersStart) {
            // Return position before the linebreak
            index--;
        }
        return index;
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
        charRuns.clear();
        lineHeights.clear();
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
