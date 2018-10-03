package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.StringBuilder;

import java.util.Arrays;
import java.util.Locale;

/**
 * Represents a text to be laid out by {@link GlyphLayout}.
 * Contains characters of the text, base font and color of the text,
 * and zero or more regions (defined by their start position), that may override base font or color.
 *
 * Must be initialized with {@link #init(Font, float)} before first use.
 *
 * <h4>Tab stops</h4>
 * <p>Specified text may contain tab character (<code>\t</code>). These generally work like left-stops in any text editor.
 * When tab character is encountered, following text shifts to the closest tab stop at greater X position.
 * If there is no next stop defined, tab is ignored.
 * <p>By default, there is endless amount of stops 8-spaces apart.</p>
 *
 * <h4>Example</h4>
 * <blockquote><pre>{@code
 *     LayoutText<BitmapFont> text = new LayoutText<>();
 *     text.init(myFont, Color.BLACK.toFloatBits());
 *     text.setText("My text");
 *     text.addRegion(3, myFont, Color.RED.toFloatBits());
 * }</pre></blockquote>
 *
 * <p><i>Note</i>: This implements CharSequence, but only in terms of actual characters.
 * No extra data take part in its implementation.</p>
 *
 * @see com.badlogic.gdx.graphics.text.util.MarkupLayoutText
 */
public class LayoutText<F extends Font<F>> implements CharSequence, Pool.Poolable {

    public static final char[] NO_TEXT = new char[0];

    char[] text = NO_TEXT;
    int length = 0;

    F initialFont;
    float initialColor;
    boolean leftToRight = true;
    private Locale locale = null;

    /** Indices of region starts. Region ends with end of text or start of next region.
     * There are never 0-length regions. */
    final IntArray regionStarts = new IntArray();
    /** Fonts corresponding to the regions */
    final Array<F> regionFonts = new Array<>(Font.class);
    /** Colors corresponding to the regions */
    final FloatArray regionColors = new FloatArray();

    /**
     * Contains unit-based positions of <a href="https://en.wikipedia.org/wiki/Tab_stop">(left) tab stops</a>.
     * May be null, in which case, default spacing is used.
     */
    private float[] tabStopPositions = null;

    /** Reset the text to be empty, clear regions, set other values to defaults and set initial font and color.
     * @param font not null */
    public void init(F font, float color) {
        if (font == null) throw new NullPointerException("font");
        reset();
        this.initialFont = font;
        this.initialColor = color;
    }

    /** Set the characters of the text.
     * @param chars not null (may be null only if length is 0)
     * @param length of chars to use, in range [0, chars.length] */
    public final void setText(char[] chars, int length) {
        if (length == 0) {
            this.text = NO_TEXT;
            this.length = 0;
            return;
        }
        if (chars == null) throw new NullPointerException("chars");
        if (length < 0 || length > chars.length) throw new IllegalArgumentException("length = "+length+", must be in [0,"+chars.length+']');
        this.text = chars;
        this.length = length;
    }

    /** Set the text to whatever are the current characters of the string builder.
     * State of characters is undefined after any modifications are done to the string builder.
     * @param stringBuilder not null */
    public final void setText(StringBuilder stringBuilder) {
        setText(stringBuilder.chars, stringBuilder.length);
    }

    /** Set the text to the content of given string.
     * <b>NOTE: This method causes an allocation. If you plan to modify the text often, use one of the other overloads.</b>
     * @param string may be null for empty test */
    public final void setText(String string) {
        final int length = string == null ? 0 : string.length();
        this.text = length == 0 ? NO_TEXT : string.toCharArray();
        this.length = length;
    }

    /**
     * Adds a region into the text, which spans from the given start to the end of the text, or the start of another region.
     * Regions can be added in arbitrary order, but the code is optimized for adding in order, from smallest start indexes.
     * Adding region with same start index twice will overwrite previously added region.
     *
     * @param start of the region (first affected char will be <code>text[start]</code>), must be >= 0
     * @param font to use in this region, not null
     * @param color of the region (see {@link Color#toFloatBits()})
     */
    public final void addRegion(int start, F font, float color) {
        if (font == null) throw new NullPointerException("font");

        if (start < 0) {
            // Clamp
            start = 0;
        }

        final IntArray regionStarts = this.regionStarts;
        final Array<F> regionFonts = this.regionFonts;
        final FloatArray regionColors = this.regionColors;

        int regionCount = regionStarts.size;
        // When appending, don't do any searching and append directly
        // It is expected that most invocations will use this.
        if (regionCount <= 0 || start > regionStarts.items[regionCount-1]) {
            regionStarts.add(start);
            regionFonts.add(font);
            regionColors.add(color);
            return;
        }

        // Search where it should be inserted
        int index = Arrays.binarySearch(regionStarts.items, 0, regionCount, start);
        if (index >= 0) {
            // Values should override previous values
            regionStarts.items[index] = start;
            regionFonts.set(index, font);
            regionColors.set(index, color);
        } else {
            index = -index - 1;
            // Values are inserted
            regionStarts.insert(index, start);
            regionFonts.insert(index, font);
            regionColors.insert(index, color);
        }
    }

    /**
     * Removes all previously added regions.
     */
    public final void removeAllRegions() {
        regionStarts.clear();
        regionFonts.clear();
        regionColors.clear();
    }

    /** @return characters of the text, not null
     * @see #length() for the valid range */
    public final char[] text() {
        return this.text;
    }

    @Override
    public char charAt(int index) {
        if (index < 0 || index > length) {
            throw new IndexOutOfBoundsException(index+" not in [0, "+length+")");
        }
        return text[index];
    }

