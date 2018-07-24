package com.badlogic.gdx.graphics.text.util;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.text.Font;
import com.badlogic.gdx.graphics.text.LayoutText;
import com.badlogic.gdx.utils.*;
import com.badlogic.gdx.utils.StringBuilder;

/**
 * {@link LayoutText} subclass, whose regions can be set through markup.
 *
 * <h2>Markup language</h2>
 * Consists of markup tags, which appear inside normal text. Markup tags consist of zero or more elements, separated by
 * commas and surrounded by square brackets. No whitespace in tags is allowed, not even in elements.
 * <br>
 * For example {@code []}, {@code [RED]} and {@code [#FF0534,italic]} are all valid tags.
 * <br>
 * Opening brace can be escaped, so that it is not considered a part of tag by another opening brace,
 * e.g. {@code [[RED]} is not a valid tag.
 *
 * Tags which are not complete (i.e. only opening {@code [}), contain whitespace, and/or invalid elements, are not considered
 * to be tags and are displayed like normal text.
 *
 * <h3>Elements</h3>
 * Possible elements (with examples) are:
 * <ul>
 *     <li>Color name - {@code RED}
 *          <p>Following characters should have color with this name. Available colors are defined by the {@link #style}.</p></li>
 *     <li>Color code - {@code #FF0000}
 *          <p>Following characters should have color specified by this hex code. Allowed patterns are: {@code #RRGGBBAA},
 *          {@code #RRGGBB}, {@code #RGBA} and {@code #RGB}. Patterns without alpha default to 0xFF.</p></li>
 *     <li>Font name - {@code italic}
 *          <p>Following characters should be set in the font with this name. Available fonts are defined by the {@link #style}.</p></li>
 *     <li>Alias name - {@code player_name}
 *          <p>This element should be evaluated as elements defined under this name in the {@link #style}.
 *          This allows to create symbolic names for font-color pairings, for example: "{@code player_name -> RED,italic}".
 *          Cyclic aliases WILL CRASH!</p></li>
 *     <li>Initial font and color - {@code -}
 *          <p>Single dash. Sets the font and color of following characters to initial values.</p></li>
 *     <li>Reset - {@code !}
 *          <p>Tag with this element erases history of previous color/font settings, so that <i>pop</i> will revert to initial color/font, not previous.
 *          It also resets the font and color to the initial values.</p></li>
 * </ul>
 *
 * When markup tag is encountered, all elements are evaluated in order.
 * If markup tag contains no elements, it is a "<i>pop</i>" tag - it reverts the font and color to the values
 * before last markup tag. If there is no last markup tag, initial color and font is used instead.
 * <br>
 * Note that "<i>pushes</i>" in a single tag are performed at once, as a single "<i>push</i>". So, for example,
 * tag {@code [RED,-,BLUE,ITALIC,BOLD]} is equal to and indistinguishable from {@code [BLUE, BOLD]}.
 * This does not apply to "<i>pop</i>" and reset though.
 *
 *
 * <h4>Example</h4>
 * <blockquote><pre>{@code
 *     MarkupLayoutText<BitmapFont> text = new MarkupLayoutText<>(myStyle);
 *     text.init(myFont, Color.BLACK.toFloatBits());
 *     text.setMarkupText("My [RED]text[] with [BOLD]important[] parts.");
 * }</pre></blockquote>
 */
public class MarkupLayoutText<F extends Font<F>> extends LayoutText<F> {

    public static final char OPEN_TAG = '[';
    public static final char CLOSE_TAG = ']';

    /** Queried for named elements. May be null.
     * Can be freely changed, but note that changes take place only after {@link #setMarkupText(CharSequence)}. */
    public MarkupStyle<F> style = null;

    private final StringBuilder textBuilder = new StringBuilder();

    /** {@link #style} is initialized to null, but can be changed later. */
    public MarkupLayoutText() {}

    /** @param style may be null */
    public MarkupLayoutText(MarkupStyle<F> style) {
        this.style = style;
    }

    private final FloatArray colorStack = new FloatArray();
    private final Array<F> fontStack = new Array<>(Font.class);

    private static int indexOf(CharSequence in, char of, int start, int end) {
        for (int i = start; i < end; i++) {
            if (in.charAt(i) == of) {
                return i;
            }
        }
        return end;
    }

    private int hexCharValue(char c){
        if (c >= '0' && c <= '9')
            return c - '0';
        else if (c >= 'a' && c <= 'f')
            return 10 + (c - 'a');
        else if (c >= 'A' && c <= 'F')
            return 10 + (c - 'A');
        else
            return -1; // Unexpected character in hex color.
    }

    private int hexByte(char c1, char c2){
        return hexCharValue(c1) << 4 | hexCharValue(c2);
    }

