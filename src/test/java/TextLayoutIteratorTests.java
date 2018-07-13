import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.text.Font;
import com.badlogic.gdx.graphics.text.Glyph;
import com.badlogic.gdx.graphics.text.LayoutText;
import com.badlogic.gdx.graphics.text.LayoutTextIterator;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.StringBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 *
 */
public class TextLayoutIteratorTests {

    public static final TestFont FONT = new TestFont("regular");
    public static final TestFont FONT_ITALIC = new TestFont("italic");
    public static final TestFont FONT_BOLD = new TestFont("bold");


    /**
     *
     * @param template text with formatting letters: 0-9 (color), R (FONT), B (FONT_BOLD) and I (FONT_ITALIC)
     * @param expected strings, chars, numbers or fonts. numbers and fonts mean: X is expected to change to given
     *                 value for next string run, allowed chars are '>' (LTR) and '<' (RTL)
     */
    private void testBlocks(String template, Object...expected) {
        final TestFont initialFont = FONT;
        final float initialColor = 0;

        LayoutText<TestFont> layoutText = new LayoutText<>();
        StringBuilder text = new StringBuilder();

        TestFont expectedFont = initialFont;
        float expectedColor = initialColor;
        boolean expectedLtr = true;

        {
            IntArray regionStarts = new IntArray();
            Array<TestFont> regionFonts = new Array<>(TestFont.class);
            FloatArray regionColors = new FloatArray();

            boolean pending = false;
            TestFont font = initialFont;
            float color = initialColor;

            for (int i = 0; i < template.length(); i++) {
                final char c = template.charAt(i);

                if (c >= '0' && c <= '9') {
                    color = c - '0';
                    pending = true;
                } else if (c == 'R') {
                    font = FONT;
                    pending = true;
                } else if (c == 'I') {
                    font = FONT_ITALIC;
                    pending = true;
                } else if (c == 'B') {
                    font = FONT_BOLD;
                    pending = true;
                } else {
                    if (pending) {
                        regionStarts.add(text.length);
                        regionFonts.add(font);
                        regionColors.add(color);
                        pending = false;
                    }

                    text.append(c);
                }
            }

            if (regionStarts.size > 0 && regionStarts.get(0) == 0) {
                expectedColor = regionColors.get(0);
                expectedFont = regionFonts.get(0);
                if (expectedColor == -1) {
                    expectedColor = initialColor;
                }
                if (expectedFont == null) {
                    expectedFont = initialFont;
                }
            }

            layoutText.init(text.chars, text.length, initialFont, initialColor, null, null, true);
            for (int i = 0; i < regionStarts.size; i++) {
                layoutText.addRegion(regionStarts.get(i), regionFonts.get(i), regionColors.get(i));
            }
        }

        LayoutTextIterator<TestFont> iterator = new LayoutTextIterator<>();
        iterator.start(layoutText);

        int i = 0;
        boolean firstRegion = true;
        boolean lastRegion = false;
        int flags;
        while ((flags = iterator.next()) != 0) {
            String regionText = text.substring(iterator.currentStartIndex, iterator.currentEndIndex);

            if (i > expected.length) {
                fail("No more regions expected, but got: '"+regionText+"'");
            }

            boolean colorChangeExpected = false;
            boolean fontChangeExpected = false;
            stringFound: {
                while (i < expected.length) {
                    Object expectedItem = expected[i++];

                    if (expectedItem instanceof Character && (Character) expectedItem == '<') {
                        expectedLtr = false;
                    } else if (expectedItem instanceof Character && (Character) expectedItem == '>') {
                        expectedLtr = true;
                    } else if (expectedItem instanceof Number) {
                        colorChangeExpected = true;
                        expectedColor = ((Number) expectedItem).floatValue();
                    } else if (expectedItem instanceof TestFont) {
                        fontChangeExpected = true;
                        expectedFont = (TestFont) expectedItem;
                    } else if (expectedItem instanceof String) {
                        assertEquals(expectedItem, regionText, "Bad text @ "+(i-1));
                        break stringFound;
                    }
                }

                throw new IllegalArgumentException("Invalid test data: found no string for '"+regionText+"'");
            }


            assertEquals(regionText.endsWith("\t"), (flags & LayoutTextIterator.FLAG_TAB_STOP) != 0, "For "+regionText);
            assertEquals(regionText.endsWith("\n"), (flags & LayoutTextIterator.FLAG_LINE_BREAK) != 0, "For "+regionText);

            assertEquals(colorChangeExpected && !firstRegion, (flags & LayoutTextIterator.FLAG_COLOR_CHANGE) != 0, "For "+regionText);
            assertEquals(fontChangeExpected && !firstRegion, (flags & LayoutTextIterator.FLAG_FONT_CHANGE) != 0, "For "+regionText);

            assertEquals(expectedFont, iterator.currentFont, "For "+regionText);
            assertEquals(expectedColor, iterator.currentColor, "For "+regionText);
            assertEquals(expectedLtr, iterator.currentLtr, "For "+regionText);

            assertFalse(lastRegion, "For "+regionText);
            lastRegion = (flags & LayoutTextIterator.FLAG_LAST_REGION) != 0;

            firstRegion = false;
        }
        assertTrue(lastRegion || firstRegion, "Last run not set");

        if (i != expected.length) {
            fail("Done, but expected "+(expected.length - i)+" more item(s)");
        }
    }

    @Test
    public void simpleTest() {
        testBlocks("");
        testBlocks("hello", "hello");
        testBlocks("hello\tworld", "hello\t", "world");
        testBlocks("hello\nworld", "hello\n", "world");
        testBlocks("a\nb\tc\nd\t", "a\n", "b\t", "c\n", "d\t");
        testBlocks("\n\t\n\t", "\n", "\t", "\n", "\t");
    }

    @Test
    public void fontColorTest() {
        testBlocks("B1");
        testBlocks("helloB1", "hello");
        testBlocks("B1hello", FONT_BOLD, 1, "hello");
        testBlocks("helloB1world", "hello", FONT_BOLD, 1, "world");
        testBlocks("aBb1cR2d", "a", FONT_BOLD, "b", 1, "c", FONT, 2, "d");
        testBlocks("a\tBb\n1cR2d", "a\t", FONT_BOLD, "b\n", 1, "c", FONT, 2, "d");
    }

    private static final String HEBREW = "טֶקסט";// Because editing RTL is pain

    @Test
    public void directionalityTest() {
        testBlocks(HEBREW, '<', HEBREW);
        testBlocks("foo "+HEBREW+" bar", "foo ", '<', HEBREW, '>', " bar");
    }

    public static final class TestFont implements Font {

        public final String name;

        public TestFont(String name) {
            this.name = name;
        }

        @Override
        public Texture[] getPages() {
            return new Texture[0];
        }

        @Override
        public Glyph getGlyph(int glyphId) {
            return null;
        }

        @Override
        public void prepareGlyphs() {}

        @Override
        public float getSpaceXAdvance() {
            return 0f;
        }

        @Override
        public float getLineHeight() {
            return 0;
        }

        @Override
        public void dispose() {}

        @Override
        public String toString() {
            return "Font("+name+")";
        }
    }

}
