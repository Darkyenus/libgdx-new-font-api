import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.text.*;
import com.badlogic.gdx.graphics.text.LayoutTextRunIterable.TextRun;
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

        final LayoutTextRunIterable<TestFont> runIterable = LayoutTextRunIterable.obtain(layoutText);

        int i = 0;
        boolean firstRegion = true;
        boolean lastRegion = false;
        int textIndex = 0;
        for (TextRun<TestFont> run : runIterable) {
            final byte flags = run.flags;

            String regionText = text.substring(run.start, run.end);

            if (i > expected.length) {
                fail("No more regions expected, but got: '"+regionText+"'");
            }

            stringFound: {
                while (i < expected.length) {
                    Object expectedItem = expected[i++];

                    if (expectedItem instanceof Character && (Character) expectedItem == '<') {
                        expectedLtr = false;
                    } else if (expectedItem instanceof Character && (Character) expectedItem == '>') {
                        expectedLtr = true;
                    } else if (expectedItem instanceof Number) {
                        expectedColor = ((Number) expectedItem).floatValue();
                    } else if (expectedItem instanceof TestFont) {
                        expectedFont = (TestFont) expectedItem;
                    } else if (expectedItem instanceof String) {
                        assertEquals(expectedItem, regionText, "Bad text @ "+(i-1));
                        break stringFound;
                    }
                }

                throw new IllegalArgumentException("Invalid test data: found no string for '"+regionText+"'");
            }

            assertEquals(textIndex, run.start, "For "+regionText);
            textIndex = run.end;

            assertEquals(regionText.equals("\t"), (flags & TextRun.FLAG_TAB_STOP) != 0, "For "+regionText);
            assertEquals(regionText.equals("\n")
                    || regionText.equals("\r")
                    || regionText.equals("\r\n"), (flags & TextRun.FLAG_LINE_BREAK) != 0, "For "+regionText);

            assertEquals(expectedFont, run.font, "For "+regionText);
            assertEquals(expectedColor, run.color, "For "+regionText);
            assertEquals(expectedLtr, run.isLtr(), "For "+regionText);

            assertFalse(lastRegion, "For "+regionText);
            lastRegion = (flags & TextRun.FLAG_LAST_RUN) != 0;

            firstRegion = false;

        }
        assertTrue(lastRegion || firstRegion, "Last run not set");
        assertEquals(textIndex, text.length);
        LayoutTextRunIterable.free(runIterable);

        if (i != expected.length) {
            fail("Done, but expected "+(expected.length - i)+" more item(s)");
        }
    }

    @Test
    public void simpleTest() {
        testBlocks("");
        testBlocks("hello", "hello");
        testBlocks("hello\tworld", "hello", "\t", "world");
        testBlocks("hello\nworld", "hello", "\n", "world");
        testBlocks("hello\rworld", "hello", "\r", "world");
        testBlocks("hello\r\nworld", "hello", "\r\n", "world");
        testBlocks("a\nb\tc\nd\t", "a", "\n", "b", "\t", "c", "\n", "d", "\t");
        testBlocks("a\nb\tc\r\nd\t", "a", "\n", "b", "\t", "c", "\r\n", "d", "\t");
        testBlocks("\n\t\n\t", "\n", "\t", "\n", "\t");
        testBlocks("\r\n\t\n\t", "\r\n", "\t", "\n", "\t");
        testBlocks("\r\n\t\r\n\t", "\r\n", "\t", "\r\n", "\t");
    }

    @Test
    public void fontColorTest() {
        testBlocks("B1");
        testBlocks("helloB1", "hello");
        testBlocks("B1hello", FONT_BOLD, 1, "hello");
        testBlocks("helloB1world", "hello", FONT_BOLD, 1, "world");
        testBlocks("aBb1cR2d", "a", FONT_BOLD, "b", 1, "c", FONT, 2, "d");
        testBlocks("a\tBb\n1cR2d", "a", "\t", FONT_BOLD, "b", "\n", 1, "c", FONT, 2, "d");
    }

    private static final String HEBREW = "טֶקסט";// Because editing RTL is pain

    @Test
    public void directionalityTest() {
        testBlocks(HEBREW, '<', HEBREW);
        testBlocks("foo "+HEBREW+" bar", "foo ", '<', HEBREW, '>', " bar");
    }

    public static final class TestFont implements Font<TestFont> {

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
        public TestFont getFallback() {
            return null;
        }

        @Override
        public GlyphLayout<TestFont> createGlyphLayout() {
            return null;
        }

        @Override
        public void dispose() {}

        @Override
        public String toString() {
            return "Font("+name+")";
        }
    }

}