    private static final CharSequenceSlice completeTag_element = new CharSequenceSlice();

    private boolean tagStatePush;
    private F tagStateFont;
    private float tagStateColor;

    /** @return true = tag is valid, false = tag is invalid, show it as text instead */
    private boolean evaluateTag(CharSequence markupText, int elementsStart, int elementsEnd) {
        if (elementsStart >= elementsEnd) {
            // Pop tag
            if (colorStack.size > 0) {
                colorStack.size--;
                fontStack.size--;
                fontStack.items[fontStack.size] = null;
            }
            final int popIndex = colorStack.size - 1;
            tagStatePush = false;
            if (popIndex < 0) {
                tagStateFont = getInitialFont();
                tagStateColor = getInitialColor();
            } else {
                tagStateFont = fontStack.get(popIndex);
                tagStateColor = colorStack.items[popIndex];
            }
            return true;
        } else {
            final MarkupStyle<F> style = this.style;

            final CharSequenceSlice element = completeTag_element;
            int elementStart = elementsStart;
            int elementEnd;
            do {
                elementEnd = indexOf(markupText, ',', elementStart, elementsEnd);
                if (elementStart >= elementEnd) {
                    // Empty element
                    return false;
                }

                if (markupText.charAt(elementStart) == '#') {
                    // Color code
                    element.set(markupText, elementStart + 1, elementEnd);
                    int r, g, b, a = 0xFF;
                    switch (element.length()) {
                        case 4:
                            a = hexByte(element.charAt(3), element.charAt(3));
                        case 3:
                            r = hexByte(element.charAt(0), element.charAt(0));
                            g = hexByte(element.charAt(1), element.charAt(1));
                            b = hexByte(element.charAt(2), element.charAt(2));
                            break;
                        case 8:
                            a = hexByte(element.charAt(6), element.charAt(7));
                        case 6:
                            r = hexByte(element.charAt(0), element.charAt(1));
                            g = hexByte(element.charAt(2), element.charAt(3));
                            b = hexByte(element.charAt(4), element.charAt(5));
                            break;
                        default:
                            return false;
                    }
                    if (((r | g | b | a) & ~0xFF) != 0) {
                        // Hex digits out of bounds
                        return false;
                    }
                    tagStateColor = Color.toFloatBits(r, g, b, a);
                    tagStatePush = true;
                } else if (markupText.charAt(elementStart) == '!') {
                    fontStack.clear();
                    colorStack.clear();
                    tagStateColor = getInitialColor();
                    tagStateFont = getInitialFont();
                    tagStatePush = false;
                } else if (elementStart + 1 == elementEnd && markupText.charAt(elementStart) == '-') {
                    tagStateColor = getInitialColor();
                    tagStateFont = getInitialFont();
                    tagStatePush = true;
                } else if (style != null) {
                    @SuppressWarnings("unchecked")
                    final ObjectFloatMap<CharSequence> colorMap = (ObjectFloatMap<CharSequence>)(ObjectFloatMap)style.colorMap;
                    @SuppressWarnings("unchecked")
                    final ObjectMap<CharSequence, F> fontMap = (ObjectMap<CharSequence, F>)(ObjectMap)style.fontMap;
                    @SuppressWarnings("unchecked")
                    final ObjectMap<CharSequence, String> aliasMap = (ObjectMap<CharSequence, String>)(ObjectMap)style.aliasMap;

                    element.set(markupText, elementStart, elementEnd);

                    if (colorMap.containsKey(element)) {
                        tagStateColor = colorMap.get(element, 0f);
                        tagStatePush = true;
                    } else {
                        final F newFont = fontMap.get(element);
                        if (newFont != null) {
                            tagStateFont = newFont;
                            tagStatePush = true;
                        } else {
                            final String alias = aliasMap.get(element);
                            if (alias != null) {
                                // Handle the alias
                                evaluateTag(alias, 0, alias.length());
                            } else {
                                // No element matched
                                return false;
                            }
                        }
                    }
                } else {
                    // Nothing fits for this value
                    return false;
                }

                elementStart = elementEnd + 1;
            } while (elementEnd < elementsEnd);
            return true;
        }
    }

    private boolean completeTag(CharSequence markupText, int regionStart, int elementsStart, int elementsEnd) {
        if (evaluateTag(markupText, elementsStart, elementsEnd)) {
            if (tagStatePush) {
                colorStack.add(tagStateColor);
                fontStack.add(tagStateFont);
            }
            addRegion(regionStart, tagStateFont, tagStateColor);
            return true;
        }
        return false;
    }

