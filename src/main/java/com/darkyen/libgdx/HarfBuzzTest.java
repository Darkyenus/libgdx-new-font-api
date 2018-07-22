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

import static com.badlogic.gdx.graphics.GL20.GL_COLOR_BUFFER_BIT;
import static com.badlogic.gdx.graphics.text.harfbuzz.HarfBuzz.*;
import static com.badlogic.gdx.graphics.text.harfbuzz.HarfBuzz.Font.hb_shape_list_shapers_strings;


/**
 *
 */
@SuppressWarnings("unused")
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

            boolean leftToRight = true;

            int caretIndex = 0;

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
                if (false)
                sb.append("Curabitur vel magna dui. Curabitur ac enim cursus, congue sapien in, tempor massa. Donec vitae tristique magna. Fusce dignissim nisl ut hendrerit vulputate. Aliquam quis magna molestie, feugiat neque sed, commodo justo. Pellentesque in posuere elit. Nullam vitae blandit ligula. Integer malesuada ornare urna, vitae fringilla metus fringilla sed. Vestibulum at leo risus. Morbi vehicula sodales arcu vel gravida. Aenean tempor ipsum non tincidunt vehicula. Etiam tincidunt leo ante, quis iaculis libero luctus non.\n" +
                        "\n" +
                        "Aliquam porttitor sed enim at viverra. Proin quis egestas neque. Sed lectus enim, tempor et sapien vel, varius molestie elit. Aliquam luctus ante in efficitur auctor. Integer et erat pharetra, ultricies nisl ut, bibendum mi. Integer molestie felis eu aliquet maximus. Vestibulum tristique efficitur turpis in ornare. Etiam pretium tincidunt sapien, sit amet facilisis nulla lacinia sed.\n" +
                        "\n" +
                        "Sed sit amet tincidunt sapien, quis porta dui. Quisque a sem a ipsum pulvinar tincidunt. Aliquam non nunc vel nulla semper sollicitudin. Fusce pellentesque, turpis a convallis fringilla, purus ante sollicitudin enim, ac mollis nulla arcu sit amet tellus. Aliquam ultricies ligula odio, eget iaculis elit pharetra vitae. Vivamus feugiat cursus turpis, a semper velit commodo eu. Vestibulum dapibus quis tortor vitae varius. Suspendisse venenatis lectus et enim bibendum venenatis. Ut eget magna risus. Nulla sagittis malesuada bibendum. Nulla posuere orci a sapien congue, vitae pharetra ex pharetra.\n" +
                        "\n" +
                        "Etiam viverra ex quis lacus consequat dignissim. Donec lacinia sodales nunc, non ultrices diam tincidunt sed. Proin ut orci vitae mauris dapibus tincidunt ac vitae augue. Sed pellentesque lectus sem, et dignissim tellus porttitor porttitor. Aliquam euismod nunc ut porta eleifend. Proin quis augue dolor. Pellentesque nulla purus, feugiat vel consectetur et, sollicitudin eleifend est. Donec vulputate volutpat egestas. Vivamus id dolor at mi molestie finibus non vel metus. Integer eget nibh erat.\n" +
                        "\n" +
                        "Ut eget ipsum in diam interdum accumsan sed ac metus. Morbi et risus justo. Nulla tristique eros eget leo ullamcorper pharetra. Donec sit amet odio est. Proin nisi mi, imperdiet quis posuere quis, tempor quis nunc. Nulla mi quam, tempus quis ornare sed, tempor a elit. Nullam nec viverra enim. Duis commodo euismod velit eu tempus. Quisque facilisis ante in fringilla scelerisque.\n" +
                        "\n" +
                        "In molestie lobortis velit, sit amet dictum dolor imperdiet in. Quisque nibh dui, feugiat ac ultricies eu, tempor eu ipsum. Nulla hendrerit justo rutrum malesuada blandit. Integer posuere pellentesque pulvinar. Maecenas fermentum justo a massa sodales fermentum. Nullam nec blandit nisl, a iaculis quam. Aliquam at justo orci. Cras eget lectus ac nibh ornare vestibulum. Cras lectus nisl, vestibulum ac tristique eu, fermentum non sem. Aliquam erat volutpat. Ut ut felis sit amet nunc faucibus tincidunt eu vitae diam. Cras sit amet nisl sem. Sed a ipsum eget urna ultricies aliquam at sit amet urna.\n" +
                        "\n" +
                        "Vivamus quis velit malesuada, congue urna et, faucibus elit. Proin ullamcorper ultrices ante, a luctus dolor pharetra sit amet. Fusce rhoncus vitae urna et dictum. Sed odio nunc, euismod eget pulvinar non, aliquet nec mi. Donec felis lorem, dictum vel aliquam a, lobortis id erat. Vestibulum auctor laoreet dui, eu molestie lorem accumsan id. Duis dignissim dui id justo facilisis rutrum sed vitae massa. Curabitur sit amet augue vel nisi porta venenatis sed quis leo. Proin ullamcorper, leo a vulputate ornare, nisl nulla ultrices orci, a tincidunt ex diam sit amet mauris. Phasellus pretium turpis vitae sapien vestibulum viverra. Duis ac tellus sem. Vestibulum augue tortor, gravida quis luctus sed, porta sollicitudin dui. Etiam mattis mollis nisi sed consequat.\n" +
                        "\n" +
                        "Phasellus sit amet lectus sollicitudin, condimentum nisi vulputate, volutpat erat. Nulla facilisi. Morbi ut massa at augue condimentum accumsan. Duis nec iaculis arcu. Ut a massa sit amet nisl congue finibus. Morbi condimentum eget diam eget semper. Duis egestas purus at purus viverra, quis auctor quam blandit. Pellentesque elementum imperdiet arcu eget placerat. Integer efficitur vitae orci ullamcorper pellentesque. Vivamus dictum massa mattis diam tincidunt faucibus. Etiam venenatis consequat elit ac mattis. Donec id magna mattis augue placerat iaculis a ac augue. Cras ultrices ullamcorper neque, id euismod leo semper vel. Praesent nec dictum magna. Nulla sagittis lacus at euismod condimentum. Etiam vehicula velit purus, ac efficitur erat laoreet ut.\n" +
                        "\n" +
                        "Curabitur id sagittis sem. Nam consectetur finibus elit. Nam ut sapien ornare sapien dignissim auctor non a lorem. Nunc vitae sapien mauris. Aenean non posuere nisi. Suspendisse eu nibh felis. Quisque quis condimentum nibh. Vivamus dictum lectus quis orci congue, eu feugiat lorem sagittis. Nulla varius sagittis urna, quis congue lectus. Quisque sed ante vitae enim ornare vulputate a eu quam. Nunc at tellus in erat efficitur tempor vitae id dui. Nunc tempus leo non tortor feugiat, eu fringilla augue tristique.\n" +
                        "\n" +
                        "Fusce at elit ipsum. Fusce ac consequat eros. Pellentesque eget mi luctus, placerat nisl nec, condimentum lorem. Nam vehicula at purus sed pretium. Curabitur diam tortor, euismod cursus nibh vitae, scelerisque ultrices eros. Suspendisse potenti. Praesent odio ligula, efficitur sed tristique a, convallis vel ipsum. Nulla mauris ante, eleifend sit amet mauris vel, pharetra laoreet lorem. Nam feugiat enim dui, et vehicula massa tincidunt non. Nullam urna ex, convallis at semper a, aliquam vitae lectus. Aenean auctor ac dolor sed sagittis. Curabitur rhoncus, nulla nec rhoncus semper, ante mauris feugiat justo, vel lobortis purus nulla cursus lectus.\n" +
                        "\n" +
                        "Mauris placerat varius felis, in porta nunc interdum vel. Etiam ornare aliquet massa et volutpat. Mauris cursus turpis at sollicitudin finibus. In malesuada auctor tellus, at hendrerit lacus tempus in. Nulla vitae metus nulla. Donec tristique aliquet libero. Nulla vitae dolor lacinia, convallis augue quis, suscipit elit. In hac habitasse platea dictumst.\n" +
                        "\n" +
                        "Nunc in nunc lorem. Fusce eu elit at sapien dignissim accumsan eget et lacus. Sed id ipsum sapien. Morbi et semper neque. Aliquam pharetra odio id faucibus commodo. Nunc sagittis risus sit amet elit sagittis consequat. Praesent dictum mollis velit id mattis. Proin maximus ex gravida risus tincidunt feugiat. Proin hendrerit gravida justo et placerat. Duis faucibus lacus vitae mi luctus, a molestie urna tristique. Nulla dictum nisl quis justo viverra, eget tincidunt massa porttitor. Duis non purus vel nibh lacinia volutpat. Nullam quis congue diam. Nam egestas gravida cursus. Nam malesuada.");
                caretIndex = sb.length;

                Gdx.input.setInputProcessor(new InputAdapter() {
                    @Override
                    public boolean keyTyped(char character) {
                        if (character == 8) {
                            if (caretIndex > 0) {
                                sb.deleteCharAt(--caretIndex);
                            }
                        } else {
                            sb.insert(caretIndex++, character);
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

                if (Gdx.input.isKeyJustPressed(Input.Keys.F2)) {
                    leftToRight = !leftToRight;
                }

                text.init(sb.chars, sb.length, font, Color.ROYAL.toFloatBits(), null, leftToRight, null);
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
                    layout.layoutText(text, 300f, 300f, Align.left, "...");
                }
                final double duration = (System.nanoTime() - layoutStart) / 100_000_000.0;
                if (Gdx.input.isKeyPressed(Input.Keys.F1)) {
                    System.out.printf("%.3f ms\n", duration);
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
                if (Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
                    caretIndex = layout.getIndexAt(mouse.x - textX, mouse.y  - textY, true);
                }
                final Rectangle caretRect = layout.getCaretPosition(caretIndex);
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
