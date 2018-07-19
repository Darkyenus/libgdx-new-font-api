package com.badlogic.gdx.graphics.text;

/**
 * Single glyph of the {@link Font}.
 *
 * Serves as a mapping of glyph ID to region in Font's texture.
 *
 * FontSystem may extend this definition to carry additional internal information.
 * <br>
 * <strong>DO NOT MODIFY THE FIELDS</strong>, they are not final only to allow easier loading, not for runtime mutation.
 */
public class Glyph implements Comparable<Glyph> {

    /** This glyph should be rendered mirrored when the {@link GlyphRun} it is in is RTL. */
    public static final byte FLAG_MIRRORED = 1;

    /** Glyph ID this corresponds to. */
    public final int glyphId;
    /** Page on which this glyph is, or -1 if this glyph has no graphic representation. */
    public short page = -1;
    /** Additional properties of the glyph. Flags are "allocated" from least significant bit.
     * Subclasses may allocate from most significant bit for their own uses, to prevent conflicts in the future.
     * @see Glyph#FLAG_MIRRORED */
    public byte flags = 0;
    /** Texture coordinates on which this glyph appears on specified page. */
    public float u = 0f, v = 0f, u2 = 0f, v2 = 0f;

    /** Offsets from the pen point to the bottom left corner of the glyph rectangle.
     * Positive goes to the right and up from the pen point.
     * @see GlyphRun#glyphX and glyphY */
    public float xOffset, yOffset;
    /** Size of the glyph rectangle. */
    public float width, height;

    public Glyph(int glyphId) {
        this.glyphId = glyphId;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Glyph glyph = (Glyph) o;
        return glyphId == glyph.glyphId;
    }

    @Override
    public final int hashCode() {
        return glyphId;
    }

    @Override
    public String toString() {
        if (glyphId > ' ' && glyphId <= Character.MAX_VALUE) {
            return glyphId+" ('"+(char)glyphId+"')";
        } else {
            return Integer.toString(glyphId);
        }
    }

    @Override
    public int compareTo(Glyph o) {
        return Integer.compare(this.glyphId, o.glyphId);
    }
}
