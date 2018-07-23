package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;

import java.util.Arrays;
import java.util.Locale;

/**
 * Represents a text to be laid out by {@link GlyphLayout}.
 * Contains characters of the text, base font and color of the text,
 * and zero or more regions (defined by their start position), that may override base font or color.
 *
 * Must be initialized with {@link #init} before first use.
 *
 * <h4>Tab stops</h4>
 * <p>Specified text may contain tab character (<code>\t</code>). These generally work like left-stops in any text editor.
 * When tab character is encountered, following text shifts to the closest tab stop at greater X position.
 * If there is no next stop defined, tab is ignored.
 * <p>By default, there is endless amount of stops 8-spaces apart.</p>
 */
public final class LayoutText<Font extends com.badlogic.gdx.graphics.text.Font> {

    char[] text;
    int length;

    Font initialFont;
    float initialColor;
    boolean leftToRight;
    private Locale locale;

    /** Indices of region starts. Region ends with end of text or start of next region.
     * There are never 0-length regions. */
    final IntArray regionStarts = new IntArray();
    /** Fonts corresponding to the regions */
    final Array<Font> regionFonts = new Array<>(com.badlogic.gdx.graphics.text.Font.class);
    /** Colors corresponding to the regions */
    final FloatArray regionColors = new FloatArray();

    /**
     * Contains unit-based positions of <a href="https://en.wikipedia.org/wiki/Tab_stop">(left) tab stops</a>.
     * May be null, in which case, default spacing is used.
     */
    private float[] tabStopPositions = null;

    /**
     * Initialize base attributes.
     *
     * @param text characters that will be part of the layout. Not copied, only reference held.
     * @param length of text to use
     * @param font initial font to use on the text
     * @param color initial color to use on the text (from {@link Color#toFloatBits()})
     * @param tabStopPositions may be null, contains unit-based positions of tab stops. Must be sorted from leftmost to rightmost.
     *                         Not copied, only reference held. Note that these positions give meaningful results only in left-aligned text (even for RTL scripts).
     * @param leftToRight if true, text is considered left-to-right. If false, right-to-left.
     * @param locale to use when manipulating this text. Used, for example, when determining line-breaks.
     *               Use null to fall back to optimized algorithms for latin-like text.
     * @throws NullPointerException text or font is null, or when tabStopPositions is null and tabTypes isn't or vice-versa
     * @throws IllegalArgumentException when tabStopPositions has different size than tabTypes
     */
    public void init(char[] text, int length, Font font, float color, float[] tabStopPositions, boolean leftToRight, Locale locale) {
        if (text == null) throw new NullPointerException("text");
        if (font == null) throw new NullPointerException("font");
        this.text = text;
        this.length = MathUtils.clamp(length, 0, text.length);
        this.initialFont = font;
        this.initialColor = color;
        this.tabStopPositions = tabStopPositions;
        this.leftToRight = leftToRight;
        this.locale = locale;

        regionStarts.clear();
        regionFonts.clear();
        regionColors.clear();
    }

    /**
     * Adds a region into the text, which spans from the given start to the end of the text, or the start of another region.
     * Must be {@link #init}'d first.
     *
     * @param start of the region (first char will be <code>text[textStart + start]</code>)
     * @param font to use in this region, not null
     * @param color of the region (see {@link Color#toFloatBits()})
     */
    public void addRegion(int start, Font font, float color) {
        if (this.text == null) throw new IllegalStateException("Not initialized.");
        if (font == null) throw new NullPointerException("font");

        if (start < 0) {
            // Clamp
            start = 0;
        } else if (start >= length) {
            // This will never apply
            return;
        }

        final IntArray regionStarts = this.regionStarts;
        final Array<Font> regionFonts = this.regionFonts;
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

    public char[] text() {
        return this.text;
    }

    public int length() {
        return this.length;
    }

    public float colorAt(int index) {
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

    public Font fontAt(int index) {
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

    /**
     * Find index of tab stop which belongs to the text found at given x.
     * Returns valid index for {@link #tabStopOffsetFor}, or -1 when there are no more tab stops on this line.
     * In that case, it can either overflow to next line and tab stop of 0 should be used (complex behavior),
     * or it can ignore the tab stop completely.
     */
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

    int regionAt(int index) {
        int i = Arrays.binarySearch(regionStarts.items, 0, regionStarts.size, index);
        if (i >= 0) {
            return i;
        }
        return -i - 2;
    }

    public boolean isLeftToRight() {
        return leftToRight;
    }

    public Font initialFont() {
        return initialFont;
    }

    public float initialColor() {
        return initialColor;
    }

    public Locale locale() {
        return locale;
    }

    /**
     * Clear all properties, including text, base properties and all regions.
     * Does not need to be called before {@link #init}, call only to prevent memory leaks.
     */
    public void reset() {
        text = null;
        initialFont = null;
        regionStarts.clear();
        regionFonts.clear();
        regionColors.clear();
        tabStopPositions = null;
        leftToRight = true;
        locale = null;
    }

}