    @Override
    public String subSequence(int start, int end) {
        if (start < 0 || start > end || end > length) {
            throw new IndexOutOfBoundsException("["+start+", "+end+") not in [0, "+length+")");
        }
        return new String(text, start, end - start);
    }

    /** @return number of valid indices from the start of {@link #text()}, i.e. length of the text. */
    public final int length() {
        return this.length;
    }

    /** @param index into the text, may be out of bounds
     * @return color to be used at given character index */
    public final float colorAt(int index) {
        final IntArray regionStarts = this.regionStarts;
        final int regionCount = regionStarts.size;
        final int[] regionStartsItems = regionStarts.items;

        if (index < 0 || regionCount <= 0 || index < regionStartsItems[0]) {
            return initialColor;
        } else {
            int regionIndex = Arrays.binarySearch(regionStartsItems, 0, regionCount, index);
            if (regionIndex < 0) {
                // Index is not directly at the start of some region...
                int insertionPoint = -regionIndex - 1;
                regionIndex = insertionPoint - 1;
            }
            return regionColors.items[regionIndex];
        }
    }

    /** @param index into the text, may be out of bounds
     * @return font to be used at given character index */
    public final F fontAt(int index) {
        final IntArray regionStarts = this.regionStarts;
        final int regionCount = regionStarts.size;
        final int[] regionStartsItems = regionStarts.items;

        if (index < 0 || regionCount <= 0 || index < regionStartsItems[0]) {
            return initialFont;
        } else {
            int regionIndex = Arrays.binarySearch(regionStartsItems, 0, regionCount, index);
            if (regionIndex < 0) {
                // Index is not directly at the start of some region...
                int insertionPoint = -regionIndex - 1;
                regionIndex = insertionPoint - 1;
            }
            return regionFonts.items[regionIndex];
        }
    }

    /** Set the positions of tab stops. Must be sorted from leftmost to rightmost. Not copied, only reference held.
     * Note that these positions give meaningful results only in left-aligned text (even for RTL scripts).
     * @param tabStopPositions may be null */
    public void setTabStopPositions(float[] tabStopPositions) {
        this.tabStopPositions = tabStopPositions;
    }

    /** Find index of tab stop which belongs to the text found at given x.
     * Returns valid index for {@link #tabStopOffsetFor(int, float)}, or -1 when there are no more tab stops on this line.
     * In that case, it can either overflow to next line and tab stop of 0 should be used (complex behavior),
     * or it can ignore the tab stop completely.
     * @param defaultTabAdvance see {@link #tabStopOffsetFor(int, float)} */
    public int tabStopIndexFor(float x, float defaultTabAdvance) {
        final float[] tabPoints = this.tabStopPositions;
        if (tabPoints == null) {
            final int index = (int) Math.floor(x / defaultTabAdvance) + 1;
            if (index < 0) {
                return 0;
            } else {
                return index;
            }
        }

        int index = Arrays.binarySearch(tabPoints, x);
        if (index < 0) {
            index = -index - 1;
        } else {
            index += 1;
        }
        if (index >= tabPoints.length) {
            return -1;
        } else {
            return index;
        }
    }

    /** Find X offset, from the start of the line, for the index of the tab stop, as returned by
     * {@link #tabStopIndexFor(float, float)}.
     * @param defaultTabAdvance to use when there are no tab stop points defined.
     *                          Usually derived as 8×space width of initial font. */
    public float tabStopOffsetFor(int index, float defaultTabAdvance) {
        final float[] tabPoints = this.tabStopPositions;
        if (tabPoints == null) {
            return index * defaultTabAdvance;
        }

        if (index < 0) {
            index = 0;
        }
        if (index >= tabPoints.length) {
            if (tabPoints.length == 0) {
                return 0f;
            }
            index = tabPoints.length - 1;
        }
        return tabPoints[index];
    }

    /** Initial font used for text that is not covered by any region.
     * Initial font and color applies to the ellipsis, if the laid out text has any, so it is valid to cover
     * the entire text by region with actual font+color and use initial font+color only for ellipsis. */
    public final F getInitialFont() {
        return initialFont;
    }

    /** Initial color used for text that is not covered by any region.
     * @see #getInitialFont() */
    public final float getInitialColor() {
        return initialColor;
    }

    /** @see #isLeftToRight() */
    public void setLeftToRight(boolean leftToRight) {
        this.leftToRight = leftToRight;
    }

    /** true if bidi paragraph direction is left to right, false if right to left.
     * This only has effect on mixed directionality text.
     * <i>Default: true</i> */
    public final boolean isLeftToRight() {
        return leftToRight;
    }

    /** @see #getLocale() */
    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    /** Locale used for locale specific things when laying out the text,
     * such as line breaking. If no locale specific behavior is needed, set to null to use english-like optimized defaults.
     * <i>Default: null</i> */
    public final Locale getLocale() {
        return locale;
    }

    @Override
    public void reset() {
        text = NO_TEXT;
        length = 0;
        initialFont = null;
        initialColor = 0f;

        regionStarts.clear();
        regionFonts.clear();
        regionColors.clear();

        tabStopPositions = null;
        leftToRight = true;
        locale = null;
    }

    final int regionAt(int index) {
        int i = Arrays.binarySearch(regionStarts.items, 0, regionStarts.size, index);
        if (i >= 0) {
            return i;
        }
        return -i - 2;
    }

    /** @return characters as string
     * @see #text() */
    @Override
    public String toString() {
        return new String(this.text, 0, this.length);
    }
}
