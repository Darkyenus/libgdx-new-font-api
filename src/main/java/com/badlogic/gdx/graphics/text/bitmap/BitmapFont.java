package com.badlogic.gdx.graphics.text.bitmap;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.text.Font;
import com.badlogic.gdx.graphics.text.Glyph;
import com.badlogic.gdx.graphics.text.GlyphLayout;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.LongArray;
import com.badlogic.gdx.utils.StreamUtils;

import java.io.BufferedReader;
import java.util.Arrays;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple 1:1 char-glyph font loaded from
 * <a href="http://www.angelcode.com/products/bmfont/doc/file_format.html">textual .fnt file</a>.
 */
public class BitmapFont implements Font<BitmapFont> {

    private final String name;
    private final BitmapFont fallback;

    static private final byte STATE_INITIAL = 0;
    static private final byte STATE_GLYPHS_LOADED = 1;
    static private final byte STATE_INITIALIZED_OWNS_PAGES = 2;
    static private final byte STATE_INITIALIZED_BORROWED_PAGES = 3;
    static private final byte STATE_DISPOSED = 4;
    private byte state = STATE_INITIAL;

    private final Array<Texture> pages = new Array<>(true, 5, Texture.class);

    /** The distance from one line of text to the next. */
    public float lineHeight;
    /** Distance from top of the drawing area to baseline. */
    public float base;
    /** The x-advance of the space character. Used for all unknown whitespace characters. */
    public float spaceXAdvance;

    /** Glyphs ordered by glyphId. */
    private final Array<BitmapGlyph> glyphs = new Array<>(true, 64, BitmapGlyph.class);

    /** Lazily created, ordered, packed kerning data.
     * As codepoints are at max 21 bits wide, values can be packed as follows:
     * 0 bit, 21 bits of first codepoint, 21 bits of second codepoint, 21 bits of kerning.
     * One 0 bit is used at the start to prevent problems when comparing signed values.
     * Codepoint bits are unsigned, kerning bits are signed. Kerning usually fits into 8 signed bits, so we have plenty
     * space to spare.
     * This format is used to make binary searching easy and to be space efficient. */
    private LongArray kerning = null;

    private static final long MASK_21BITS = 0x1F_FFFFL;

    /**
     * @param name of the font, for debug
     * @param fallback font, or null
     */
    public BitmapFont(String name, BitmapFont fallback) {
        this.name = name;
        this.fallback = fallback;
    }

    private long packKerning(int first, int second, int clampedKerning) {
        return ((first & MASK_21BITS) << 42) | ((second & MASK_21BITS) << 21) | clampedKerning;
    }

    private void addKerning(int firstGlyph, int secondGlyph, int amount) {
        amount &= 0x1F_FFFF;// It is unlikely that this would modify the amount in any way, but is needed for correctness
        if (amount == 0) {
            // Retrieval assumes, that 0-amount kernings are not stored
            return;
        }
        LongArray kerning = this.kerning;
        if (kerning == null) {
            kerning = this.kerning = new LongArray(true, 64);
        }
        final long key = packKerning(firstGlyph, secondGlyph, amount);
        int insertPoint = Arrays.binarySearch(kerning.items, 0, kerning.size, key);
        if (insertPoint >= 0) {
            // Duplicate, including the same value. We don't try to detect duplicates otherwise.
            return;
        }
        insertPoint = -insertPoint - 1;
        kerning.insert(insertPoint, key);
    }

    public int getKerning(int firstGlyph, int secondGlyph) {
        final LongArray kerning = this.kerning;
        if (kerning == null) {
            return 0;
        }
        final long key = packKerning(firstGlyph, secondGlyph, 0);
        // As kerning may never be 0, we can safely assume, that returned index is always negative
        final int index = -Arrays.binarySearch(kerning.items, 0, kerning.size, key) -1;
        if (index < 0 || index >= kerning.size) {
            return 0;
        }
        final long storedKey = kerning.items[index];
        if ((storedKey ^ key) > MASK_21BITS) {
            // Stored key relates to a different glyph pair, ignore it
            return 0;
        }
        // Sign-extend and return
        return (int) ((storedKey << (64 - 21)) >> (64 - 21));
    }

