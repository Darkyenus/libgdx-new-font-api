package com.badlogic.gdx.graphics.text.harfbuzz;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.ImagePacker;
import com.badlogic.gdx.graphics.g2d.freetype.FreeType;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeExtra;
import com.badlogic.gdx.graphics.text.Font;
import com.badlogic.gdx.graphics.text.Glyph;
import com.badlogic.gdx.graphics.text.GlyphLayout;
import com.badlogic.gdx.graphics.text.harfbuzz.HBFontSystem.FontParameters;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.nio.ByteBuffer;
import java.util.Comparator;

/**
 *
 */
public class HBFont implements Font<HBFont> {

    private static final int PACK_TO_TEXTURE_THRESHOLD = 10;

    /** Special Glyph instance that signifies an error with the glyph. */
    private static final HBGlyph ERROR_GLYPH = new HBGlyph(-1);

    private final FreeType.Face face;
    final HarfBuzz.Font hbFont;
    private final FreeType.Stroker stroker;
    private final FontParameters parameters;
    private final ImagePacker packer;
    private final Comparator<Glyph> packerComparator;

    public final float densityScale;

    private final Array<Texture> textures = new Array<>(true, 4, Texture.class);
    private final HBGlyph[] glyphs;

    private final Array<HBGlyph> dirtyGlyphs = new Array<>(false, 32, HBGlyph.class);

    /** The distance from one line of text to the next. */
    public float lineHeight;
    /** Distance from top of the drawing area to baseline. */
    public float base;
    /** The x-advance of the space character. Used for all unknown whitespace characters or tab advance. */
    public float spaceXAdvance;

    protected HBFont(FreeType.Library library, FreeType.Face face, float size, float pixelsPerPoint, FontParameters parameters) {
        this.face = face;
        this.hbFont = HarfBuzz.Font.createReferenced(face);
        this.glyphs = new HBGlyph[face.getNumGlyphs()];
        this.densityScale = 1f / pixelsPerPoint;

        if (parameters.borderWidth > 0) {
            stroker = library.createStroker();
            stroker.set((int)(parameters.borderWidth * 64f),
                    parameters.borderStraight ? FreeType.FT_STROKER_LINECAP_BUTT : FreeType.FT_STROKER_LINECAP_ROUND,
                    parameters.borderStraight ? FreeType.FT_STROKER_LINEJOIN_MITER_FIXED : FreeType.FT_STROKER_LINEJOIN_ROUND, 0);
        } else {
            stroker = null;
        }

        this.parameters = parameters;
        this.packer = parameters.packer != null ? parameters.packer : parameters.createDefaultImagePacker();
        this.packerComparator = new Comparator<Glyph>() {
            @Override
            public int compare(Glyph o1, Glyph o2) {
                return packer.packStrategy.compare((int) o1.width, (int) o1.height, (int) o2.width, (int) o2.height);
            }
        };

        final int pixelSize = Math.round(size * pixelsPerPoint);
        if (isFaceScalable(face)) {
            FreeTypeExtra.FT_Request_Size(face,
                    FreeTypeExtra.FT_Size_Request_Type_FT_SIZE_REQUEST_TYPE_REAL_DIM,
                    HarfBuzz.to26p6FromInt(pixelSize), HarfBuzz.to26p6FromInt(pixelSize), 0, 0);
        } else {
            // TODO(jp): Implement matching of the closest available size.
            // FreeType seems to do this only for perfect matches,
            // but we should do it with a best effort basis.
            face.setPixelSizes(0, pixelSize);
        }

        if (face.loadChar(' ', FreeType.FT_LOAD_DEFAULT)) {
            spaceXAdvance = FreeType.toInt(face.getGlyph().getAdvanceX()) * densityScale;
        } else {
            spaceXAdvance = FreeType.toInt(face.getMaxAdvanceWidth()) * densityScale;
        }

        final FreeType.SizeMetrics metrics = face.getSize().getMetrics();
        lineHeight = FreeType.toInt(metrics.getHeight()) * densityScale;
        base = FreeType.toInt(metrics.getAscender()) * densityScale;
    }

    private static boolean isFaceScalable(FreeType.Face face) {
        final int faceFlags = face.getFaceFlags();
        return (faceFlags & FreeType.FT_FACE_FLAG_SCALABLE) != 0;
    }

    @Override
    public Texture[] getPages() {
        return textures.items;
    }

