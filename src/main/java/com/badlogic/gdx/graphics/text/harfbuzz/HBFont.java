package com.badlogic.gdx.graphics.text.harfbuzz;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.text.Font;
import com.badlogic.gdx.graphics.text.Glyph;
import com.badlogic.gdx.utils.Array;

/**
 *
 */
public class HBFont implements Font<HBGlyphLayout> {

    @Override
    public Texture[] getPages() {
        return null;
    }

    @Override
    public Glyph getGlyph(int glyphId) {
        return null;
    }

    @Override
    public void prepareGlyphs() {

    }

    @Override
    public float getSpaceXAdvance() {
        return 0;
    }

    @Override
    public float getLineHeight() {
        return 0;
    }

    @Override
    public void dispose() {

    }
}
