package com.badlogic.gdx.graphics.text.harfbuzz;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.ImagePacker;
import com.badlogic.gdx.graphics.g2d.freetype.FreeType;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.text.FontSystem;
import com.badlogic.gdx.utils.*;

import java.io.InputStream;
import java.nio.ByteBuffer;

/** Font system based on FreeType and HarfBuzz.
 * More heavyweight than {@link BitmapFont}, but supports most Unicode features, including complex scripts,
 * such as Arabic. It can also generate glyphs at runtime, which is useful for scripts with large amounts of characters
 * (Chinese), and when the size of rendered text (or the pixel density of the screen) is not known in advance.
 *
 * Create only one instance per application.
 * Should be disposed when no longer needed.
 */
public class HBFontSystem implements FontSystem<HBGlyphLayout>, Disposable {

    private final FreeType.Library freeTypeLibrary;

    public HBFontSystem() {
        HarfBuzz.initialize();
        this.freeTypeLibrary = FreeType.initFreeType();
    }

    /**
     * Create incrementally built font of given dimensions.
     * Needed glyphs will be rasterized from the font on demand.
     *
     * @param fontFile font supported by TrueType, such as ttf or otf
     * @param size of the font (height), in arbitrary units that will be used for public HBGlyphLayout measures
     * @param pixelsPerPoint physical pixels per one unit of size. Measurements will be aligned to physical pixels to achieve
     *               pixel-perfect look.
     * @param parameters to be used by the font, may be null. Kept by the font, do not modify later!
     * @return created font
     */
    public HBFont createIncrementalFont(FileHandle fontFile, float size, float pixelsPerPoint, FontParameters parameters) {
        // TODO(jp): Api for creating multiple fonts from the same buffer with different sizes
        try {
            final ByteBuffer buffer;
            final InputStream input = fontFile.read();
            try {
                final int fileSize = (int) fontFile.length();
                if (fileSize == 0) {
                    // Copy to a byte[] to get the file size, then copy to the buffer.
                    byte[] data = StreamUtils.copyStreamToByteArray(input,
                            256 * 1024 /* 256kb, smaller fonts will fit,
                        professional ones can grow to couple MB, professional asian even to tens of MB. */);
                    buffer = BufferUtils.newUnsafeByteBuffer(data.length);
                    BufferUtils.copy(data, 0, buffer, data.length);
                } else {
                    // Trust the specified file size.
                    buffer = BufferUtils.newUnsafeByteBuffer(fileSize);
                    StreamUtils.copyStream(input, buffer);
                }
            } finally {
                StreamUtils.closeQuietly(input);
            }

            final FreeType.Face face = freeTypeLibrary.newMemoryFace(buffer, 0);

            if (parameters == null) {
                parameters = new FontParameters();
            }

            return new HBFont(freeTypeLibrary, face, size, pixelsPerPoint, parameters);// TODO(jp): Probably not all three size metrics are needed
        } catch (Exception e) {
            throw new GdxRuntimeException("Failed to load "+fontFile, e);
        }
    }

    @Override
    public HBGlyphLayout createGlyphLayout() {
        return new HBGlyphLayout();
    }

    /** Dispose this instance.
     * Call after all {@link HBFont}s created by it were disposed. */
    @Override
    public void dispose() {
        freeTypeLibrary.dispose();
    }

    /** Font smoothing algorithm. */
    public enum Hinting {
        /** Disable hinting. Generated glyphs will look blurry. */
        None,
        /** Light hinting with fuzzy edges, but close to the original shape */
        Slight,
        /** Average hinting */
        Medium,
        /** Strong hinting with crisp edges at the expense of shape fidelity */
        Full,
        /** Light hinting with fuzzy edges, but close to the original shape. Uses the FreeType auto-hinter. */
        AutoSlight,
        /** Average hinting. Uses the FreeType auto-hinter. */
        AutoMedium,
        /** Strong hinting with crisp edges at the expense of shape fidelity. Uses the FreeType auto-hinter. */
        AutoFull;