    /**
     * Load glyphs for this font.
     * Must be called (only) once per font initialization.
     *
     * First part of the font initialization, convert resulting glyph paths to {@link TextureRegion}s and
     * call {@link #loadPages(TextureRegion[], boolean)} to finish font initialization.
     *
     * @param fontFile containing text data of the font
     * @param pixelsPerPoint for the context in which this font will be used.
     *                       .fnt files are in pixels, drawing units are points.
     *                       Standard is 1f, classic Apple Retina is 2f, etc.
     * @return page paths
     */
    public String[] loadGlyphs (FileHandle fontFile, float pixelsPerPoint) {
        if (state != STATE_INITIAL) {
            throw new GdxRuntimeException("Glyphs already loaded");
        }

        final float scale = 1f / pixelsPerPoint;

        final BufferedReader reader = fontFile.reader(1024, "UTF-8");
        try {
            String line = reader.readLine(); // info
            if (line == null) throw new GdxRuntimeException("File is empty");
            line = reader.readLine();
            if (line == null) throw new GdxRuntimeException("Missing common header");
            String[] common = line.split(" ", 7); // At most we want the 6th element; i.e. "page=N"

            // At least lineHeight and base are required.
            if (common.length < 3) throw new GdxRuntimeException("Invalid common header");

            if (!common[1].startsWith("lineHeight=")) throw new GdxRuntimeException("Missing: lineHeight");
            lineHeight = Integer.parseInt(common[1].substring(11)) * scale;

            if (!common[2].startsWith("base=")) throw new GdxRuntimeException("Missing: base");
            final float base = this.base = Integer.parseInt(common[2].substring(5)) * scale;

            int pageCount = 1;
            if (common.length >= 6 && common[5] != null && common[5].startsWith("pages=")) {
                try {
                    pageCount = Math.max(1, Integer.parseInt(common[5].substring(6)));
                } catch (NumberFormatException ignored) { // Use one page.
                }
            }

            pages.ensureCapacity(pageCount);
            final Pattern idPattern = Pattern.compile(".*id=(\\d+)");
            final Pattern filePattern = Pattern.compile(".*file=\"?([^\"]+)\"?");

            String[] pagePaths = new String[pageCount];

            // Read each page definition.
            for (int p = 0; p < pageCount; p++) {
                // Read each "page" info line.
                line = reader.readLine();
                if (line == null) throw new GdxRuntimeException("Missing additional page definitions");

                // Expect ID to mean "index".
                Matcher matcher = idPattern.matcher(line);
                if (matcher.find()) {
                    String id = matcher.group(1);
                    try {
                        int pageID = Integer.parseInt(id);
                        if (pageID != p) throw new GdxRuntimeException("Page IDs must be indices starting at 0: " + id);
                    } catch (NumberFormatException ex) {
                        throw new GdxRuntimeException("Invalid page id: " + id, ex);
                    }
                }

                matcher = filePattern.matcher(line);
                if (!matcher.find()) throw new GdxRuntimeException("Missing: file");

                pagePaths[p] = matcher.group(1);
            }

            final Array<BitmapGlyph> glyphs = this.glyphs;
            float fallbackXAdvance = -1f;
            while (true) {
                line = reader.readLine();
                if (line == null) break; // EOF
                if (line.startsWith("kernings ")) break; // Starting kernings block.
                if (!line.startsWith("char ")) continue;

                StringTokenizer tokens = new StringTokenizer(line, " =");
                tokens.nextToken();
                tokens.nextToken();
                final int glyphId = Integer.parseInt(tokens.nextToken());
                if (glyphId < Character.MIN_CODE_POINT || glyphId > Character.MAX_CODE_POINT) continue;
                tokens.nextToken();
                final short srcX = Short.parseShort(tokens.nextToken());
                tokens.nextToken();
                final short srcY = Short.parseShort(tokens.nextToken());
                tokens.nextToken();
                final short srcWidth = Short.parseShort(tokens.nextToken());
                tokens.nextToken();
                final short srcHeight = Short.parseShort(tokens.nextToken());
                tokens.nextToken();
                final float xOffset = Integer.parseInt(tokens.nextToken()) * scale;
                tokens.nextToken();
                final float yOffset = Integer.parseInt(tokens.nextToken()) * scale;
                tokens.nextToken();
                final float xAdvance = Integer.parseInt(tokens.nextToken()) * scale;

                if (fallbackXAdvance < 0f && xAdvance > 0f) {
                    fallbackXAdvance = xAdvance;
                }

                int page = 0;
                // Check for page safely, it could be omitted or invalid.
                if (tokens.hasMoreTokens()) tokens.nextToken();
                if (tokens.hasMoreTokens()) {
                    try {
                        page = Integer.parseInt(tokens.nextToken());
                    } catch (NumberFormatException ignored) {
                    }
                }

                final float width = srcWidth * scale;
                final float height = srcHeight * scale;

                // .fnt counts yOffset from top of the line to the top edge of rectangle,
                // but we count it from baseline to the bottom edge of rectangle
                final BitmapGlyph glyph = new BitmapGlyph(
                        glyphId, page,
                        srcX, srcY, srcWidth, srcHeight,
                        xOffset, base - yOffset - height,
                        width, height, xAdvance);

                int insertIndex = Arrays.binarySearch(glyphs.items, 0, glyphs.size, glyph);
                if (insertIndex >= 0) {
                    // Duplicate index, ignore
                    continue;
                }
                insertIndex = -insertIndex - 1;

                glyphs.insert(insertIndex, glyph);
            }

            while (true) {
                line = reader.readLine();
                if (line == null) break;
                if (!line.startsWith("kerning ")) break;

                StringTokenizer tokens = new StringTokenizer(line, " =");
                tokens.nextToken();
                tokens.nextToken();
                int first = Integer.parseInt(tokens.nextToken());
                tokens.nextToken();
                int second = Integer.parseInt(tokens.nextToken());
                if (first < Character.MIN_CODE_POINT || first > Character.MAX_CODE_POINT
                        || second < Character.MIN_CODE_POINT || second > Character.MAX_CODE_POINT) continue;
                tokens.nextToken();
                int amount = Integer.parseInt(tokens.nextToken());
                addKerning(first, second, amount);
            }

            BitmapGlyph spaceGlyph = getGlyph(' ');
            if (spaceGlyph != null) {
                spaceXAdvance = spaceGlyph.xAdvance;
            } else if (fallbackXAdvance != -1) {
                spaceXAdvance = fallbackXAdvance;
            } else {
                spaceXAdvance = lineHeight * 4 / 3;
            }

            state = STATE_GLYPHS_LOADED;
            return pagePaths;
        } catch (Exception ex) {
            throw new GdxRuntimeException("Error loading font file: " + fontFile, ex);
        } finally {
            StreamUtils.closeQuietly(reader);
        }
    }