    /**
     * Evaluate markup inside {@code markupText} and setup this {@link LayoutText} so that its text and regions
     * reflect the markup.
     * @param markupText not null
     */
    public void setMarkupText(CharSequence markupText) {
        removeAllRegions();
        colorStack.clear();
        fontStack.clear();

        final StringBuilder text = this.textBuilder;
        text.setLength(0);
        final int length = markupText.length();
        text.ensureCapacity(length);

        final byte STATE_TEXT = 0;
        final byte STATE_JUST_OPENED = 1;
        final byte STATE_ELEMENT = 2;

        int tagStart = -1;
        tagStateFont = getInitialFont();
        tagStateColor = getInitialColor();

        byte state = STATE_TEXT;
        for (int i = 0; i < length; i++) {
            final char c = markupText.charAt(i);
            boolean tagValid = true;
            switch (state) {
                case STATE_TEXT:
                    if (c == OPEN_TAG) {
                        state = STATE_JUST_OPENED;
                        tagStart = i;
                    } else {
                        text.append(c);
                    }
                    break;
                case STATE_JUST_OPENED:
                    if (c == OPEN_TAG) {
                        // Escape
                        state = STATE_TEXT;
                        text.append(OPEN_TAG).append(OPEN_TAG);
                    } else if (c == CLOSE_TAG) {
                        state = STATE_TEXT;
                        tagValid = completeTag(markupText, text.length, tagStart + 1, i);
                    } else {
                        state = STATE_ELEMENT;
                    }
                    break;
                case STATE_ELEMENT:
                    if (c == CLOSE_TAG) {
                        state = STATE_TEXT;
                        tagValid = completeTag(markupText, text.length, tagStart + 1, i);
                    } else if (Character.isWhitespace(c)) {
                        state = STATE_TEXT;
                        tagValid = false;
                    }// else Keep state
                    break;
            }

            if (i + 1 == length && state != STATE_TEXT) {
                state = STATE_TEXT;
                tagValid = false;
            }

            if (!tagValid) {
                for (int j = tagStart; j <= i; j++) {
                    text.append(markupText.charAt(j));
                }
            }
        }

        setText(text);
        colorStack.clear();
        fontStack.clear();
        completeTag_element.reset();
    }

    @Override
    public void reset() {
        super.reset();
        style = null;
    }

    /** Specifies name to color, font and alias mapping for {@link MarkupLayoutText}. */
    public static class MarkupStyle<F extends Font<F>> {

        public final ObjectFloatMap<String> colorMap = new ObjectFloatMap<>();
        public final ObjectMap<String, F> fontMap = new ObjectMap<>();
        public final ObjectMap<String, String> aliasMap = new ObjectMap<>();

        public MarkupStyle() {
            this(true, null, null, null);
        }

        /** Create style with pre-added fonts.
         * Parameters may be null, in which case they will not be added.
         * @param italic font added under names {@code I} and {@code ITALIC}
         * @param bold font added under names {@code B} and {@code BOLD}
         * @param underscored font added under names {@code U} and {@code UNDERSCORED}*/
        public MarkupStyle(boolean addDefaultColors, F italic, F bold, F underscored) {
            if (addDefaultColors) {
                addDefaultColors(this);
            }
            if (italic != null) {
                setFont("I", italic);
                setFont("ITALIC", italic);
            }
            if (bold != null) {
                setFont("B", bold);
                setFont("BOLD", bold);
            }
            if (underscored != null) {
                setFont("U", underscored);
                setFont("UNDERSCORED", underscored);
            }
        }

        /** Assign {@code name} to a {@code color}.
         * Convenience method for {@link #colorMap}{@code .put(name, color.toFloatBits())}.
         * @param name not null and not present in other maps
         * @param color not null */
        public void setColor(String name, Color color) {
            colorMap.put(name, color.toFloatBits());
        }

        /** Assign {@code name} to a {@code color}.
         * Convenience method for {@link #colorMap}{@code .put(name, color)}.
         * @param name not null and not present in other maps
         * @param color float bits {@link Color#toFloatBits()} */
        public void setColor(String name, float color) {
            colorMap.put(name, color);
        }

        /** Assign {@code name} to color specified by {@code r}, {@code g}, {@code b}, {@code a} parameters in [0f, 1f] range.
         * Convenience method for {@link #colorMap}{@code .put(name, Color.toFloatBits(r, g, b, a))}.
         * @param name not null and not present in other maps
         * @see Color#toFloatBits(float, float, float, float)  */
        public void setColor(String name, float r, float g, float b, float a) {
            colorMap.put(name, Color.toFloatBits(r, g, b, a));
        }

        /** Assign {@code name} to {@code font}.
         * Convenience method for {@link #fontMap}{@code .put(name, font)}.
         * @param name not null and not present in other maps
         * @param font not null */
        public void setFont(String name, F font) {
            fontMap.put(name, font);
        }

