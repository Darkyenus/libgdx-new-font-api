package com.badlogic.gdx.graphics.text.harfbuzz;

import com.badlogic.gdx.graphics.text.GlyphLayout;
import com.badlogic.gdx.graphics.text.GlyphRun;
import com.badlogic.gdx.graphics.text.LayoutText;
import com.badlogic.gdx.graphics.text.LayoutTextRunArray;
import com.badlogic.gdx.graphics.text.LayoutTextRunArray.TextRun;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;

import java.text.Bidi;
import java.util.Arrays;

import static com.badlogic.gdx.graphics.text.harfbuzz.HarfBuzz.Font.NO_FEATURES;

/**
 * Glyph layout for harf-buzz fonts.
 */
public class HBGlyphLayout extends GlyphLayout<HBFont> {

    private float startX;

    /** Borrowed by BitmapGlyphLayout instances when they are doing glyph layout, to store all fonts in the layout. */
    private static final Array<HBFont> usedFonts = new Array<>(true, 10, HBFont.class);

    private void addLineHeight(float height) {
        lineHeights.add(getHeight() + height);
    }

    private void addLinebreakRunFor(final TextRun<HBFont> textRun, final int line) {
        final int runStart = textRun.start;
        final int runEnd = textRun.end;
        assert runStart < runEnd;

        final GlyphRun<HBFont> run = GlyphRun.obtain(false);

        // Linebreak is represented always as a single character position
        final float[] characterPositions = run.characterPositions.ensureCapacity(runEnd - runStart);
        run.characterPositions.size = runEnd - runStart;
        characterPositions[0] = 0f;
        for (int i = 1; i < runEnd - runStart; i++) {
            characterPositions[i] = Float.NaN;
        }

        // Linebreak never has glyphs, nor width
        run.x = startX; // Y set later
        run.width = 0f;
        run.line = line;
        run.font = textRun.font;
        run.color = textRun.color;
        run.charactersStart = runStart;
        run.charactersEnd = runEnd;
        run.characterFlags |= GlyphRun.FLAG_LINEBREAK;
        runs.add(run);
    }

    private void addTabStopRunFor(final LayoutText<HBFont> text, final TextRun<HBFont> textRun, final int line) {
        final int runStart = textRun.start;
        final int runEnd = textRun.end;
        assert runStart < runEnd;

        final GlyphRun<HBFont> run = GlyphRun.obtain(false);

        // Tab stop is represented always as a single character position
        final float[] characterPositions = run.characterPositions.ensureCapacity(runEnd - runStart);
        run.characterPositions.size = runEnd - runStart;
        characterPositions[0] = 0f;
        for (int i = 1; i < runEnd - runStart; i++) {
            // Unlikely to ever happen
            characterPositions[i] = Float.NaN;
        }

        // Tab never has glyphs, only width
        final float defaultTabAdvance = textRun.font.spaceXAdvance * 8f;
        final int tabIndex = text.tabStopIndexFor(startX, defaultTabAdvance);

        run.x = startX; // Y set later
        run.line = line;
        run.font = textRun.font;
        run.color = textRun.color;
        run.charactersStart = runStart;
        run.charactersEnd = runEnd;

        run.characterFlags |= GlyphRun.FLAG_TAB;
        if (tabIndex == -1) {
            // Ignore it
            run.width = 0f;
        } else {
            final float offset = text.tabStopOffsetFor(tabIndex, defaultTabAdvance);
            run.width = offset - run.x;
            startX += run.width;
        }
        runs.add(run);
    }

    private static final IntArray addRunsForTextRuns_glyphInfo = new IntArray();
    private static final IntArray addRunsForTextRuns_glyphPositions = new IntArray();

