package com.badlogic.gdx.graphics.text.harfbuzz;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.text.Font;
import com.badlogic.gdx.graphics.text.Glyph;
import com.badlogic.gdx.graphics.text.GlyphLayout;

/**
 *
 */
public class HBFont implements Font<HBFont> {

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
    public HBFont getFallback() {
        return null;
    }

    @Override
    public GlyphLayout<HBFont> createGlyphLayout() {
        return null;
    }

    @Override
    public void dispose() {

    }
}
