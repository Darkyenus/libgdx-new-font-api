package com.badlogic.gdx.graphics.g2d.freetype;

import com.badlogic.gdx.graphics.text.harfbuzz.HarfBuzz;

/**
 * To be used by {@link HarfBuzz} internally.
 */
public class HarfBuzzHelper {
    public static long addressOf(FreeType.Face face) {
        return face.address;
    }
}
