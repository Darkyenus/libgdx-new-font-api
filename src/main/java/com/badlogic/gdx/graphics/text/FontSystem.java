package com.badlogic.gdx.graphics.text;

/**
 * Represents some configuration of font drawing.
 *
 * Subclasses should provide specific methods for font creation.
 *
 * Fonts created in this way MUST be compatible with {@link com.badlogic.gdx.graphics.text.GlyphLayout} created through the same font system.
 * <br>
 * <b>FontSystem and related objects are NOT THREAD SAFE and should be used FROM THE RENDER THREAD ONLY, unless specified otherwise.</b>
 */
public interface FontSystem <GlyphLayout extends com.badlogic.gdx.graphics.text.GlyphLayout> {

    /**
     * @return a new glyph layout for rendering fonts created by this font system
     */
    GlyphLayout createGlyphLayout();

}