    @Override
    public HBGlyph getGlyph(int glyphId) {
        final HBGlyph[] glyphs = this.glyphs;
        if (glyphId < 0 || glyphId > glyphs.length) {
            return null;
        }

        HBGlyph resultGlyph = glyphs[glyphId];

        if (resultGlyph == null) {
            resultGlyph = glyphs[glyphId] = createGlyph(glyphId);
            if (resultGlyph.unpackedPixmap != null) {
                dirtyGlyphs.add(resultGlyph);
            }
        }

        if (resultGlyph == ERROR_GLYPH) {
            return null;
        }

        return resultGlyph;
    }

    private static Rectangle prepareGlyphs_packedTo = new Rectangle();

    @Override
    public void prepareGlyphs() {
        final Array<HBGlyph> dirtyGlyphs = this.dirtyGlyphs;
        final int dirtyGlyphCount = dirtyGlyphs.size;
        if (dirtyGlyphCount == 0) {
            return;
        }

        dirtyGlyphs.sort(packerComparator);
        final HBGlyph[] dirtyGlyphItems = dirtyGlyphs.items;

        packer.packToTexture = dirtyGlyphCount < PACK_TO_TEXTURE_THRESHOLD;

        final Rectangle packedTo = HBFont.prepareGlyphs_packedTo;
        final float invTexWidth = 1.0f / packer.pageWidth;
        final float invTexHeight = 1.0f / packer.pageHeight;

        for (int i = 0; i < dirtyGlyphCount; i++) {
            final HBGlyph dirtyGlyph = dirtyGlyphItems[i];

            final Pixmap glyphPixmap = dirtyGlyph.unpackedPixmap;
            dirtyGlyph.unpackedPixmap = null;
            final ImagePacker.Page page = packer.pack(glyphPixmap, packedTo);
            glyphPixmap.dispose();

            final int pageIndex = packer.pages.indexOf(page, true);
            dirtyGlyph.page = (short) pageIndex;

            dirtyGlyph.u = packedTo.x * invTexWidth;
            dirtyGlyph.u2 = (packedTo.x + packedTo.width) * invTexWidth;
            dirtyGlyph.v = (packedTo.y + packedTo.height) * invTexHeight;
            dirtyGlyph.v2 = packedTo.y * invTexHeight;
        }

        dirtyGlyphs.size = 0;

        packer.updateTextures(textures);
    }

    @Override
    public HBFont getFallback() {
        return null;
    }

    @Override
    public GlyphLayout<HBFont> createGlyphLayout() {
        return new HBGlyphLayout();
    }

    @Override
    public void dispose() {
        face.dispose();
        if (stroker != null) {
            stroker.dispose();
        }
        if (parameters.packer == null) {
            packer.dispose();
        }
    }