        public int toFreeTypeLoadFlags() {
            int loadingFlags = FreeType.FT_LOAD_DEFAULT;
            switch (this) {
                case None:
                    loadingFlags |= FreeType.FT_LOAD_NO_HINTING;
                    break;
                case Slight:
                    loadingFlags |= FreeType.FT_LOAD_TARGET_LIGHT;
                    break;
                case Medium:
                    loadingFlags |= FreeType.FT_LOAD_TARGET_NORMAL;
                    break;
                case Full:
                    loadingFlags |= FreeType.FT_LOAD_TARGET_MONO;
                    break;
                case AutoSlight:
                    loadingFlags |= FreeType.FT_LOAD_FORCE_AUTOHINT | FreeType.FT_LOAD_TARGET_LIGHT;
                    break;
                case AutoMedium:
                    loadingFlags |= FreeType.FT_LOAD_FORCE_AUTOHINT | FreeType.FT_LOAD_TARGET_NORMAL;
                    break;
                case AutoFull:
                    loadingFlags |= FreeType.FT_LOAD_FORCE_AUTOHINT | FreeType.FT_LOAD_TARGET_MONO;
                    break;
            }
            return loadingFlags;
        }
    }

    /** Details of how {@link HBFont} glyphs are generated.
     *
     * Based on {@link FreeTypeFontGenerator.FreeTypeFontParameter}. */
    public static class FontParameters {
        private static final Color SHADOW_COLOR = new Color(0, 0, 0, 0.75f);

        /** If true, font smoothing is disabled. */
        public boolean mono = false;
        /** Strength of hinting */
        public Hinting hinting = Hinting.AutoMedium;
        /** Foreground color (required for non-black borders) */
        public Color color = Color.WHITE;
        /** Glyph gamma. Values > 1 reduce antialiasing. */
        public float gamma = 1.8f;
        /** Number of times to render the glyph. Useful with a shadow or border, so it doesn't show through the glyph. */
        public int renderCount = 2;

        /** Border width in pixels, 0 to disable */
        public float borderWidth = 0;
        /** Border color; only used if borderWidth > 0 */
        public Color borderColor = Color.BLACK;
        /** true for straight (mitered), false for rounded borders */
        public boolean borderStraight = false;
        /** Values < 1 increase the border size. */
        public float borderGamma = 1.8f;

        /** Offset of text shadow on X axis in pixels, 0 to disable */
        public int shadowOffsetX = 0;
        /** Offset of text shadow on Y axis in pixels, 0 to disable */
        public int shadowOffsetY = 0;
        /** Shadow color; only used if shadowOffset > 0. If alpha component is 0, no shadow is drawn but characters are still offset
         * by shadowOffset. */
        public Color shadowColor = SHADOW_COLOR;

        /** Whether the font should use kerning (if there is any specified by the font) */
        public boolean kerning = true;

        /** Optional: PixmapPacker to use. This is useful when different than default parameters are needed
         * (see {@link #createDefaultImagePacker()}) or when it is necessary to pack multiple {@link HBFont}s
         * into a single Texture atlas. This can lead to better performance and memory usage, when you plan to use
         * few glyphs from many {@link HBFont} instances.
         * <p/>
         * Notes on parameters:
         * <ul>
         *     <li>Page size: does not have to be square, but should be power of two</li>
         *     <li>Texture filters: Use Nearest for typical UI crisp font rendering</li>
         *     <li>Transparent color: Should have 0 alpha and the color of the glyphs (or their border)</li>
         * </ul>
         *
         * If no packer is specified, each font instance will use own separate one.
         * When custom packer is specified, its creator is responsible for disposing it - default one is disposed
         * automatically with the font. */
        public ImagePacker packer = null;

        /** Create a default {@link ImagePacker} suitable for packing font glyphs.
         * Default parameters: pageSize = 2048, filters = Nearest. */
        public ImagePacker createDefaultImagePacker() {
            final ImagePacker packer = new ImagePacker(2048, 2048,
                    Pixmap.Format.RGBA8888, Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest,
                    1, false, ImagePacker.GuillotineStrategy.INSTANCE);

            if (borderWidth > 0) {
                packer.transparentColor.set(borderColor);
            } else {
                packer.transparentColor.set(color);
            }
            packer.transparentColor.a = 0f;
            return packer;
        }
    }
}