    /**
     * @param textures corresponding to paths from {@link #loadGlyphs}, can handle {@link TextureAtlas.AtlasRegion}
     * @param ownedByFont true = this BitmapFont should dispose these textures, false = they are disposed by someone else
     */
    public void loadPages(TextureRegion[] textures, boolean ownedByFont) {
        if (state != STATE_GLYPHS_LOADED) {
            throw new GdxRuntimeException("Glyphs already loaded");
        }
        for (TextureRegion region : textures) {
            pages.add(region.getTexture());
        }
        if (ownedByFont) {
            state = STATE_INITIALIZED_OWNS_PAGES;
        } else {
            state = STATE_INITIALIZED_BORROWED_PAGES;
        }

        for (BitmapGlyph glyph : glyphs) {
            if (glyph == null || glyph.page == -1) continue;

            final TextureRegion region = textures[glyph.page];
            final Texture texture = region.getTexture();

            float invTexWidth = 1.0f / texture.getWidth();
            float invTexHeight = 1.0f / texture.getHeight();

            float offsetX = 0, offsetY = 0;
            float u = region.getU();
            float v = region.getV();
            final int regionWidth = region.getRegionWidth();
            final int regionHeight = region.getRegionHeight();
            if (region instanceof TextureAtlas.AtlasRegion) {
                // Compensate for whitespace stripped from left and top edges.
                TextureAtlas.AtlasRegion atlasRegion = (TextureAtlas.AtlasRegion)region;
                offsetX = atlasRegion.offsetX;
                offsetY = atlasRegion.originalHeight - atlasRegion.packedHeight - atlasRegion.offsetY;
            }
            // TODO CACHE PREVIOUS STUFF?

            int x = glyph.srcX;
            int x2 = glyph.srcX + glyph.srcWidth;
            int y = glyph.srcY;
            int y2 = glyph.srcY + glyph.srcHeight;

            // Shift glyph for left and top edge stripped whitespace. Clip glyph for right and bottom edge stripped whitespace.
            if (offsetX > 0) {
                x -= offsetX;
                x2 -= offsetX;
                if (x < 0) {
                    glyph.width += x;
                    glyph.xOffset -= x;
                    x = 0;
                }
                if (x2 > regionWidth) {
                    glyph.width -= x2 - regionWidth;
                    x2 = regionWidth;
                }
            }
            if (offsetY > 0) {
                y -= offsetY;
                y2 -= offsetY;
                if (y < 0) {
                    glyph.height += y;
                    y = 0;
                }
                if (y2 > regionHeight) {
                    int amount = y2 - regionHeight;
                    glyph.height -= amount;
                    glyph.yOffset += amount;
                    y2 = regionHeight;
                }
            }

            if (x == x2 || y == y2) {
                // No reason to render glyph without graphics
                glyph.page = -1;
            } else {
                glyph.u = u + x * invTexWidth;
                glyph.u2 = u + x2 * invTexWidth;
                glyph.v = v + y2 * invTexHeight;
                glyph.v2 = v + y * invTexHeight;
            }
        }
    }

