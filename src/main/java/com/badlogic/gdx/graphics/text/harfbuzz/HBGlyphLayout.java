package com.badlogic.gdx.graphics.text.harfbuzz;

import com.badlogic.gdx.graphics.text.*;
import com.badlogic.gdx.utils.*;

/**
 * Glyph layout for harf-buzz fonts.
 */
public class HBGlyphLayout extends GlyphLayout<HBGlyphLayout, HBFont> {

    // Cached instances
    private static final LayoutTextIterator<HBFont> textIterator = new LayoutTextIterator<>();
    private static final IntArray results = new IntArray();

    /*
    IDEAS:

    - Make rasterizing backend pluggable to allow easy caching/pre-rendering.
    - Make shaping pluggable to allow for simple and fast shaper for monospaced fonts and mainly GWT.
    - Do we allow vertical fonts?
    - TODO Stuff for editors (TextField...)
     */

    @Override
    public void layoutText(LayoutText<HBFont> text, float availableWidth, float availableHeight, int horizontalAlign, String elipsis) {
        clear();
        final int length = text.length();
        if (length <= 0) {
            return;
        }

        /*
        Text is split by different font regions, newlines (\n only) and tabulators (\t).
        Text is NOT split by different coloring, this is supplied later retroactively.
         */

        LayoutTextIterator<HBFont> textIterator = HBGlyphLayout.textIterator;
        textIterator.start(text);

        HarfBuzz.Buffer shapeBuffer = HarfBuzz.Buffer.create();//TODO allocation

        int runEnd = 0;//textIterator.index;
        while (runEnd < length) {
            int runStart = runEnd;
            HBFont font = null;//textIterator.toEndOfRun();
            assert font != null;
            //runEnd = textIterator.index;

            if (runStart == runEnd) {
                // 0-length run
                continue;
            }

            // Set flags and properties
            {
                int runFlags = HarfBuzz.Buffer.HB_BUFFER_FLAG_DEFAULT;
                if (runStart == 0) {
                    runFlags |= HarfBuzz.Buffer.HB_BUFFER_FLAG_BOT;
                }
                if (runEnd == length) {
                    runFlags |= HarfBuzz.Buffer.HB_BUFFER_FLAG_EOT;
                }
                shapeBuffer.setFlags(runFlags);
                shapeBuffer.setClusterLevel(HarfBuzz.Buffer.ClusterLevel.CHARACTERS);
            }

            // Add everything in the run.
            shapeBuffer.add(text.text(),
                    runStart, runEnd - runStart,
                    runStart, -1);

            // TODO BIDI algo
            shapeBuffer.guessSegmentProperties();

            // Shape with default features TODO Examine other possible features
            //font.shape(shapeBuffer, NO_FEATURES);TODO

            // Create run
            GlyphRun<HBFont> run = GlyphRun.<HBFont>pool().obtain();
            int shapedGlyphCount = shapeBuffer.getLength();
            run.ensureGlyphCapacity(shapedGlyphCount);

            final IntArray results = HBGlyphLayout.results;

            shapeBuffer.getGlyphInfos(results);
            assert shapedGlyphCount * 3 == results.size;
            for (int i = 0; i < results.size; i += 3) {
                run.glyphs.add(font.getGlyph(results.items[i]));
            }

            shapeBuffer.getGlyphPositions(results);
            assert shapedGlyphCount * 4 == results.size;
            int penX = 0;
            for (int i = 0; i < results.size; i += 4) {
                int advanceX = results.items[i];
                int advanceY = results.items[i + 1];
                assert advanceY == 0;// Should be non-0 only for vertical layout
                int offsetX = results.items[i + 2];
                int offsetY = results.items[i + 3];

                run.glyphX.add(penX + offsetX);
                run.glyphY.add(offsetY);

                penX += advanceX;
            }

            run.width = penX;
        }

        //TODO Line wrapping, \n and \t handling

        shapeBuffer.destroy();
        textIterator.end();
    }


}