    private void addRunsForTextRuns(LayoutText<HBFont> text, LayoutTextRunArray<HBFont> textRuns, int start, int end, int line, boolean paragraphBegin, boolean paragraphEnd) {
        final HarfBuzz.Buffer shapeBuffer = HarfBuzz.Buffer.create();//TODO allocation

        // Set flags and properties
        {
            shapeBuffer.setContentType(HarfBuzz.Buffer.ContentType.UNICODE);
            shapeBuffer.setClusterLevel(HarfBuzz.Buffer.ClusterLevel.MONOTONE_CHARACTERS);

            int runFlags = HarfBuzz.Buffer.HB_BUFFER_FLAG_DEFAULT;
            if (paragraphBegin) {
                runFlags |= HarfBuzz.Buffer.HB_BUFFER_FLAG_BOT;
            }
            if (paragraphEnd) {
                runFlags |= HarfBuzz.Buffer.HB_BUFFER_FLAG_EOT;
            }
            shapeBuffer.setFlags(runFlags);
        }

        final TextRun<HBFont> firstTextRun = textRuns.get(start);

        // Add the text to the buffer
        {
            int i = start;
            TextRun<HBFont> textRun = firstTextRun;
            while (true) {
                shapeBuffer.add(text.text(),
                        0, text.length(),
                        textRun.start, textRun.end - textRun.start);

                if (++i >= end) {
                    break;
                }
                textRun = textRuns.get(i);
            }
        }

        shapeBuffer.guessSegmentProperties();
        shapeBuffer.setDirection(TextRun.isLevelLtr(firstTextRun.level) ? HarfBuzz.Direction.LTR : HarfBuzz.Direction.RTL);

        // Shape with default features
        firstTextRun.font.hbFont.shape(shapeBuffer, NO_FEATURES);// TODO(jp): Examine what features can/should we use

        // Create runs
        final int shapedGlyphCount = shapeBuffer.getLength();

        final IntArray glyphInfoArray = HBGlyphLayout.addRunsForTextRuns_glyphInfo;
        shapeBuffer.getGlyphInfos(glyphInfoArray);
        assert shapedGlyphCount * 3 == glyphInfoArray.size;
        final int[] glyphInfo = glyphInfoArray.items;

        final IntArray glyphPositionsArray = HBGlyphLayout.addRunsForTextRuns_glyphPositions;
        shapeBuffer.getGlyphPositions(glyphPositionsArray);
        assert shapedGlyphCount * 4 == glyphPositionsArray.size;
        final int[] glyphPositions = glyphPositionsArray.items;

        TextRun<HBFont> currentTextRun = firstTextRun;
        int currentTextRunIndex = start;
        GlyphRun<HBFont> currentGlyphRun = GlyphRun.obtain(true);
        runs.add(currentGlyphRun);
        currentGlyphRun.x = startX;
        currentGlyphRun.line = line;
        currentGlyphRun.font = currentTextRun.font;
        currentGlyphRun.color = currentTextRun.color;
        currentGlyphRun.charactersLevel = currentTextRun.level;
        currentGlyphRun.charactersStart = currentTextRun.start;
        currentGlyphRun.charactersEnd = currentTextRun.end;
        float[] characterPositions = currentGlyphRun.characterPositions.ensureCapacity(currentTextRun.end - currentTextRun.start);
        currentGlyphRun.characterPositions.size = currentTextRun.end - currentTextRun.start;

        float penX = 0f;
        int c = 0;
        for (int i = 0, gi = 0, gp = 0; i < shapedGlyphCount; i++, gi += 3, gp += 4) {
            final int glyphId = glyphInfo[gi];
            final int glyphFlags = glyphInfo[gi + 1];
            final int originalIndex = glyphInfo[gi + 2];

            final int xAdvance = glyphPositions[gp]; // TODO(jp): * densityScale?
            final int xOffset = glyphPositions[gp + 2];
            final int yOffset = glyphPositions[gp + 3];

            while (originalIndex >= currentTextRun.end) {
                currentGlyphRun.width = penX;
                startX += penX;
                penX = 0f;

                while (c < currentGlyphRun.characterPositions.size) {
                    characterPositions[c++] = Float.NaN;
                }

                currentTextRunIndex++;
                assert currentTextRunIndex < end;
                currentTextRun = textRuns.get(currentTextRunIndex);
                currentGlyphRun = GlyphRun.obtain(true);
                runs.add(currentGlyphRun);
                currentGlyphRun.x = startX;
                currentGlyphRun.line = line;
                currentGlyphRun.font = currentTextRun.font;
                currentGlyphRun.color = currentTextRun.color;
                currentGlyphRun.charactersLevel = currentTextRun.level;
                currentGlyphRun.charactersStart = currentTextRun.start;
                currentGlyphRun.charactersEnd = currentTextRun.end;
                characterPositions = currentGlyphRun.characterPositions.ensureCapacity(currentTextRun.end - currentTextRun.start);
                currentGlyphRun.characterPositions.size = currentTextRun.end - currentTextRun.start;
            }

            penX += xAdvance;

            currentGlyphRun.createCheckpoint(originalIndex, currentGlyphRun.glyphs.size);

            currentGlyphRun.glyphs.add(currentTextRun.font.getGlyph(glyphId));
            currentGlyphRun.glyphX.add(penX + xOffset);
            currentGlyphRun.glyphY.add(yOffset);

            final int newC = originalIndex - currentTextRun.start;
            characterPositions[c++] = penX;
            while (c < newC) {
                characterPositions[c++] = Float.NaN;
            }
        }

        currentGlyphRun.width = penX;
        while (c < currentGlyphRun.characterPositions.size) {
            characterPositions[c++] = Float.NaN;
        }
        startX += penX;

        shapeBuffer.destroy();
    }