    @Override
    public Texture[] getPages() {
        // Check if font is properly initialized. This is not called often, so it shouldn't add any overhead.
        if (state != STATE_INITIALIZED_OWNS_PAGES && state != STATE_INITIALIZED_BORROWED_PAGES) {
            throw new GdxRuntimeException("Font not initialized");
        }
        return pages.items;
    }

    @Override
    public BitmapGlyph getGlyph(int glyphId) {
        final Array<BitmapGlyph> glyphs = this.glyphs;
        final BitmapGlyph[] items = glyphs.items;

        // Binary search, based on Arrays.binarySearch, because of incompatible types (BitmapGlyph searched by glyphId)
        int low = 0;
        int high = glyphs.size - 1;

        while (low <= high) {
            final int mid = (low + high) >>> 1;
            final int midVal = items[mid].glyphId;

            if (midVal < glyphId)
                low = mid + 1;
            else if (midVal > glyphId)
                high = mid - 1;
            else
                return items[mid];
        }

        return null;
    }

    @Override
    public void prepareGlyphs() {
        // no-op, all glyphs are already preloaded
    }

    @Override
    public BitmapFont getFallback() {
        return fallback;
    }

    @Override
    public GlyphLayout<BitmapFont> createGlyphLayout() {
        return BitmapFontSystem.INSTANCE.createGlyphLayout();
    }

    @Override
    public void dispose() {
        if (state == STATE_INITIALIZED_OWNS_PAGES) {
            for (Texture page : pages) {
                page.dispose();
            }
        }
        pages.clear();
        state = STATE_DISPOSED;
    }

    @Override
    public String toString() {
        return name;
    }

    public static final class BitmapGlyph extends Glyph {

        /** Position in source texture, as defined in fnt file. */
        final short srcX, srcY, srcWidth, srcHeight;

        /** Layout related information: x-amount by which the pen point should move after writing this glyph */
        public final float xAdvance;

        BitmapGlyph(int glyphId, int page,
                    short srcX, short srcY, short srcWidth, short srcHeight,
                    float xOffset, float yOffset, float width, float height, float xAdvance) {
            super(glyphId);
            this.page = page;
            this.srcX = srcX;
            this.srcY = srcY;
            this.srcWidth = srcWidth;
            this.srcHeight = srcHeight;
            this.width = width;
            this.height = height;
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.xAdvance = xAdvance;
        }
    }
}