        /** Assign {@code name} to given alias.
         * Convenience method for {@link #aliasMap}{@code .put(name, aliasFor)}.
         * @param name not null and not present in other maps
         * @param aliasFor not null */
        public void setAlias(String name, String aliasFor) {
            aliasMap.put(name, aliasFor);
        }

        public static <F extends Font<F>> void addDefaultColors(MarkupStyle<F> to) {
            to.setColor("CLEAR", Color.CLEAR);
            to.setColor("BLACK", Color.BLACK);

            to.setColor("WHITE", Color.WHITE);
            to.setColor("LIGHT_GRAY", Color.LIGHT_GRAY);
            to.setColor("GRAY", Color.GRAY);
            to.setColor("DARK_GRAY", Color.DARK_GRAY);

            to.setColor("BLUE", Color.BLUE);
            to.setColor("NAVY", Color.NAVY);
            to.setColor("ROYAL", Color.ROYAL);
            to.setColor("SLATE", Color.SLATE);
            to.setColor("SKY", Color.SKY);
            to.setColor("CYAN", Color.CYAN);
            to.setColor("TEAL", Color.TEAL);

            to.setColor("GREEN", Color.GREEN);
            to.setColor("CHARTREUSE", Color.CHARTREUSE);
            to.setColor("LIME", Color.LIME);
            to.setColor("FOREST", Color.FOREST);
            to.setColor("OLIVE", Color.OLIVE);

            to.setColor("YELLOW", Color.YELLOW);
            to.setColor("GOLD", Color.GOLD);
            to.setColor("GOLDENROD", Color.GOLDENROD);
            to.setColor("ORANGE", Color.ORANGE);

            to.setColor("BROWN", Color.BROWN);
            to.setColor("TAN", Color.TAN);
            to.setColor("FIREBRICK", Color.FIREBRICK);

            to.setColor("RED", Color.RED);
            to.setColor("SCARLET", Color.SCARLET);
            to.setColor("CORAL", Color.CORAL);
            to.setColor("SALMON", Color.SALMON);
            to.setColor("PINK", Color.PINK);
            to.setColor("MAGENTA", Color.MAGENTA);

            to.setColor("PURPLE", Color.PURPLE);
            to.setColor("VIOLET", Color.VIOLET);
            to.setColor("MAROON", Color.MAROON);
        }
    }

    /** Mutable {@link CharSequence} which works as a slice of another {@link CharSequence}. */
    private static final class CharSequenceSlice implements CharSequence, Pool.Poolable {

        private CharSequence content = "";
        private int offset = 0, length = 0;

        public CharSequenceSlice set(CharSequence content, int start, int end) {
            if (content == null || start < 0 || start > end || end > content.length())
                throw new IndexOutOfBoundsException("content: "+content+", start: "+start+", end: "+end);
            this.content = content;
            this.offset = start;
            this.length = end - start;
            return this;
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public char charAt(int index) {
            if (index < 0 || index >= length)
                throw new IndexOutOfBoundsException("index: "+index+", length: "+length);
            return content.charAt(offset + index);
        }

        @Override
        public CharSequenceSlice subSequence(int start, int end) {
            return new CharSequenceSlice().set(this, start, end);
        }

        @Override
        public void reset() {
            this.content = "";
            this.offset = this.length = 0;
        }

        /**
         * Any other {@link CharSequence} with the same characters as this one is considered equal.
         * <b>Note</b>: This breaks symmetry requirement of {@code equals()}, but makes it possible to use this class
         * as an argument for {@link ObjectMap#get(Object)} (and related) methods for maps whose keys are purely {@link String}s.
         * This is made possible by the fact, that Java erases types, so that on the bytecode level, it compares Objects,
         * and by the fact that libGDX maps always compare key.equals(storedKey) and not the other way around.
         * This is a hack, but it allows for map searching without allocating a string for each query..
         */
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CharSequence)) {
                return false;
            }
            if (obj == this) {
                return true;
            }

            final CharSequence other = (CharSequence) obj;
            final CharSequence content = this.content;
            final int offset = this.offset;
            final int length = this.length;
            if (other.length() != length) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (content.charAt(offset + i) != other.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        /** @return hash code computed like {@link String#hashCode()} (but computed each time, not cached) */
        @Override
        public int hashCode() {
            final CharSequence content = this.content;
            final int offset = this.offset;
            final int length = this.length;
            int hash = 0;
            for (int i = 0; i < length; i++) {
                hash = 31 * hash + content.charAt(offset + i);
            }
            return hash;
        }

        @Override
        public String toString() {
            final CharSequence content = this.content;
            final int length = this.length;
            final int offset = this.offset;

            final char[] chars = new char[length];
            for (int i = 0; i < length; i++) {
                chars[i] = content.charAt(offset + i);
            }
            return new String(chars);
        }
    }
}
