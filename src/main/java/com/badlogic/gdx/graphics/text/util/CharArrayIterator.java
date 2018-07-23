package com.badlogic.gdx.graphics.text.util;

import java.text.CharacterIterator;

/**
 * CharacterIterator that works on char array in given bounds.
 */
public class CharArrayIterator implements CharacterIterator {

    private char[] chars;
    private int start, end;

    private int position;

    /**
     * Reset the iterated range to given values.
     * @param chars to iterate in
     * @param start index into chars (inclusive)
     * @param end index into chars (exclusive)
     */
    public void reset(char[] chars, int start, int end) {
        assert chars != null;
        assert start <= end;

        this.chars = chars;
        this.start = start;
        this.end = end;

        this.position = 0;
    }

    @Override
    public char first() {
        position = start;
        if (position == end) {
            return DONE;
        }
        return chars[position];
    }

    @Override
    public char last() {
        position = end - 1;
        if (position < start) {
            position = end;
            return DONE;
        }
        return chars[position];
    }

    @Override
    public char current() {
        if (position == end) {
            return DONE;
        }
        return chars[position];
    }

    @Override
    public char next() {
        if (++position >= end) {
            position = end;
            return DONE;
        }
        return chars[position];
    }

    @Override
    public char previous() {
        if (--position < start) {
            position = start;
            return DONE;
        }
        return chars[position];
    }

    @Override
    public char setIndex(int position) {
        if (position < start || position > end) {
            throw new IllegalArgumentException(position+" outside valid range of ["+start+", "+end+"]");
        }
        this.position = position;
        if (position < end) {
            return chars[position];
        } else {
            return DONE;
        }
    }

    @Override
    public int getBeginIndex() {
        return start;
    }

    @Override
    public int getEndIndex() {
        return end;
    }

    @Override
    public int getIndex() {
        return position;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public CharArrayIterator clone() {
        final CharArrayIterator clone = new CharArrayIterator();
        clone.chars = this.chars;
        clone.start = this.start;
        clone.end = this.end;
        clone.position = this.position;
        return clone;
    }
}