    /** Reorders run according to BiDi algorithm, computes line height, sets Y of runs on the line,
     * and adjusts variables for next line. */
    private void completeLine(final LayoutText<HBFont> text, final int runsStart, final int runsEnd,
                              final HBFont defaultFont) {
        assert runsStart <= runsEnd : runsStart + " <= "+ runsEnd;
        final Array<GlyphRun<HBFont>> runs = this.runs;

        // Find out how the runs need to be reordered
        reordering:
        {
            if (runsStart + 1 >= runsEnd) {
                // Only one run, no need to reorder
                break reordering;
            }

            // Analyze if reordering is needed
            byte and = ~0;
            byte or = 0;
            for (int i = runsStart; i < runsEnd; i++) {
                final GlyphRun<HBFont> run = runs.items[i];
                if ((run.characterFlags & (GlyphRun.FLAG_LINEBREAK | GlyphRun.FLAG_TAB)) != 0) {
                    // Reset level of this to paragraph level
                    run.charactersLevel = (byte) (text.isLeftToRight() ? 0 : 1);
                }

                and &= run.charactersLevel;
                or |= run.charactersLevel;
            }

            if ((or & 1) == 0) {
                // All levels are LTR, no need to reorder
                break reordering;
            }
            if ((and & 1) == 1) {
                // All levels are RTL, simple all-reorder
                reverse(runs.items, runsStart, runsEnd);
            } else {
                // Full bidi reordering needed
                final byte[] levels = bidiLevelsFor(runs.items, runsStart, runsEnd);
                Bidi.reorderVisually(levels, 0, runs.items, runsStart, runsEnd - runsStart);
            }

            // Reorder runs on X
            float x = 0f;
            for (int i = runsStart; i < runsEnd; i++) {
                final GlyphRun<HBFont> run = runs.items[i];
                run.x = x;
                x += run.width;
            }
        }

        // Find out, which fonts are on this line
        final Array<HBFont> fonts = usedFonts;
        int usedFontsOnLineFrom = fonts.size;
        for (int i = runsStart; i < runsEnd; i++) {
            final GlyphRun<HBFont> run = runs.items[i];
            if (run.glyphs.size == 0) {
                continue;
            }

            final HBFont font = run.font;
            final int fontIndex = fonts.indexOf(font, true);
            if (fontIndex < 0) {
                fonts.add(font);
            } else if (fontIndex < usedFontsOnLineFrom) {
                usedFontsOnLineFrom--;
                fonts.swap(fontIndex, usedFontsOnLineFrom);
            }// else: already good
        }

        // Go through fonts on line and check their max extend from top to baseline and from baseline down
        float topToBaseline = 0f;
        float baselineToDown = 0f;
        if (usedFontsOnLineFrom == fonts.size) {
            // No fonts on line, use only current run font
            topToBaseline = defaultFont.base;
            baselineToDown = defaultFont.lineHeight - defaultFont.base;
        } else {
            for (int i = usedFontsOnLineFrom; i < fonts.size; i++) {
                final HBFont f = fonts.items[i];
                topToBaseline = Math.max(topToBaseline, f.base);
                baselineToDown = Math.max(baselineToDown, f.lineHeight - f.base);
            }
        }

        final int line = lineHeights.size;
        final float lineStartY = -getHeight();

        // Shift each run so that it shares common baseline with all fonts on line
        GlyphRun<HBFont> run;
        final float finalLineHeight = topToBaseline + baselineToDown;
        for (int i = runsStart; i < runsEnd; i++) {
            run = runs.items[i];
            assert run.line == line;

            final float fontBase = run.font.base;
            run.y = lineStartY - topToBaseline + fontBase;

            // Fill the glyphY here
            // (it is always one constant value, based on the font, and doing it earlier may lead to copying around
            // extra values when wrapping)
            final int glyphCount = run.glyphX.size;
            Arrays.fill(run.glyphY.ensureCapacity(glyphCount), 0, glyphCount, -fontBase);
            run.glyphY.size = glyphCount;
        }

        addLineHeight(finalLineHeight);

        startX = 0f;
    }

