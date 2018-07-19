package com.darkyen.libgdx;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeType;
import com.badlogic.gdx.graphics.text.FontRenderCache;
import com.badlogic.gdx.graphics.text.LayoutText;
import com.badlogic.gdx.graphics.text.bitmap.BitmapFont;
import com.badlogic.gdx.graphics.text.bitmap.BitmapFontSystem;
import com.badlogic.gdx.graphics.text.bitmap.BitmapGlyphLayout;
import com.badlogic.gdx.graphics.text.harfbuzz.HarfBuzz;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.StringBuilder;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import java.io.File;
import java.text.Bidi;
import java.text.BreakIterator;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT;
import static com.badlogic.gdx.graphics.text.harfbuzz.HarfBuzz.*;
import static com.badlogic.gdx.graphics.text.harfbuzz.HarfBuzz.Font.hb_shape_list_shapers_strings;


/**
 *
 */
public class HarfBuzzTest {

    private static void lowLevelTest() {
        GdxNativesLoader.load();
        HarfBuzz.initialize();

        FreeType.Library ffLibrary = FreeType.initFreeType();

        FreeType.Face ffFace = ffLibrary.newFace(new FileHandle(new File("caladea/caladea-regular.ttf")), 0);

        ffFace.setCharSize(0, 16, 72, 72);

        Face hbFace = Face.createReferenced(ffFace);
        Font hbFont = Font.createReferenced(ffFace);//new Font(hbFace);

        IntArray tags = new IntArray();
        hbFace.getTableTags(tags);
        System.out.println("Got "+tags.size+" tags");
        for (int i = 0; i < tags.size; i++) {
            System.out.println(hb_tag_to_string(tags.get(i)));
        }

        for (String shaper : hb_shape_list_shapers_strings()) {
            System.out.println("Shaper: "+shaper);
        }

        Buffer buffer = Buffer.create();
        System.out.println("Got buffer: "+buffer);

        String text = "H‌ell🚱\nwo r l d VAVA\nVAVAعُطك";
        System.out.println("Adding");
        buffer.add(text, 0, text.length(), 0, -1);
        System.out.println("Added "+text);

        System.out.println("Direction: "+buffer.getDirection());
        System.out.println("Script: "+buffer.getScript());
        System.out.println("Language: "+buffer.getLanguage());
        System.out.println("Cluster level: "+buffer.getClusterLevel());
        System.out.println("Length: "+buffer.getLength());

        buffer.guessSegmentProperties();

        System.out.println("Direction: "+buffer.getDirection());
        System.out.println("Script: "+buffer.getScript());
        System.out.println("Language: "+buffer.getLanguage());
        System.out.println("Cluster level: "+buffer.getClusterLevel());
        System.out.println("Length: "+buffer.getLength());

        hbFont.shape(buffer, new int[0]);

        System.out.println("Direction: "+buffer.getDirection());
        System.out.println("Script: "+buffer.getScript());
        System.out.println("Language: "+buffer.getLanguage());
        System.out.println("Cluster level: "+buffer.getClusterLevel());
        System.out.println("Length: "+buffer.getLength());

        System.out.println("Buffer content: "+buffer.getContentType());

        IntArray glyphInfos = new IntArray();
        IntArray glyphPositions = new IntArray();
        buffer.getGlyphInfos(glyphInfos);
        buffer.getGlyphPositions(glyphPositions);
        final int length = buffer.getLength();
        int gI = 0;
        int gP = 0;
        assert glyphInfos.size == length * 3;
        assert glyphPositions.size == length * 4;
        for (int i = 0; i < length; i++) {
            int glyphIndex = glyphInfos.items[gI++];
            int glyphFlags = glyphInfos.items[gI++];
            int charIndex = glyphInfos.items[gI++];

            int xAdvance = glyphPositions.items[gP++];
            int yAdvance = glyphPositions.items[gP++];
            int xOffset = glyphPositions.items[gP++];
            int yOffset = glyphPositions.items[gP++];

            System.out.print("["+glyphIndex+"] "+charIndex+" '"+text.charAt(charIndex)+"'");
            if (xAdvance != 0)
                System.out.print(" xA: "+xAdvance);
            if (yAdvance != 0)
                System.out.print(" yA: "+yAdvance);
            if (xOffset != 0)
                System.out.print(" xO: "+xOffset);
            if (yOffset != 0)
                System.out.print(" yO: "+yOffset);
            if ((glyphFlags & Buffer.HB_GLYPH_FLAG_UNSAFE_TO_BREAK) != 0) {
                System.out.print(" NO_BREAK");
            }
            System.out.println();
        }

        buffer.destroy();
        System.out.println("Done");
    }

