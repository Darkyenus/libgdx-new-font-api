package com.badlogic.gdx.graphics.text;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.utils.*;

import java.util.Arrays;
import java.util.Comparator;

/** Takes care of holding and customizing a {@link com.badlogic.gdx.graphics.text.GlyphLayout} geometry.
 * Since it holds the geometry, it can be used as a cache for repeated rendering of static text,
 * saving the need to compute the glyph geometry on each frame.
 *
 * Features:
 * <ul>
 *     <li>Build geometry for {@link com.badlogic.gdx.graphics.text.GlyphLayout}'s glyphs, reordering their draw order to minimize
 *     draw calls caused by texture change</li>
 *     <li>Hold the geometry in {@link Batch#draw(Texture, float[], int, int)} format</li>
 *     <li>Allow to translate, scale and affine transform the geometry</li>
 *     <li>Allow to change the RGBA color of geometry, either by replacing it or tinting it</li>
 *     <li>Allow to render the geometry to {@link Batch}</li>
 * </ul>
 */
public class FontRenderCache {

    /** Texture IDs for pages */
    private final Array<Texture> pageTextures = new Array<>(true, 10, Texture.class);
    /** Vertex data per page. Values are borrowed from pool. */
    private final Array<FloatArray> pageVertices = new Array<>(true, 10, FloatArray.class);

    private static final Pool<FloatArray> PAGE_VERTICES_POOL = new Pool<FloatArray>() {
        @Override
        protected FloatArray newObject() {
            return new FloatArray(true, 20 * 50); // Preallocate space for 50 glyphs
        }

        @Override
        protected void reset(FloatArray object) {
            object.clear();
        }
    };

    private static final Comparator<Texture> TEXTURE_COMPARATOR = new Comparator<Texture>() {
        @Override
        public int compare(Texture o1, Texture o2) {
            final int t1 = o1.getTextureObjectHandle();
            final int t2 = o2.getTextureObjectHandle();
            return Integer.compare(t1, t2);
        }
    };

    private static FloatArray[] PAGE_MAP_CACHE = new FloatArray[10];

    /** Allocate space for all pages of the font, and return array with vertex-data holders for corresponding pages..
     * NOTE: Result is valid only as long as font pages don't change and this method nor clear() is called (again).
     * @return array where it[font.glyph.page] == FloatArray to which vertex data should be added to
     * (usually returns the same instance) */
    private <F extends Font<F>> FloatArray[] preparePageMappingForFont(F font) {
        final Texture[] fontTextures = font.getPages();

        final Array<FloatArray> pageVertices = this.pageVertices;
        final Array<Texture> pageTextures = this.pageTextures;

        int pageCount = 0;

        // Check that all textures are present, insert them if needed
        for (final Texture texture : fontTextures) {
            if (texture == null) {
                break;
            }
            pageCount++;

            int index = Arrays.binarySearch(pageTextures.items, 0, pageTextures.size, texture, TEXTURE_COMPARATOR);
            if (index < 0) {
                // Insert it, while keeping order
                int insertPoint = -index - 1;
                pageTextures.insert(insertPoint, texture);
                FloatArray obtain = PAGE_VERTICES_POOL.obtain();
                obtain.clear();
                pageVertices.insert(insertPoint, obtain);
            }
        }

        FloatArray[] result = PAGE_MAP_CACHE;
        if (result.length < pageCount) {
            result = PAGE_MAP_CACHE = new FloatArray[pageCount];
        }

        // Now that internal indices are stable, we can return them
        for (int i = 0; i < pageCount; i++) {
            final Texture texture = fontTextures[i];

            int index = Arrays.binarySearch(pageTextures.items, 0, pageTextures.size, texture, TEXTURE_COMPARATOR);
            assert index >= 0;
            result[i] = pageVertices.get(index);
        }

        return result;
    }