    private static int findRunGroupEnd(LayoutTextRunArray<HBFont> textRuns, int start) {
        final TextRun<HBFont> first = textRuns.get(start);
        int end = start + 1;
        while (end < textRuns.size) {
            final TextRun<HBFont> next = textRuns.get(end);
            if (first.level != next.level
                    || first.font != next.font
                    || (next.flags & (TextRun.FLAG_TAB_STOP | TextRun.FLAG_LINE_BREAK)) != 0) {
                break;
            }
            end++;
        }
        return end;
    }

    @Override
    protected void doLayoutText(LayoutText<HBFont> text, LayoutTextRunArray<HBFont> textRuns, float availableWidth, float availableHeight, int maxLines, String ellipsis) {
        final Array<GlyphRun<HBFont>> runs = this.runs;

        int line = 0;
        int lineLaidRuns = 0;

        boolean clampLines = false;

        if (textRuns.size <= 0) {
            // At least one line must be always present, even if there is no text run
            addLineHeight(text.fontAt(0).lineHeight);
            return;
        }

        int textRunGroupStart = 0;
        boolean paragraphStart = true;
        doForTextRuns:
        do {
            final TextRun<HBFont> firstTextRun = textRuns.items[textRunGroupStart];
            final int textRunGroupEnd;

            final int flags = firstTextRun.flags;
            boolean linebreak = (flags & TextRun.FLAG_LINE_BREAK) != 0;

            if (linebreak) {
                addLinebreakRunFor(firstTextRun, line);
                paragraphStart = true;
                textRunGroupEnd = textRunGroupStart + 1;
            } else if ((firstTextRun.flags & TextRun.FLAG_TAB_STOP) != 0) {
                addTabStopRunFor(text, firstTextRun, line);
                textRunGroupEnd = textRunGroupStart + 1;
            } else {
                textRunGroupEnd = findRunGroupEnd(textRuns, textRunGroupStart);
                addRunsForTextRuns(text, textRuns, textRunGroupStart, textRunGroupEnd, line,
                        paragraphStart,
                        textRunGroupEnd >= textRuns.size || (textRuns.get(textRunGroupEnd - 1).flags & TextRun.FLAG_LINE_BREAK) != 0
                        );
                paragraphStart = false;
            }
            final boolean lastTextRun = textRunGroupEnd >= textRuns.size;

            // Wrapping
            /*wrapping:
            while (startX >= availableWidth) {
                assert lineLaidRuns < runs.size;

                if (line + 1 >= maxLines) {
                    // Wrapping this is unnecessary, as it will be ellipsized later.
                    clampLines = true;
                    break doForTextRuns;
                }

                // Find character index from which to wrap
                int runIndexOfWrapPoint = runs.size - 1;
                for (int i = lineLaidRuns; i < runs.size - 1; i++) {
                    // TODO(jp): optimize with binary search?
                    final GlyphRun<HBFont> run = runs.items[i];
                    if (run.x + run.width >= availableWidth) {
                        // Wrap this one
                        runIndexOfWrapPoint = i;
                        break;
                    }
                }

                // Find wrap character index inside that run
                final GlyphRun<HBFont> wrapPointRun = runs.items[runIndexOfWrapPoint];
                final int wrapPointCharacterIndex = charEndIndexForTargetRunWidth(wrapPointRun, availableWidth - wrapPointRun.x);

                // Find suitable breaking point
                final int lineCharactersStart = runs.items[lineLaidRuns].charactersStart;
                final int lineCharactersEnd = runs.items[runs.size - 1].charactersEnd;
                final int wrapIndex = findWrapPointFor(text, lineCharactersStart, lineCharactersEnd, wrapPointCharacterIndex);

                // There may be some whitespace after the wrap index, which is not wrapped to the new line, but rather
                // shrunk to 0-width on the last line. This is the index, where such whitespace ends (it starts at wrapIndex).
                int realWrapIndex = wrapIndex;
                // Collapse any spaces at the end of the line
                while (realWrapIndex < lineCharactersEnd && chars[realWrapIndex] == COLLAPSIBLE_SPACE) {
                    realWrapIndex++;
                }

                // Do the actual wrapping
                // From wrapIndex to realWrapIndex collapse spaces and move everything that remains to next line
                // for the normal line-ending code to handle it

                // Find where actual split happens and split it there
                final int splitRunIndex = runIndexWithCharIndex(lineLaidRuns, realWrapIndex);
                int firstRunOnWrappedLineIndex = splitRunForWrap(chars, splitRunIndex, realWrapIndex);

                final boolean wrappedBecauseOfCollapsedText = firstRunOnWrappedLineIndex == runs.size;
                // When collapsed spaces end with a newline, it won't be moved to a next line,
                // as a normal letter would do, but it will stay on this line. (This applies only to the first newline.)
                // (This is what happens when editing text on Mac and presumably on other OSes too)

                // wrappedBecauseOfCollapsedText is true when the currently laid text ends with collapsible space,
                // which makes it go over the width, but can be collapsed so it doesn't. Now three things can happen:
                // 1. Normal text run goes after this, in which case it should start on a new line
                //      -> We defer the collapsing and wrapping logic to the next iteration and bail
                // 2. Newline goes after this - this newline should still be part of this line
                //      -> Same as case 1, but the collapsing logic has a special case for this
                // 3. This is the last line
                //      -> We have to do the wrapping now, because there is no other iteration to defer it to,
                //         but we won't increment the line variable, because the cursor should stay on this collapsed line

                if (wrappedBecauseOfCollapsedText) {
                    if (!lastTextRun) {
                        break wrapping;
                    }
                } else {
                    final GlyphRun<HBFont> linebreakRun = runs.items[firstRunOnWrappedLineIndex];
                    if ((linebreakRun.characterFlags & GlyphRun.FLAG_LINEBREAK) != 0) {
                        // This is the special case mentioned in situation 2,
                        // we leave the newline together with the collapsed chars

                        // This special case is really only expected to happen after linebreak has been just added.
                        assert firstRunOnWrappedLineIndex + 1 == runs.size;
                        assert linebreak;

                        firstRunOnWrappedLineIndex++;
                        //realWrapIndex = linebreakRun.charactersEnd;
                        // (above line commented out because realWrapIndex is no longer used after this,
                        // but technically that is what happens)
                    }
                }

                // Collapse everything between wrapIndex and firstRunOnWrappedLineIndex run
                int collapseRunIndex = runIndexWithCharIndex(lineLaidRuns, wrapIndex);
                if (collapseRunIndex < firstRunOnWrappedLineIndex) {
                    // There is something to truncate
                    GlyphRun<HBFont> collapseRun = runs.items[collapseRunIndex];
                    int collapseFromIndex = wrapIndex - collapseRun.charactersStart;
                    assert (collapseRun.characterFlags & GlyphRun.FLAG_TAB) == 0;
                    assert (collapseRun.characterFlags & GlyphRun.FLAG_LINEBREAK) == 0 || collapseRunIndex + 1 == firstRunOnWrappedLineIndex;

                    float collapseToPos;
                    {// Ensure that we don't overflow the available width
                        final int collapseToCharacterIndex = wrapIndex - collapseRun.charactersStart + 1;
                        if (collapseToCharacterIndex >= collapseRun.characterPositions.size) {
                            collapseToPos = collapseRun.width;
                        } else {
                            collapseToPos = collapseRun.characterPositions.items[collapseToCharacterIndex];
                        }

                        final float maxCollapseToPos = availableWidth - collapseRun.x;
                        if (maxCollapseToPos < collapseToPos) {
                            collapseToPos = maxCollapseToPos;
                        }
                    }

                    while (true) {
                        // Set positions of glyphs (space glyphs, presumably, from back to front)
                        for (int i = collapseRun.glyphs.size-1; i > 0; i--) {
                            final float glyphXAdvance = ((Glyph) collapseRun.glyphs.items[i]).xAdvance;
                            final float originalX = collapseRun.glyphX.items[i];
                            if (originalX + glyphXAdvance <= collapseToPos) {
                                // Glyphs that were not collapsed start here
                                break;
                            }
                            // This glyph should be moved so that it does not extend the size of the layout
                            collapseRun.glyphX.items[i] = collapseToPos - glyphXAdvance;
                        }

                        // Set positions of characters
                        for (int i = collapseFromIndex; i < collapseRun.characterPositions.size; i++) {
                            collapseRun.characterPositions.items[i] = collapseToPos;
                        }

                        // Trim width
                        collapseRun.width = collapseToPos;

                        // Set flag indicating that this has collapsed spaces
                        collapseRun.characterFlags |= FLAG_GLYPH_RUN_HAS_COLLAPSED_SPACES;

                        // Advance to next run for truncation
                        if (++collapseRunIndex >= firstRunOnWrappedLineIndex) {
                            break;
                        }

                        final GlyphRun<HBFont> newCollapseRun = runs.items[collapseRunIndex];
                        newCollapseRun.x = collapseRun.x + collapseRun.width;
                        // This new run should not have any glyphs
                        assert newCollapseRun.glyphs.size == 0;
                        collapseRun = newCollapseRun;
                        collapseFromIndex = 0;
                        collapseToPos = 0f;
                    }
                }

                // Complete the wrapped line
                completeLine(text, lineLaidRuns, firstRunOnWrappedLineIndex, runs.items[firstRunOnWrappedLineIndex - 1].font);
                lineLaidRuns = firstRunOnWrappedLineIndex;
                line++;

                // Reposition runs whose line was changed
                for (int i = firstRunOnWrappedLineIndex; i < runs.size; i++) {
                    final GlyphRun<HBFont> run = runs.items[i];
                    run.x = startX;
                    startX += run.width;
                    run.line = line;
                }

                // Check vertical limits
                if (line > 0 && getHeight() > availableHeight) {
                    // The line we just completed is too high and will be scrapped, with previous line ellipsized
                    clampLines = true;
                    break forTextRuns;
                }

                // Line wrapping is done. Since this is a while-loop, it may run multiple times, because the wrapped
                // runs may still be too long to fit. This can degrade to "slow" O(n^2) when the font/text is extremely
                // ligature heavy, see splitRunForWrap. Though this shouldn't happen often.
                // (as of writing this, it should never happen, as BitmapFont does not generate any ligatures,
                // but maybe in the future)
            }*/

            if (lastTextRun || linebreak) {
                // Line probably has to be completed, unless it has been completed before by wrapping algo

                if (lineLaidRuns < runs.size) {
                    completeLine(text, lineLaidRuns, runs.size, firstTextRun.font);
                    lineLaidRuns = runs.size;
                    line++;
                }

                // Check vertical limits
                if (line >= 2 && getHeight() > availableHeight) {
                    // This line is too high, it will be discarded
                    clampLines = true;
                    break;
                }

                if (line >= maxLines) {
                    // Following lines will be clamped, are there any?
                    if (!lastTextRun || linebreak) {
                        // There is something more, clamp now
                        clampLines = true;
                        break;
                    } // else: It is not a problem, as there is nothing more
                }

                if (lastTextRun && linebreak) {
                    // Last line ends with \n, there should be additional new line with the height of current font
                    addLineHeight(firstTextRun.font.lineHeight);

                    // This definitely leads on to the non-first line. Check if it still fits height requirements.
                    if (getHeight() > availableHeight) {
                        clampLines = true;
                        break;
                    }
                }
            }

            textRunGroupStart = textRunGroupEnd;
        } while (textRunGroupStart < textRuns.size);

        // All relevant text runs were added, ensure that we fit with number of lines/height
        if (clampLines) {
            clampExtraLines(text, availableWidth, availableHeight, maxLines, ellipsis);
        }

        final Array<HBFont> fonts = usedFonts;
        for (HBFont font : fonts) {
            font.prepareGlyphs();
        }
        fonts.clear();
    }