    public static void applicationTest() {
        final Lwjgl3ApplicationConfiguration conf = new Lwjgl3ApplicationConfiguration();
        new Lwjgl3Application(new ApplicationAdapter(){

            ScreenViewport viewport = new ScreenViewport();
            SpriteBatch batch;
            FontRenderCache cache;
            BitmapFont font, fontBold, fontItalic;
            BitmapGlyphLayout layout;
            LayoutText<BitmapFont> text;
            StringBuilder sb = new StringBuilder();

            Texture white;

            final Vector2 mouse = new Vector2();

            @Override
            public void create() {
                batch = new SpriteBatch();
                cache = new FontRenderCache();

                font = BitmapFontSystem.INSTANCE.createFont(
                        Gdx.files.local("test-fonts/some-time-later/some-time-later-regular64.fnt"), 2f, null);
                fontBold = BitmapFontSystem.INSTANCE.createFont(
                        Gdx.files.local("test-fonts/some-time-later/some-time-later-bold64.fnt"), 2f, null);
                fontItalic = BitmapFontSystem.INSTANCE.createFont(
                        Gdx.files.local("test-fonts/some-time-later/some-time-later-italic64.fnt"), 2f, null);

                layout = BitmapFontSystem.INSTANCE.createGlyphLayout();

                text = new LayoutText<>();

                final Pixmap pixmap = new Pixmap(10, 10, Pixmap.Format.RGBA8888);
                pixmap.setColor(Color.WHITE);
                pixmap.fill();
                white = new Texture(pixmap);


                sb.append("Hello world, WHY,\nVAVAW % Ø");

                Gdx.input.setInputProcessor(new InputAdapter() {
                    @Override
                    public boolean keyTyped(char character) {
                        if (character == 8) {
                            if (sb.length > 0) {
                                sb.length--;
                            }
                        } else {
                            sb.append(character);
                        }
                        return true;
                    }

                    private boolean touch(int screenX, int screenY) {
                        mouse.set(screenX, screenY);
                        viewport.unproject(mouse);
                        return true;
                    }

                    @Override
                    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                        return touch(screenX, screenY);
                    }

                    @Override
                    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
                        return touch(screenX, screenY);
                    }

                    @Override
                    public boolean touchDragged(int screenX, int screenY, int pointer) {
                        return touch(screenX, screenY);
                    }
                });
            }

            @Override
            public void render() {
                Gdx.gl.glClearColor(0.8f, 0.8f, 0.8f, 1f);
                Gdx.gl.glClear(GL_COLOR_BUFFER_BIT);

                text.init(sb.chars, sb.length, font, Color.ROYAL.toFloatBits(), null, null, true);
                //text.addRegion(5, fontItalic, Color.BLUE.toFloatBits());
                /*for (int i = 18; i < 24; i++) {
                    if (MathUtils.randomBoolean()) {
                        text.addRegion(i, font, Color.toFloatBits(MathUtils.random(), MathUtils.random(), MathUtils.random(), 1f));
                    }
                }
                text.addRegion(24, fontBig, Color.RED.toFloatBits());
                text.addRegion(40, fontSponge, Color.BLACK.toFloatBits());*/
                //text.addRegion(15, fontBold, Color.BLACK.toFloatBits());
                //text.addRegion(25, font, Color.BLACK.toFloatBits());


                layout.clear();
                final long layoutStart = System.nanoTime();
                for (int i = 0; i < 100; i++) {
                    layout.layoutText(text, 300f, Float.POSITIVE_INFINITY, Align.left, null);
                }
                final long duration = TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - layoutStart) / 100);
                if (Gdx.input.isKeyPressed(Input.Keys.F1)) {
                    System.out.println(duration+" ms");
                }

                final float textX = Gdx.graphics.getWidth()/2f - layout.width()/2f;
                final float textY = Gdx.graphics.getHeight()/2f + layout.height()/2f;
                cache.addGlyphs(layout, textX, textY);

                batch.setProjectionMatrix(viewport.getCamera().combined);
                batch.begin();
                batch.enableBlending();
                batch.setColor(1f, 1f, 1f, 1f);
                batch.draw(white, textX, textY, layout.width(), -layout.height());
                cache.draw(batch);

                //Caret
                final Rectangle caretRect;
                if (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)) {
                    final int caretIndex = (int) ((System.currentTimeMillis() / 700) % (text.length() + 1));
                    caretRect = layout.getCaretPosition(caretIndex);
                } else {
                    final int index = layout.getIndexAt(mouse.x - textX, mouse.y  - textY, true);
                    caretRect = layout.getCaretPosition(index);
                }
                if (caretRect != null) {
                    caretRect.width = 1f;
                    batch.setColor(0f, 0f, 0f, 1f);
                    batch.draw(white, textX + caretRect.x, textY + caretRect.y, caretRect.width, caretRect.height);
                }

                batch.end();

                cache.clear();
            }

            @Override
            public void resize(int width, int height) {
                viewport.update(width, height, true);
            }

            @Override
            public void dispose() {
                batch.dispose();
            }
        }, conf);
    }

    public static void bidiTest() {
        final String hebrew1 = "אטאו";
        final String hebrew2 = "כגכד";
        //final String text = "normal \u202e reverse \u202d embedded \u202c againreverse \u202c norback";
        final String text = hebrew1+"(C+\n+)"+hebrew2;
        final Bidi bidi = new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);

        System.out.println("Mixed: "+bidi.isMixed());
        System.out.println("LTR: "+bidi.isLeftToRight());
        System.out.println("RTL: "+bidi.isRightToLeft());
        System.out.println("base LTR: "+bidi.baseIsLeftToRight());
        System.out.println("base level: "+bidi.getBaseLevel());
        for (int i = 0; i < bidi.getRunCount(); i++) {
            final int start = bidi.getRunStart(i);
            final int limit = bidi.getRunLimit(i);
            final int level = bidi.getRunLevel(i);

            System.out.println(level+") "+text.substring(start, limit));
        }

        System.out.println(bidi);
    }

    public static void lineBreakTest() {
        final BreakIterator lineInstance = BreakIterator.getLineInstance();
        lineInstance.setText("B !    world.\r\nHow is this?");
        System.out.println(lineInstance.first());
        while (true) {
            final int next = lineInstance.next();
            if (next == BreakIterator.DONE) {
                break;
            }
            System.out.println(next);
        }
    }

    public static void bidiAssumptionsTest() {
        final byte[] levels = {-1, 3, 3, 3, -1};
        final String[] things = {"A", "B", "C", "D", "E"};
        Bidi.reorderVisually(levels, 0, things, 0, levels.length);

        System.out.println(Arrays.toString(things));
    }

    public static void main(String[] args) {
        applicationTest();
        //bidiTest();
        //lineBreakTest();
        //bidiAssumptionsTest();
    }
}