    /**
     * Render the glyphs from glyphLayout to the cache.
     * What is currently in the cache will be kept.
     *
     * @param glyphLayout with some laid out glyphs, not null
     * @param x of the upper left corner at which text should be rendered to
     * @param y of the upper left corner at which text should be rendered to
     */
    public <F extends Font<F>> void addGlyphs(GlyphLayout<F> glyphLayout, float x, float y) {
        Font lastFont = null;
        FloatArray[] pageVertices = null;

        for (GlyphRun<F> run : glyphLayout.runs) {
            final F font = run.font;
            assert font != null;
            if (font != lastFont) {
                lastFont = font;
                pageVertices = preparePageMappingForFont(font);
            }

            final boolean flipMirrored = !run.isLtr();
            final float color = run.color;

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

                float gX = baseX + glyphX[i] + glyph.xOffset;
                float gY = baseY + glyphY[i] + glyph.yOffset;
                float gX2 = gX + glyph.width;
                float gY2 = gY + glyph.height;

                if (flipMirrored && (glyph.flags & Glyph.FLAG_MIRRORED) != 0) {
                    // Flip RTL mirrored glyphs (https://www.compart.com/en/unicode/mirrored)
                    float tmp = gX;
                    gX = gX2;
                    gX2 = tmp;
                }

                final float u = glyph.u, u2 = glyph.u2, v = glyph.v, v2 = glyph.v2;

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

    /** Sets the position of the text, relative to its current position.
     * Does not affect subsequently added text.
     * @param xAmount The amount in x to move the text
     * @param yAmount The amount in y to move the text */
    public void translate (float xAmount, float yAmount) {
        if (xAmount == 0 && yAmount == 0) return;

        final Array<FloatArray> pageVertices = this.pageVertices;
        final FloatArray[] pageVerticesItems = pageVertices.items;
        final int pageVerticesCount = pageVertices.size;

        for (int i = 0; i < pageVerticesCount; i++) {
            final float[] vertices = pageVerticesItems[i].items;
            final int vertexCount = pageVerticesItems[i].size;

            for (int v = 0; v < vertexCount; v += 5) {
                vertices[v] += xAmount;
                vertices[v + 1] += yAmount;
            }
        }
    }

    /** Scales the position of the text, around origin.
     * Scale of 0 is silently ignored, as it would make the contents useless.
     * Does not affect subsequently added text.
     * @param xAmount The amount in x to scale the text
     * @param yAmount The amount in y to scale the text */
    public void scale (float xAmount, float yAmount) {
        if (xAmount == 0f) {
            xAmount = 1f;
        }
        if (yAmount == 0f) {
            yAmount = 1f;
        }
        if (xAmount == 1f && yAmount == 1f) return;

        final Array<FloatArray> pageVertices = this.pageVertices;
        final FloatArray[] pageVerticesItems = pageVertices.items;
        final int pageVerticesCount = pageVertices.size;

        for (int i = 0; i < pageVerticesCount; i++) {
            final float[] vertices = pageVerticesItems[i].items;
            final int vertexCount = pageVerticesItems[i].size;

            for (int v = 0; v < vertexCount; v += 5) {
                vertices[v] *= xAmount;
                vertices[v + 1] *= yAmount;
            }
        }
    }

    /** Transforms all positions of the text.
     * Does not affect subsequently added text.
     * @param transform to be used on all present vertices, not null */
    public void transform (Matrix3 transform) {
        final Array<FloatArray> pageVertices = this.pageVertices;
        final FloatArray[] pageVerticesItems = pageVertices.items;
        final int pageVerticesCount = pageVertices.size;

        for (int i = 0; i < pageVerticesCount; i++) {
            final float[] vertices = pageVerticesItems[i].items;
            final int vertexCount = pageVerticesItems[i].size;

            for (int v = 0; v < vertexCount; v += 5) {
                final float vX = vertices[v];
                final float vY = vertices[v + 1];
                final float newX = vX * transform.val[0] + vY * transform.val[3] + transform.val[6];
                final float newY = vX * transform.val[1] + vY * transform.val[4] + transform.val[7];
                vertices[v] = newX;
                vertices[v + 1] = newY;
            }
        }
    }

    /** Sets the color of all text currently in the cache. Does not affect subsequently added text. */
    public void setColors (float color) {
        final Array<FloatArray> pageVertices = this.pageVertices;
        final FloatArray[] pageVerticesItems = pageVertices.items;
        final int pageVerticesCount = pageVertices.size;

        for (int i = 0; i < pageVerticesCount; i++) {
            final float[] vertices = pageVerticesItems[i].items;
            final int vertexCount = pageVerticesItems[i].size;

            for (int v = 0; v < vertexCount; v += 5) {
                vertices[v + 3] = color;
            }
        }
    }

    /** Sets the color of all text currently in the cache. Does not affect subsequently added text. */
    public void setColors (Color tint) {
        setColors(tint.toFloatBits());
    }

    /** Sets the color of all text currently in the cache. Does not affect subsequently added text. */
    public void setColors (float r, float g, float b, float a) {
        int intBits = ((int)(255 * a) << 24) | ((int)(255 * b) << 16) | ((int)(255 * g) << 8) | ((int)(255 * r));
        setColors(NumberUtils.intToFloatColor(intBits));
    }

    /** Tints all text currently in the cache (by multiplying RGBA components). Does not affect subsequently added text. */
    public void tint(float r, float g, float b, float a) {
        final Array<FloatArray> pageVertices = this.pageVertices;
        final FloatArray[] pageVerticesItems = pageVertices.items;
        final int pageVerticesCount = pageVertices.size;

        for (int i = 0; i < pageVerticesCount; i++) {
            final float[] vertices = pageVerticesItems[i].items;
            final int vertexCount = pageVerticesItems[i].size;

            for (int v = 0; v < vertexCount; v += 5) {
                final int color = NumberUtils.floatToIntColor(vertices[v + 3]);
                // Based on Color.toFloatBits, but with eliminated 255 division/multiplication
                final float nr = (color & 0xFF) * r;
                final float ng = ((color >>> 8) & 0xFF) * g;
                final float nb = ((color >>> 16) & 0xFF) * b;
                final float na = ((color >>> 24) & 0xFF) * a;

                vertices[v + 3] = NumberUtils.intToFloatColor(
                        ((int)(na) << 24) | ((int)(nb) << 16) | ((int)(ng) << 8) | ((int)(nr)));
            }
        }
    }

    /** Tints all text currently in the cache (by multiplying RGBA components). Does not affect subsequently added text. */
    public void tint (Color tint) {
        tint(tint.r, tint.g, tint.b, tint.a);
    }

    /** Tints all text currently in the cache (by multiplying RGBA components). Does not affect subsequently added text.
     * @param tint float packed color ({@link Color#toFloatBits()}) */
    public void tint (float tint) {
        final int color = NumberUtils.floatToIntColor(tint);
        final float r = (color & 0xFF) / 255f;
        final float g = ((color >>> 8) & 0xFF) / 255f;
        final float b = ((color >>> 16) & 0xFF) / 255f;
        final float a = ((color >>> 24) & 0xFF) / 255f;
        tint(r, g, b, a);
    }

    /** Sets the alpha component of all text currently in the cache. Does not affect subsequently added text.
     * Special case of {@link #setColors} that replaces only alpha. */
    public void setAlphas (float alpha) {
        final int alphaMask = (int) (alpha * 0xFF) << 24;

        final Array<FloatArray> pageVertices = this.pageVertices;
        final FloatArray[] pageVerticesItems = pageVertices.items;
        final int pageVerticesCount = pageVertices.size;

        for (int i = 0; i < pageVerticesCount; i++) {
            final float[] vertices = pageVerticesItems[i].items;
            final int vertexCount = pageVerticesItems[i].size;

            for (int v = 0; v < vertexCount; v += 5) {
                final int rgb = NumberUtils.floatToIntColor(vertices[v + 3]) & 0xFFFFFF;
                vertices[v + 3] = NumberUtils.intToFloatColor(alphaMask | rgb);
            }
        }
    }

    /**
     * Draw the contained text to the batch.
     * @param batch not null
     */
    public void draw (Batch batch) {
        final Array<Texture> pageTextures = this.pageTextures;
        final Array<FloatArray> pageVertices = this.pageVertices;
        final FloatArray[] pageVerticesItems = pageVertices.items;
        final Texture[] pageTextureItems = pageTextures.items;
        final int pageCount = pageTextures.size;

        for (int i = 0; i < pageCount; i++) {
            final FloatArray vertices = pageVerticesItems[i];
            if (vertices.size == 0) {
                continue;
            }

            batch.draw(pageTextureItems[i], vertices.items, 0, vertices.size);
        }
    }

    /** Removes all glyphs in the cache. */
    public void clear () {
        PAGE_VERTICES_POOL.freeAll(pageVertices);
        pageVertices.clear();
        pageTextures.clear();
    }

}