    private void clampExtraLines(final LayoutText<HBFont> text, final float availableWidth, final float availableHeight,
                                 int maxLines, final String ellipsis) {
        final Array<GlyphRun<HBFont>> runs = this.runs;
        final FloatArray lineHeights = this.lineHeights;

        // Discard any line information about the last line or any following lines
        if (getHeight() > availableHeight) {
            // Convert to max lines problem
            // Remove lines that overflow
            while (lineHeights.size > 0 && lineHeights.items[lineHeights.size - 1] > availableHeight) {
                lineHeights.size--;
            }
            // Now we know how many lines we can fit
            maxLines = lineHeights.size;
            // Discard lineHeight of the last (valid) line, because we will recompute it later
            lineHeights.size--;
        } else {
            // Drop extra line heights
            assert lineHeights.size >= maxLines - 1;
            lineHeights.size = maxLines - 1;
        }
        final int lastAllowedLine = maxLines - 1;

        // Drop extra runs, if any
        for (int i = runs.size - 1; i >= 0; i--) {
            GlyphRun<HBFont> run = runs.items[i];

            if (run.line >= maxLines) {
                runs.items[i] = null;
                runs.size = i;
                GlyphRun.<HBFont>pool().free(run);
            } else {
                if (run.line == maxLines - 1 && (run.characterFlags & GlyphRun.FLAG_LINEBREAK) != 0) {
                    // This run is on a valid line, but it is a linebreak, so it has to go as well
                    runs.items[i] = null;
                    runs.size = i;
                    GlyphRun.<HBFont>pool().free(run);
                }
                break;
            }
        }

        // Update lineLaidRuns to a valid value, as it may be wrong and point to a different line
        int lineLaidRuns = runs.size;
        while (lineLaidRuns > 0 && runs.items[lineLaidRuns - 1].line == lastAllowedLine) {
            lineLaidRuns--;
        }

        // Generate ellipsis and trim the last line to fit it
        int ellipsisStart = runs.size;

        // Reset startX, it may be in inconsistent state
        if (ellipsisStart <= lineLaidRuns) {
            startX = 0f;
        } else {
            final GlyphRun<HBFont> lastRun = runs.items[ellipsisStart - 1];
            startX = lastRun.x + lastRun.width;
        }

        // Add the ellipsis run (no ellipsis = 0-width ellipsis with no runs)
        final float ellipsisWidth;
        if (ellipsis != null && !ellipsis.isEmpty()) {
            final float startXBeforeEllipsis = startX;
            // TODO(jp): Implement
            /*addEllipsisRunFor(ellipsis, (byte) (text.isLeftToRight() ? 0 : 1),
                    text.getInitialFont(), text.getInitialColor(), lastAllowedLine, ellipsisStart);*/
            ellipsisWidth = startX - startXBeforeEllipsis;
        } else {
            ellipsisWidth = 0f;
        }

        // Trim previous runs, so that ellipsis fits on the line width-wise
        if (startX > availableWidth) {
            final char[] chars = text.text();

            // We need to trim
            int trimmedIndex = ellipsisStart - 1;
            GlyphRun<HBFont> trimmedRun;
            while (trimmedIndex >= 0 && (trimmedRun = runs.items[trimmedIndex]).line == lastAllowedLine) {
                if (trimmedRun.x + ellipsisWidth >= availableWidth) {
                    // Even with this removed, ellipsis won't fit, so remove it
                    startX = trimmedRun.x;
                    GlyphRun.<HBFont>pool().free(trimmedRun);
                    runs.removeIndex(trimmedIndex);
                    ellipsisStart--;
                    trimmedIndex--;
                    continue;
                } else if (trimmedRun.x + trimmedRun.width + ellipsisWidth <= availableWidth) {
                    // This somehow fits, don't do anything
                    break;
                }
                // This run does not need to be removed, but we need to trim it
                startX = trimmedRun.x;
                /* TODO Implement
                final boolean trimmedRunLtr = trimmedRun.isLtr();
                int charactersEnd = charEndIndexForTargetRunWidth(trimmedRun, availableWidth - ellipsisWidth - trimmedRun.x);
                runs.removeIndex(trimmedIndex);
                ellipsisStart--;
                if (trimmedRunLtr ? charactersEnd <= trimmedRun.charactersStart : charactersEnd > trimmedRun.charactersEnd) {
                    // Run has to be removed completely anyway
                    break;
                } else {
                    // Run can be re-added, with less characters
                    ellipsisStart += addRunsFor(chars, trimmedRun.charactersStart, charactersEnd, trimmedRun.charactersLevel,
                            trimmedRun.font, trimmedRun.color, trimmedRun.line, trimmedIndex);
                }
                */
                GlyphRun.<HBFont>pool().free(trimmedRun);
            }

            // Reposition ellipsis according to current startX
            for (int i = ellipsisStart; i < runs.size; i++) {
                GlyphRun<HBFont> ellipsisRun = runs.items[i];
                ellipsisRun.x = startX;
                startX += ellipsisRun.width;
            }
        }

        // Re-complete the line
        completeLine(text, lineLaidRuns, runs.size, text.getInitialFont());
    }

}