    HBGlyph createGlyph (int glyphId) {
        if (!face.loadGlyph(glyphId, parameters.hinting.toFreeTypeLoadFlags())) {
            // Should not happen!
            Gdx.app.error("HBFont", "Failed to load glyph " + glyphId);
            return ERROR_GLYPH;
        }

        FreeType.GlyphSlot slot = face.getGlyph();
        FreeType.Glyph mainGlyph = slot.getGlyph();

        try {
            mainGlyph.toBitmap(parameters.mono ? FreeType.FT_RENDER_MODE_MONO : FreeType.FT_RENDER_MODE_NORMAL);
        } catch (GdxRuntimeException e) {
            mainGlyph.dispose();
            Gdx.app.error("FreeTypeFontGenerator", "Couldn't render glyph " + glyphId);
            return ERROR_GLYPH;
        }

        final HBGlyph resultGlyph = new HBGlyph(glyphId);
        final FreeType.Bitmap glyphBitmap = mainGlyph.getBitmap();
        Pixmap mainPixmap = glyphBitmap.getPixmap(Pixmap.Format.RGBA8888, parameters.color, parameters.gamma);

        if (glyphBitmap.getWidth() > 0 && glyphBitmap.getRows() > 0) {
            if (parameters.borderWidth > 0) {
                // execute stroker; this generates a glyph "extended" along the outline
                int top = mainGlyph.getTop(), left = mainGlyph.getLeft();
                FreeType.Glyph borderGlyph = slot.getGlyph();
                borderGlyph.strokeBorder(stroker, false);
                borderGlyph.toBitmap(parameters.mono ? FreeType.FT_RENDER_MODE_MONO : FreeType.FT_RENDER_MODE_NORMAL);
                int offsetX = left - borderGlyph.getLeft();
                int offsetY = -(top - borderGlyph.getTop());

                // Render border (pixmap is bigger than main).
                FreeType.Bitmap borderBitmap = borderGlyph.getBitmap();
                Pixmap borderPixmap = borderBitmap
                        .getPixmap(Pixmap.Format.RGBA8888, parameters.borderColor, parameters.borderGamma);

                // Draw main glyph on top of border.
                for (int i = 0, n = parameters.renderCount; i < n; i++)
                    borderPixmap.drawPixmap(mainPixmap, offsetX, offsetY);

                mainPixmap.dispose();
                mainGlyph.dispose();
                mainPixmap = borderPixmap;
                mainGlyph = borderGlyph;
            }

            if (parameters.shadowOffsetX != 0 || parameters.shadowOffsetY != 0) {
                int mainW = mainPixmap.getWidth(), mainH = mainPixmap.getHeight();
                int shadowOffsetX = Math.max(parameters.shadowOffsetX, 0), shadowOffsetY = Math
                        .max(parameters.shadowOffsetY, 0);
                int shadowW = mainW + Math.abs(parameters.shadowOffsetX), shadowH = mainH + Math
                        .abs(parameters.shadowOffsetY);
                Pixmap shadowPixmap = new Pixmap(shadowW, shadowH, mainPixmap.getFormat());

                Color shadowColor = parameters.shadowColor;
                float a = shadowColor.a;
                if (a != 0) {
                    byte r = (byte) (shadowColor.r * 255), g = (byte) (shadowColor.g * 255), b = (byte) (shadowColor.b * 255);
                    ByteBuffer mainPixels = mainPixmap.getPixels();
                    ByteBuffer shadowPixels = shadowPixmap.getPixels();
                    for (int y = 0; y < mainH; y++) {
                        int shadowRow = shadowW * (y + shadowOffsetY) + shadowOffsetX;
                        for (int x = 0; x < mainW; x++) {
                            int mainPixel = (mainW * y + x) * 4;
                            byte mainA = mainPixels.get(mainPixel + 3);
                            if (mainA == 0) continue;
                            int shadowPixel = (shadowRow + x) * 4;
                            shadowPixels.put(shadowPixel, r);
                            shadowPixels.put(shadowPixel + 1, g);
                            shadowPixels.put(shadowPixel + 2, b);
                            shadowPixels.put(shadowPixel + 3, (byte) ((mainA & 0xff) * a));
                        }
                    }
                }

                // Draw main glyph (with any border) on top of shadow.
                for (int i = 0, n = parameters.renderCount; i < n; i++)
                    shadowPixmap.drawPixmap(mainPixmap, Math.max(-parameters.shadowOffsetX, 0), Math
                            .max(-parameters.shadowOffsetY, 0));
                mainPixmap.dispose();
                mainPixmap = shadowPixmap;
            } else if (parameters.borderWidth == 0) {
                // No shadow and no border, draw glyph additional times.
                for (int i = 0, n = parameters.renderCount - 1; i < n; i++)
                    mainPixmap.drawPixmap(mainPixmap, 0, 0);
            }
        }

        /*
        if (bitmapped) {// TODO(jp): This does not feel right
            mainPixmap.setColor(Color.CLEAR);
            mainPixmap.fill();
            ByteBuffer buf = glyphBitmap.getBuffer();
            int whiteIntBits = Color.WHITE.toIntBits();
            int clearIntBits = Color.CLEAR.toIntBits();
            for (int h = 0; h < glyph.height; h++) {
                int idx = h * glyphBitmap.getPitch();
                for (int w = 0; w < (glyph.width + glyph.xOffset); w++) {
                    int bit = (buf.get(idx + (w / 8)) >>> (7 - (w % 8))) & 1;
                    mainPixmap.drawPixel(w, h, ((bit == 1) ? whiteIntBits : clearIntBits));
                }
            }
        }
        */

        resultGlyph.unpackedPixmap = mainPixmap;

        final float densityScale = this.densityScale;
        resultGlyph.width = mainPixmap.getWidth() * densityScale;
        resultGlyph.height = mainPixmap.getHeight() * densityScale;
        resultGlyph.xOffset = mainGlyph.getLeft() * densityScale;
        resultGlyph.yOffset = -(mainPixmap.getHeight() - mainGlyph.getTop()) * densityScale;

        mainGlyph.dispose();
        return resultGlyph;
    }

    public static final class HBGlyph extends Glyph {

        /** Pixmap awaiting to be packed to the font packer. */
        Pixmap unpackedPixmap = null;

        HBGlyph(int glyphId) {
            super(glyphId);
        }
    }
}
