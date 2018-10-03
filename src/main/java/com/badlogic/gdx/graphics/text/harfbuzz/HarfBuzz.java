package com.badlogic.gdx.graphics.text.harfbuzz;

import com.badlogic.gdx.graphics.g2d.freetype.FreeType;
import com.badlogic.gdx.graphics.g2d.freetype.HarfBuzzHelper;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * HarfBuzz 1.8.1 bindings
 *
 * Bindings are not exhaustive, as many functions are not meaningful in Java (such as `funcs` procedures),
 * or were not needed (possibly because I did not know what they do - HarfBuzz documentation leaves a lot to be desired).
 *
 * Both low-level API and Java object API is exposed here. Whole binding is structured so that it is easy to combine
 * both API levels, if needed. (Some functions are not exposed through high-level API and low-level API has greater overhead.)
 *
 * There is no additional verification done for performance reasons.
 *
 * Interesting links when digging in HarfBuzz:
 * - https://mail.gnome.org/archives/gtk-i18n-list/2009-August/msg00025.html (and followups)
 * - https://chromium.googlesource.com/chromium/src/+/49cf5df2724445f3160b4fdf13a187295abe14fb/ui/gfx/render_text_harfbuzz.cc
 * - https://github.com/tangrams/harfbuzz-example/blob/master/src/hbshaper.h
 * - MAYBE https://developer.apple.com/fonts/TrueType-Reference-Manual/RM06/Chap6lcar.html ?
 *
 * Some methods are documented in .h/.cc files, but not on the web.
 * It is expected that user will consult official HarfBuzz documentation before using this binding.
 *
 * @author Jan Polák (Darkyenus)
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public final class HarfBuzz {

	private static boolean initialized = false;

	/**
	 * Must be called before any HarfBuzz class is used.
	 * Loads natives. Does nothing if natives already loaded though this method's call.
	 */
	public static void initialize() {
		if (!initialized) {
			new SharedLibraryLoader().load("harfbuzz");
			initialized = true;
		}
	}

    // @off
	/*JNI
	#include <harfbuzz/hb.h>
	#include <harfbuzz/hb-ft.h>

	// Test that the assumptions used in bridging types are valid

	// https://stackoverflow.com/a/19402196/2694196
	#define STATIC_ASSERT(test) typedef char static_assertion_helper[( !!(test) )*2-1 ]

	STATIC_ASSERT(sizeof(char) == sizeof(jbyte));
	STATIC_ASSERT(sizeof(uint16_t) == sizeof(jchar));

	STATIC_ASSERT(sizeof(hb_codepoint_t) == sizeof(jint));
	STATIC_ASSERT(sizeof(hb_mask_t) == sizeof(jint));
	STATIC_ASSERT(sizeof(hb_direction_t) == sizeof(jint));
	STATIC_ASSERT(sizeof(hb_position_t) == sizeof(jint));
	STATIC_ASSERT(sizeof(hb_tag_t) == sizeof(jint));
	STATIC_ASSERT(sizeof(hb_script_t) == sizeof(jint));
	STATIC_ASSERT(sizeof(uint32_t) == sizeof(jint));
	STATIC_ASSERT(sizeof(int) == sizeof(jint));
	STATIC_ASSERT(sizeof(unsigned int) == sizeof(jint));
	STATIC_ASSERT(sizeof(hb_buffer_content_type_t) == sizeof(jint));
	STATIC_ASSERT(sizeof(hb_buffer_flags_t) == sizeof(jint));
	STATIC_ASSERT(sizeof(hb_buffer_cluster_level_t) == sizeof(jint));

	STATIC_ASSERT(sizeof(hb_language_t) == sizeof(jlong));
	STATIC_ASSERT(sizeof(hb_buffer_t *) == sizeof(jlong));
	STATIC_ASSERT(sizeof(hb_glyph_info_t *) == sizeof(jlong));
	STATIC_ASSERT(sizeof(hb_face_t *) == sizeof(jlong));
	STATIC_ASSERT(sizeof(hb_font_t *) == sizeof(jlong));
	STATIC_ASSERT(sizeof(unsigned int *) == sizeof(jlong));
	STATIC_ASSERT(sizeof(hb_tag_t *) == sizeof(jlong));

	*/

	// region https://harfbuzz.github.io/harfbuzz-hb-common.html

	/** hb_tag_t */
	public static final int HB_TAG_NONE = 0;

	/**
	 * @return hb_tag_t
	 */
	public static int hb_tag_from_string(String tag) {
		// Combination of macro and hb_tag_from_string in hb_common.h
		int c1 = (tag == null || tag.length() < 1 ? ' ' : tag.charAt(0)) & 0xFF;
		int c2 = (tag == null || tag.length() < 2 ? ' ' : tag.charAt(1)) & 0xFF;
		int c3 = (tag == null || tag.length() < 3 ? ' ' : tag.charAt(2)) & 0xFF;
		int c4 = (tag == null || tag.length() < 4 ? ' ' : tag.charAt(3)) & 0xFF;
		return c1 <<24 | c2 <<16 | c3 <<8 | c4;
	}

	/**
	 * @param tag hb_tag_t
	 */
	public static String hb_tag_to_string(int tag) {
		char[] buf = new char[4];
		buf[0] = (char) ((tag >> 24) & 0xFF);
		buf[1] = (char) ((tag >> 16) & 0xFF);
		buf[2] = (char) ((tag >> 8) & 0xFF);
		buf[3] = (char) (tag & 0xFF);
		return new String(buf);
	}

	/**
	 * hb_direction_t
	 */
	public enum Direction {
		INVALID(0),
		LTR(4),
		RTL(5),
		TTB(6),
		BTT(7);

		public final int value;

		Direction(int value) {
			this.value = value;
		}

		/**
		 * hb_direction_from_string(char *)
		 * @return hb_direction_t
		 */
		public static Direction fromString(String direction) {
			if (direction == null || direction.isEmpty()) {
				return INVALID;
			}
			switch (Character.toLowerCase(direction.charAt(0))) {
				case 'l':
					return LTR;
				case 'r':
					return RTL;
				case 't':
					return TTB;
				case 'b':
					return BTT;
				default:
					return INVALID;
			}
		}

		public static Direction valueOf(int hb_direction_t) {
			switch (hb_direction_t) {
				case 4:
					return Direction.LTR;
				case 5:
					return Direction.RTL;
				case 6:
					return Direction.TTB;
				case 7:
					return Direction.BTT;
			}
			return Direction.INVALID;
		}

		/**
		 * @param dir hb_direction_t
		 * @return hb_direction_t
		 */
		public static int HB_DIRECTION_REVERSE(int dir){
			return dir ^ 1;
		}

		public static boolean HB_DIRECTION_IS_BACKWARD(int dir) {
			return (dir & ~2) == 5;
		}

		public static boolean HB_DIRECTION_IS_FORWARD(int dir) {
			return (dir & ~2) == 4;
		}

		public static boolean HB_DIRECTION_IS_HORIZONTAL(int dir) {
			return (dir & ~1) == 4;
		}

		public static boolean HB_DIRECTION_IS_VERTICAL(int dir) {
			return (dir & ~1) == 6;
		}

		public static boolean HB_DIRECTION_IS_VALID(int dir) {
			return (dir & ~3) == 4;
		}
	}

	/**
	 * hb_script_t
	 */
	public static final class Script {

		public final int value;

		public Script(int value) {
			this.value = value;
		}

		public Script(String str) {
			this.value = hb_script_from_iso15924_tag(hb_tag_from_string(str));
		}

		/**
		 * @param tag hb_tag_t
		 * @return hb_script_t
		 */
		public static native int hb_script_from_iso15924_tag (int tag); /*
			return hb_script_from_iso15924_tag((hb_tag_t) tag);
		*/

		public Direction getHorizontalDirection() {
			return Direction.valueOf(hb_script_get_horizontal_direction(value));
		}

		/**
		 * @param script hb_script_t
		 * @return hb_direction_t
		 */
		public static native int hb_script_get_horizontal_direction (int script);/*
			return (jint) hb_script_get_horizontal_direction((hb_script_t)script);
		*/

		@Override
		public String toString() {
			return hb_tag_to_string(value);
		}
	}

	/**
	 * hb_language_t
	 */
	public static final class Language {

		public static final long HB_LANGUAGE_INVALID = 0L;

		public final long value;

		public Language(long value) {
			this.value = value;
		}

		public Language(String str) {
			this.value = hb_language_from_string(str);
		}

		/**
		 * @return hb_language_t
		 */
		public static native long hb_language_from_string(String str); /*
			return (jlong) hb_language_from_string(str, env->GetStringUTFLength(obj_str));
		*/

		@Override
		public String toString() {
			return hb_language_to_string(value);
		}

		/**
		 * @param language hb_language_t
		 */
		public static native String hb_language_to_string (long language); /*
			return env->NewStringUTF(hb_language_to_string((hb_language_t) language));
		*/

		public static Language getDefault() {
			return new Language(hb_language_get_default());
		}

		/**
		 * @return hb_language_t
		 */
		public static native long hb_language_get_default (); /*
			return (jlong) hb_language_get_default();
		*/
	}

	//endregion

	//region https://harfbuzz.github.io/harfbuzz-Buffers.html

	/**
	 * hb_buffer_t
	 */
	public static final class Buffer extends Pointer {

		public Buffer(long addr) {
			super(addr);
		}

		public static Buffer create() {
			long ptr = hb_buffer_create();
			return new Buffer(ptr == 0 ? hb_buffer_get_empty() : ptr);
		}

		/**
		 * @return hb_buffer_t *
		 */
		public static native long hb_buffer_create(); /*
			return (jlong) hb_buffer_create();
		*/

		/**
		 * @return hb_buffer_t *
		 */
		public static native long hb_buffer_get_empty(); /*
			return (jlong) hb_buffer_get_empty();
		*/

		@Override
		public void reference() {
			hb_buffer_reference(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @return hb_buffer_t *
		 */
		public static native long hb_buffer_reference(long buffer); /*
			return (jlong) hb_buffer_reference((hb_buffer_t *) buffer);
		*/

		@Override
		public void destroy() {
			hb_buffer_destroy(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 */
		public static native void hb_buffer_destroy(long buffer); /*
			hb_buffer_destroy((hb_buffer_t *) buffer);
		*/

		public void reset() {
			hb_buffer_reset(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 */
		public static native void hb_buffer_reset(long buffer); /*
			hb_buffer_reset((hb_buffer_t *) buffer);
		*/

		public void clearContents() {
			hb_buffer_clear_contents(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 */
		public static native void hb_buffer_clear_contents(long buffer); /*
			hb_buffer_clear_contents((hb_buffer_t *) buffer);
		*/

		public boolean preAllocate(int size) {
			return hb_buffer_pre_allocate(addr, size);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param size unsigned int
		 */
		public static native boolean hb_buffer_pre_allocate(long buffer, int size); /*
			return hb_buffer_pre_allocate((hb_buffer_t *) buffer, size);
		*/

		public boolean allocationSuccessful() {
			return hb_buffer_allocation_successful(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 */
		public static native boolean hb_buffer_allocation_successful (long buffer); /*
			return hb_buffer_allocation_successful((hb_buffer_t *) buffer);
		*/

		public void add(String text, int textOffset, int textLength, int itemOffset, int itemLength) {
			hb_buffer_add_string(addr, text, textOffset, textLength, itemOffset, itemLength);
		}

		public void add(char[] text, int textOffset, int textLength, int itemOffset, int itemLength) {
			hb_buffer_add_utf16(addr, text, textOffset, textLength, itemOffset, itemLength);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param codepoint hb_codepoint_t
		 * @param cluster unsigned int
		 */
		public static native void hb_buffer_add (long buffer, int codepoint, int cluster); /*
			hb_buffer_add((hb_buffer_t *) buffer, (hb_codepoint_t) codepoint, (unsigned int) cluster);
		*/

		/**
		 * @param buffer hb_buffer_t *
		 * @param text const hb_codepoint_t*
		 * @param text_offset offset into text
		 * @param text_length int
		 * @param item_offset unsigned int
		 * @param item_length int
		 */
		public static native void hb_buffer_add_codepoints (long buffer, int[] text, int text_offset, int text_length, int item_offset, int item_length); /*
			hb_buffer_add_codepoints((hb_buffer_t *) buffer, (hb_codepoint_t *) (text + text_offset), text_length, item_offset, item_length);
		*/

		/**
		 * @param buffer hb_buffer_t *
		 * @param text const uint32_t *
		 * @param text_offset offset into text
		 * @param text_length int
		 * @param item_offset unsigned int
		 * @param item_length int
		 */
		public static native void hb_buffer_add_utf32 (long buffer, int[] text, int text_offset, int text_length, int item_offset, int item_length); /*
			hb_buffer_add_utf32((hb_buffer_t *) buffer, (uint32_t *)(text + text_offset), text_length, item_offset, item_length);
		*/

		/**
		 * @param buffer hb_buffer_t *
		 * @param text const uint16_t *
		 * @param text_offset offset into text
		 * @param text_length int
		 * @param item_offset unsigned int
		 * @param item_length int
		 */
		public static native void hb_buffer_add_utf16 (long buffer, char[] text, int text_offset, int text_length, int item_offset, int item_length); /*
			hb_buffer_add_utf16((hb_buffer_t *) buffer, text + text_offset, text_length, item_offset, item_length);
		*/

		/**
		 * @param buffer hb_buffer_t *
		 * @param text const char *
		 * @param text_offset offset into text
		 * @param text_length int
		 * @param item_offset unsigned int
		 * @param item_length int
		 */
		public static native void hb_buffer_add_utf8 (long buffer, byte[] text, int text_offset, int text_length, int item_offset, int item_length); /*
			hb_buffer_add_utf8((hb_buffer_t *) buffer, text + text_offset, text_length, item_offset, item_length);
		*/

		/**
		 * @see #hb_buffer_add_utf16(long, char[], int, int, int, int)
		 */
		public static native void hb_buffer_add_string (long buffer, String text, int text_offset, int text_length, int item_offset, int item_length); /*MANUAL
			const jchar *text_chars = env->GetStringCritical(obj_text, 0);
			//TODO text_chars could be null when OOM, do we care? (applies to all array or string procedures)
			hb_buffer_add_utf16((hb_buffer_t *) buffer, (const uint16_t *) (text_chars + text_offset), text_length, item_offset, item_length);
			env->ReleaseStringCritical(obj_text, text_chars);
		*/

		public void append(Buffer buffer, int start, int end) {
			hb_buffer_append(addr, buffer.addr, start, end);
		}

		/**
		 * @param buffer hb_buffer_t * (target)
		 * @param source hb_buffer_t *
		 * @param start unsigned int
		 * @param end unsigned int
		 */
		public static native void hb_buffer_append (long buffer, long source, int start, int end);/*
			hb_buffer_append((hb_buffer_t *) buffer, (hb_buffer_t *) source, (unsigned int) start, (unsigned int) end);
		*/

		/** hb_buffer_content_type_t: Initial value for new buffer. */
		public static final int HB_BUFFER_CONTENT_TYPE_INVALID = 0;
		/** hb_buffer_content_type_t: The buffer contains input characters (before shaping). */
		public static final int HB_BUFFER_CONTENT_TYPE_UNICODE = 1;
		/** hb_buffer_content_type_t: The buffer contains output glyphs (after shaping). */
		public static final int HB_BUFFER_CONTENT_TYPE_GLYPHS = 2;

		public enum ContentType {
			INVALID(HB_BUFFER_CONTENT_TYPE_INVALID),
			UNICODE(HB_BUFFER_CONTENT_TYPE_UNICODE),
			GLYPHS(HB_BUFFER_CONTENT_TYPE_GLYPHS);

			public final int value;

			ContentType(int value) {
				this.value = value;
			}
		}

		public void setContentType(ContentType type) {
			hb_buffer_set_content_type(addr, type.value);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param content_type hb_buffer_content_type_t
		 */
		public static native void hb_buffer_set_content_type (long buffer, int content_type); /*
			hb_buffer_set_content_type((hb_buffer_t *) buffer, (hb_buffer_content_type_t) content_type);
		*/

		public ContentType getContentType() {
			final int contentType = hb_buffer_get_content_type(addr);
			switch (contentType) {
				case HB_BUFFER_CONTENT_TYPE_INVALID:
					return ContentType.INVALID;
				case HB_BUFFER_CONTENT_TYPE_UNICODE:
					return ContentType.UNICODE;
				case HB_BUFFER_CONTENT_TYPE_GLYPHS:
					return ContentType.GLYPHS;
				default:
					assert false : "Unrecognized content type: "+contentType;
					return ContentType.INVALID;
			}
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @return hb_buffer_content_type_t
		 */
		public static native int hb_buffer_get_content_type (long buffer); /*
			return (jint) hb_buffer_get_content_type((hb_buffer_t *) buffer);
		*/

		public void setDirection(Direction direction) {
			hb_buffer_set_direction(addr, direction.value);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param direction hb_direction_t
		 */
		public static native void hb_buffer_set_direction (long buffer, int direction); /*
			hb_buffer_set_direction((hb_buffer_t *) buffer, (hb_direction_t) direction);
		*/

		public Direction getDirection() {
			return Direction.valueOf(hb_buffer_get_direction(addr));
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @return hb_direction_t
		 */
		public static native int hb_buffer_get_direction (long buffer); /*
			return (jint) hb_buffer_get_direction((hb_buffer_t *) buffer);
		*/

		public void setScript(Script script) {
			hb_buffer_set_script(addr, script.value);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param script hb_script_t
		 */
		public static native void hb_buffer_set_script (long buffer, int script); /*
			hb_buffer_set_script((hb_buffer_t *) buffer, (hb_script_t) script);
		*/

		public Script getScript() {
			return new Script(hb_buffer_get_script(addr));
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @return hb_script_t
		 */
		public static native int hb_buffer_get_script (long buffer); /*
			return (jint) hb_buffer_get_script((hb_buffer_t *) buffer);
		*/

		public void setLanguage(Language language) {
			hb_buffer_set_language(addr, language.value);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param language hb_language_t
		 */
		public static native void hb_buffer_set_language (long buffer, long language); /*
			hb_buffer_set_language((hb_buffer_t *) buffer, (hb_language_t) language);
		*/

		public Language getLanguage() {
			return new Language(hb_buffer_get_language(addr));
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @return hb_language_t
		 */
		public static native long hb_buffer_get_language (long buffer); /*
			return (jlong) hb_buffer_get_language((hb_buffer_t *) buffer);
		*/

		/** hb_buffer_flags_t
		 * the default buffer flag. */
		public static final int HB_BUFFER_FLAG_DEFAULT = 0;

		/** hb_buffer_flags_t
		 * flag indicating that special handling of the beginning of text paragraph can be applied to this buffer.
		 * Should usually be set, unless you are passing to the buffer only part of the text without the full context. */
		public static final int HB_BUFFER_FLAG_BOT = 0x1;

		/** hb_buffer_flags_t
		 * flag indicating that special handling of the end of text paragraph can be applied to this buffer,
		 * similar to HB_BUFFER_FLAG_BOT.*/
		public static final int HB_BUFFER_FLAG_EOT = 0x2;

		/** hb_buffer_flags_t
		 * flag indication that character with Default_Ignorable Unicode property should use the corresponding glyph
		 * from the font, instead of hiding them (done by replacing them with the space glyph and zeroing the advance width.)
		 * This flag takes precedence over HB_BUFFER_FLAG_REMOVE_DEFAULT_IGNORABLES . */
		public static final int HB_BUFFER_FLAG_PRESERVE_DEFAULT_IGNORABLES = 0x4;

		/** hb_buffer_flags_t
		 * flag indication that character with Default_Ignorable Unicode property should be removed from glyph string
		 * instead of hiding them (done by replacing them with the space glyph and zeroing the advance width.)
		 * HB_BUFFER_FLAG_PRESERVE_DEFAULT_IGNORABLES takes precedence over this flag. */
		public static final int HB_BUFFER_FLAG_REMOVE_DEFAULT_IGNORABLES = 0x8;

		public void setFlags(int flags) {
			hb_buffer_set_flags(addr, flags);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param flags hb_buffer_flags_t
		 */
		public static native void hb_buffer_set_flags (long buffer, int flags); /*
			hb_buffer_set_flags((hb_buffer_t *) buffer, (hb_buffer_flags_t) flags);
		*/

		public int getFlags() {
			return hb_buffer_get_flags(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @return hb_buffer_flags_t
		 */
		public static native int hb_buffer_get_flags (long buffer); /*
			return (jint) hb_buffer_get_flags((hb_buffer_t *) buffer);
		*/

		/**
		 * hb_buffer_cluster_level_t
		 */
		public enum ClusterLevel {
			MONOTONE_GRAPHEMES(0),
			MONOTONE_CHARACTERS(1),
			CHARACTERS(2);

			public final int value;

			ClusterLevel(int value) {
				this.value = value;
			}

			public static ClusterLevel valueOf(int hb_buffer_cluster_level_t) {
				switch (hb_buffer_cluster_level_t) {
					case 0:
						return MONOTONE_GRAPHEMES;
					case 1:
						return MONOTONE_CHARACTERS;
					case 2:
						return CHARACTERS;
					default:
						return null;
				}
			}

			public static final ClusterLevel DEFAULT = MONOTONE_GRAPHEMES;
		}

		public void setClusterLevel(ClusterLevel clusterLevel) {
			hb_buffer_set_cluster_level(addr, clusterLevel.value);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param cluster_level hb_buffer_cluster_level_t
		 */
		public static native void hb_buffer_set_cluster_level (long buffer, int cluster_level); /*
			hb_buffer_set_cluster_level((hb_buffer_t *) buffer, (hb_buffer_cluster_level_t) cluster_level);
		*/

		public ClusterLevel getClusterLevel() {
			return ClusterLevel.valueOf(hb_buffer_get_cluster_level(addr));
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @return hb_buffer_cluster_level_t
		 */
		public static native int hb_buffer_get_cluster_level (long buffer); /*
			return (jint) hb_buffer_get_cluster_level((hb_buffer_t *) buffer);
		*/

		public int getLength() {
			return hb_buffer_get_length(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @return unsigned int
		 */
		public static native int hb_buffer_get_length (long buffer); /*
			return (jint) hb_buffer_get_length((hb_buffer_t *) buffer);
		*/

		public void setLength(int length) {
			hb_buffer_set_length(addr, length);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param length unsigned int
		 * @return hb_bool_t
		 */
		public static native boolean hb_buffer_set_length (long buffer, int length); /*
			return hb_buffer_set_length((hb_buffer_t *) buffer, (unsigned int) length);
		*/

		public void guessSegmentProperties() {
			hb_buffer_guess_segment_properties(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 */
		public static native void hb_buffer_guess_segment_properties (long buffer); /*
			hb_buffer_guess_segment_properties((hb_buffer_t *) buffer);
		*/

		/**
		 * @param buffer hb_buffer_t *
		 * @param length unsigned int *
		 * @return hb_glyph_info_t *
		 */
		public static native long hb_buffer_get_glyph_infos (long buffer, IntBuffer length); /*
			return (jlong) hb_buffer_get_glyph_infos((hb_buffer_t *) buffer, (unsigned int *) length);
		*/

		/**
		 * @see #hb_buffer_get_glyph_infos_to(long, IntArray)
		 */
		public void getGlyphInfos(IntArray out) {
			hb_buffer_get_glyph_infos_to(addr, out);
		}

		/**
		 * Retrieves glyph infos in the buffer and stores it in out array.
		 * Can be called on both {@link #HB_BUFFER_CONTENT_TYPE_GLYPHS} and {@link #HB_BUFFER_CONTENT_TYPE_UNICODE} type buffer.
		 *
		 * After call, out/3 = amount of glyphs.
		 * Packing follows closely
		 * <a href="https://harfbuzz.github.io/harfbuzz-Buffers.html#hb-glyph-info-t-struct">hb_glyph_info_t struct</a>
		 * layout.
		 *
		 * <code>
		 * out[i/3 + 0] = either a Unicode code point (before shaping) or a glyph index (after shaping) of item i
		 * out[i/3 + 1] = after shaping contains hb_glyph_flags_t (and possibly other bits are set)
		 * out[i/3 + 2] = the index of the character in the original text, SEE hb_glyph_info_t!
		 * </code>
		 *
		 * @param buffer hb_buffer_t *
		 * @param out array to which result should be stored to. Any current contents are discarded.
		 */
		public static void hb_buffer_get_glyph_infos_to(long buffer, IntArray out) {
			//TODO How do we do this in libgdx?
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer length = stack.mallocInt(1);
				long glyphInfosPtr = hb_buffer_get_glyph_infos(buffer, length);

				out.size = 0;
				int infoCount = length.get(0);
				out.ensureCapacity(infoCount * 3);
				out.size = infoCount * 3;

				hb_buffer_get_glyph_infos_java_pack(glyphInfosPtr, infoCount, out.items);
			}
		}

		private static native void hb_buffer_get_glyph_infos_java_pack(long glyphPtr, int glyphCount, int[] out); /*
			hb_glyph_info_t * glyphs = (hb_glyph_info_t *) glyphPtr;

			for (size_t g = 0, i = 0; g < glyphCount; g++) {
				out[i++] = (jint) glyphs[g].codepoint;
				out[i++] = (jint) glyphs[g].mask;
				out[i++] = (jint) glyphs[g].cluster;
			}
		*/

		/**
		 * @param buffer hb_buffer_t *
		 * @param length unsigned int *
		 * @return hb_glyph_position_t *
		 */
		public static native long hb_buffer_get_glyph_positions (long buffer, IntBuffer length); /*
			return (jlong) hb_buffer_get_glyph_positions((hb_buffer_t *) buffer, (unsigned int *) length);
		*/

		/**
		 * @see #hb_buffer_get_glyph_positions_to(long, IntArray)
		 */
		public void getGlyphPositions(IntArray out) {
			hb_buffer_get_glyph_positions_to(addr, out);
		}

		/**
		 * Retrieves shaped glyph positions and stores it in out array.
		 * Can be called only on {@link #HB_BUFFER_CONTENT_TYPE_GLYPHS} (shaped) type buffer.
		 *
		 * After call, out/4 = amount of glyphs.
		 * Packing follows closely
		 * <a href="https://harfbuzz.github.io/harfbuzz-Buffers.html#hb-glyph-position-t-struct">hb_glyph_position_t struct</a>
		 * layout.
		 *
		 * <code>
		 * out[i/4 + 0] = x_advance - how much the line advances after drawing this glyph when setting text in horizontal direction
		 * out[i/4 + 1] = y_advance - how much the line advances after drawing this glyph when setting text in vertical direction
		 * out[i/4 + 2] = x_offset - how much the glyph moves on the X-axis before drawing it, this should not affect how much the line advances
		 * out[i/4 + 3] = y_offset - how much the glyph moves on the Y-axis before drawing it, this should not affect how much the line advances
		 * </code>
		 * All positions are relative to the current point.
		 *
		 * @param buffer hb_buffer_t *
		 * @param out array to which result should be stored to. Any current contents are discarded.
		 */
		public static void hb_buffer_get_glyph_positions_to(long buffer, IntArray out) {
			//TODO How do we do this in libgdx?
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer length = stack.mallocInt(1);
				long glyphPositionsPtr = hb_buffer_get_glyph_positions(buffer, length);

				out.size = 0;
				int infoCount = length.get(0);
				out.ensureCapacity(infoCount * 4);
				out.size = infoCount * 4;

				hb_buffer_get_glyph_positions_java_pack(glyphPositionsPtr, infoCount, out.items);
			}
		}

		private static native void hb_buffer_get_glyph_positions_java_pack(long glyphPositionsPtr, int glyphCount, int[] out); /*
			hb_glyph_position_t * glyphPositions = (hb_glyph_position_t *) glyphPositionsPtr;

			for (size_t g = 0, i = 0; g < glyphCount; g++) {
				out[i++] = (jint) glyphPositions[g].x_advance;
				out[i++] = (jint) glyphPositions[g].y_advance;
				out[i++] = (jint) glyphPositions[g].x_offset;
				out[i++] = (jint) glyphPositions[g].y_offset;
			}
		*/

		public void setReplacementCodepoint(int replacementCodepoint) {
			hb_buffer_set_replacement_codepoint(addr, replacementCodepoint);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param replacement hb_codepoint_t
		 */
		public static native void hb_buffer_set_replacement_codepoint (long buffer, int replacement); /*
			hb_buffer_set_replacement_codepoint((hb_buffer_t *) buffer, (hb_codepoint_t) replacement);
		*/

		public int getReplacementCodepoint() {
			return hb_buffer_get_replacement_codepoint(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @return hb_codepoint_t
		 */
		public static native int hb_buffer_get_replacement_codepoint (long buffer); /*
			return (hb_codepoint_t) hb_buffer_get_replacement_codepoint((hb_buffer_t *) buffer);
		*/

		public void normalizeGlyphs() {
			hb_buffer_normalize_glyphs(addr);
		}

		/**
		 * This has nothing to do with Unicode normalization.
		 * @param buffer hb_buffer_t *
		 */
		public static native void hb_buffer_normalize_glyphs (long buffer); /*
			hb_buffer_normalize_glyphs((hb_buffer_t *) buffer);
		*/

		public void reverse() {
			hb_buffer_reverse(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 */
		public static native void hb_buffer_reverse (long buffer); /*
			hb_buffer_reverse((hb_buffer_t *) buffer);
		*/

		public void reverseRange(int start, int end) {
			hb_buffer_reverse_range(addr, start, end);
		}

		/**
		 * @param buffer hb_buffer_t *
		 * @param start unsigned int
		 * @param end unsigned int
		 */
		public static native void hb_buffer_reverse_range (long buffer, int start, int end); /*
			hb_buffer_reverse_range((hb_buffer_t *) buffer, (unsigned int) start, (unsigned int) end);
		*/

		public void reverseClusters() {
			hb_buffer_reverse_clusters(addr);
		}

		/**
		 * @param buffer hb_buffer_t *
		 */
		public static native void hb_buffer_reverse_clusters (long buffer); /*
			hb_buffer_reverse_clusters((hb_buffer_t *) buffer);
		*/

		/** hb_glyph_flags_t
		 * https://harfbuzz.github.io/harfbuzz-Buffers.html#hb-glyph-flags-t */
		public static final int HB_GLYPH_FLAG_UNSAFE_TO_BREAK = 0x1;
	}

	//endregion

	//region https://harfbuzz.github.io/harfbuzz-hb-face.html
	// To be used with hb_ft faces.

	public static final class Face extends Pointer {

		public Face(long addr) {
			super(addr);
		}

		@Override
		public void reference() {
			hb_face_reference(addr);
		}

		/**
		 * @param face hb_face_t *
		 * @return hb_face_t *
		 */
		public static native long hb_face_reference (long face); /*
			return (jlong) hb_face_reference((hb_face_t *) face);
		*/

		@Override
		public void destroy() {
			hb_face_destroy(addr);
		}

		/**
		 * @param face hb_face_t *
		 */
		public static native void hb_face_destroy (long face); /*
			hb_face_destroy((hb_face_t *) face);
		*/

		//region https://harfbuzz.github.io/harfbuzz-hb-ft.html

		/**
		 * @param ft_face FT_Face
		 * @return hb_face_t *
		 */
		public static native long hb_ft_face_create_referenced (long ft_face); /*
			return (jlong) hb_ft_face_create_referenced((FT_Face) ft_face);
		*/

		/**
		 * @see #hb_ft_face_create_referenced(long)
		 */
		public static Face createReferenced(FreeType.Face face) {
			return new Face(hb_ft_face_create_referenced(HarfBuzzHelper.addressOf(face)));
		}

		//endregion

		public boolean isImmutable() {
			return hb_face_is_immutable(addr);
		}

		/**
		 * @param face hb_face_t *
		 * @return hb_bool_t
		 */
		public static native boolean hb_face_is_immutable (long face); /*
			return (jboolean) hb_face_is_immutable((hb_face_t *) face);
		*/

		public int getIndex() {
			return hb_face_get_index(addr);
		}

		/**
		 * @param face hb_face_t *
		 * @return unsigned int
		 */
		public static native int hb_face_get_index (long face); /*
			return (jint) hb_face_get_index((hb_face_t *) face);
		*/

		public int getUpem() {
			return hb_face_get_upem(addr);
		}

		/**
		 * @param face hb_face_t *
		 * @return unsigned int
		 */
		public static native int hb_face_get_upem (long face); /*
			return (jint) hb_face_get_upem((hb_face_t *) face);
		*/

		public int getGlyphCount() {
			return hb_face_get_glyph_count(addr);
		}

		/**
		 * @param face hb_face_t *
		 * @return unsigned int
		 */
		public static native int hb_face_get_glyph_count (long face); /*
			return (jint) hb_face_get_glyph_count((hb_face_t *) face);
		*/

		/**
		 * @param face hb_face_t *
		 * @param start_offset unsigned int
		 * @param table_count unsigned int *  IN/OUT
		 * @param table_tags hb_tag_t *  OUT
		 * @return unsigned int
		 */
		public static native int hb_face_get_table_tags (long face, int start_offset, IntBuffer table_count, IntBuffer table_tags); /*
			return (jint) hb_face_get_table_tags((hb_face_t *) face, (unsigned int) start_offset, (unsigned int *) table_count, (hb_tag_t *) table_tags);
		*/

		/**
		 * @see #hb_face_get_table_tags(long, IntArray)
		 */
		public void getTableTags(IntArray out) {
			hb_face_get_table_tags(addr, out);
		}

		/**
		 * @param face hb_face_t *
		 * @param tags_out array of hb_tag_t
		 * @see #hb_face_get_table_tags(long, int, IntBuffer, IntBuffer)
		 */
		public static void hb_face_get_table_tags (long face, IntArray tags_out) {
			tags_out.clear();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer tableCount = stack.mallocInt(1);
				final int tagBatchCount = 3;
				IntBuffer tableTags = stack.mallocInt(tagBatchCount);
				int offset = 0;
				while (true) {
					tableCount.put(0, tagBatchCount);
					final int totalCount = hb_face_get_table_tags(face, offset, tableCount, tableTags);
					if (totalCount == 0) {
						return;
					}
					final int count = tableCount.get(0);
					if (count == 0) {
						break;
					}
					offset += count;
					for (int i = 0; i < count; i++) {
						tags_out.add(tableTags.get(i));
					}
				}
			}
		}

	}

	//endregion

	//region https://harfbuzz.github.io/harfbuzz-hb-font.html

	public static final class Font extends Pointer {

		public Font(long addr) {
			super(addr);
		}

		@Deprecated // TODO Does not seem to work
		public Font(Face face) {
			super(hb_font_create(face.addr));
		}

		/**
		 * @param face hb_face_t *
		 * @return hb_font_t *
		 */
		public static native long hb_font_create (long face); /*
			return (jlong) hb_font_create((hb_face_t *) face);
		*/

		/**
		 * @param parent hb_font_t *
		 * @return hb_font_t *
		 */
		public static native long hb_font_create_sub_font (long parent); /*
			return (jlong) hb_font_create_sub_font((hb_font_t *) parent);
		*/

		@Override
		public void reference() {
			hb_font_reference(addr);
		}

		/**
		 * @param font hb_font_t *
		 * @return hb_font_t *
		 */
		public static native long hb_font_reference (long font); /*
			return (jlong) hb_font_reference((hb_font_t *) font);
		*/

		@Override
		public void destroy() {
			hb_font_destroy(addr);
		}

		/**
		 * @param font hb_font_t *
		 */
		public static native void hb_font_destroy (long font); /*
			hb_font_destroy((hb_font_t *) font);
		*/

		public boolean isImmutable() {
			return hb_font_is_immutable(addr);
		}

		/**
		 * @param font hb_font_t *
		 * @return hb_bool_t
		 */
		public static native boolean hb_font_is_immutable (long font); /*
			return (jboolean) hb_font_is_immutable((hb_font_t *) font);
		*/

		/**
		 * @param font hb_font_t *
		 * @param parent hb_font_t *
		 */
		public static native void hb_font_set_parent (long font, long parent); /*
			hb_font_set_parent((hb_font_t *) font, (hb_font_t *) parent);
		*/

		/**
		 * @param font hb_font_t *
		 * @return hb_font_t *
		 */
		public static native long hb_font_get_parent (long font); /*
			return (jlong) hb_font_get_parent((hb_font_t *) font);
		*/

		public void setFace(Face face) {
			hb_font_set_face(addr, face.addr);
		}

		/**
		 * @param font hb_font_t *
		 * @param face hb_face_t *
		 */
		public static native void hb_font_set_face (long font, long face); /*
			hb_font_set_face((hb_font_t *) font, (hb_face_t *) face);
		*/

		public Face getFace() {
			return new Face(hb_font_get_face(addr));
		}

		/**
		 * @param font hb_font_t *
		 * @return hb_face_t *
		 */
		public static native long hb_font_get_face (long font); /*
			return (jlong) hb_font_get_face((hb_font_t *) font);
		*/

		//region https://harfbuzz.github.io/harfbuzz-hb-ft.html

		/**
		 * @see #hb_ft_font_create_referenced(long)
		 */
		public static Font createReferenced(FreeType.Face face) {
			return new Font(hb_ft_font_create_referenced(HarfBuzzHelper.addressOf(face)));
		}

		/**
		 * @param ft_face FT_Face
		 * @return hb_font_t *
		 */
		public static native long hb_ft_font_create_referenced(long ft_face); /*
			return (jlong) hb_ft_font_create_referenced((FT_Face) ft_face);
		*/

		public void freetypeFontChanged() {
			hb_ft_font_changed(addr);
		}

		/**
		 * @param font hb_font_t *
		 */
		public static native void hb_ft_font_changed (long font); /*
			hb_ft_font_changed((hb_font_t *) font);
		*/

		//endregion

		/**
		 * Procedures that return hb_font_extents_t struct instead take int[] with length {@link #FONT_EXTENTS_SIZE}.
		 * Struct's fields are then stored at those indices.
		 *
		 * typographic ascender
		 * Note that typically ascender is positive and descender negative in coordinate systems that grow up.
		 */
		public static final int FONT_EXTENTS_I_ASCENDER = 0;
		/** typographic descender */
		public static final int FONT_EXTENTS_I_DESCENDER = 1;
		/** suggested line spacing gap */
		public static final int FONT_EXTENTS_I_LINE_GAP = 2;
		public static final int FONT_EXTENTS_SIZE = 3;

		/**
		 * @see #FONT_EXTENTS_I_ASCENDER
		 * @param font hb_font_t *
		 * @param extents hb_font_extents_t *
		 * @return hb_bool_t
		 */
		public static native boolean hb_font_get_h_extents (long font, int[] extents); /*
			hb_font_extents_t font_extents;
			if (hb_font_get_h_extents((hb_font_t *) font, &font_extents)) {
				extents[0] = font_extents.ascender;
				extents[1] = font_extents.descender;
				extents[2] = font_extents.line_gap;
				return JNI_TRUE;
			}
			return JNI_FALSE;
		*/

		/**
		 * @see #FONT_EXTENTS_I_ASCENDER
		 * @param font hb_font_t *
		 * @param extents hb_font_extents_t *
		 * @return hb_bool_t
		 */
		public static native boolean hb_font_get_v_extents (long font, int[] extents); /*
			hb_font_extents_t font_extents;
			if (hb_font_get_v_extents((hb_font_t *) font, &font_extents)) {
				extents[0] = font_extents.ascender;
				extents[1] = font_extents.descender;
				extents[2] = font_extents.line_gap;
				return JNI_TRUE;
			}
			return JNI_FALSE;
		*/

		/**
		 * @param font hb_font_t *
		 * @param glyph hb_codepoint_t
		 * @return hb_position_t
		 */
		public static native int hb_font_get_glyph_h_advance (long font, int glyph); /*
			return (jint) hb_font_get_glyph_h_advance((hb_font_t *) font, (hb_codepoint_t) glyph);
		*/

		/**
		 * @param font hb_font_t *
		 * @param glyph hb_codepoint_t
		 * @return hb_position_t
		 */
		public static native int hb_font_get_glyph_v_advance (long font, int glyph); /*
			return (jint) hb_font_get_glyph_v_advance((hb_font_t *) font, (hb_codepoint_t) glyph);
		*/

		/**
		 * @param font hb_font_t *
		 * @param glyph hb_codepoint_t
		 * @param x hb_position_t *
		 * @param y hb_position_t *
		 * @return hb_bool_t
		 */
		public static native boolean hb_font_get_glyph_h_origin (long font, int glyph, IntBuffer x, IntBuffer y); /*
			return (jboolean) hb_font_get_glyph_h_origin((hb_font_t *) font, (hb_codepoint_t) glyph, (hb_position_t *) x, (hb_position_t *) y);
		*/

		/**
		 * @param font hb_font_t *
		 * @param glyph hb_codepoint_t
		 * @param x hb_position_t *
		 * @param y hb_position_t *
		 * @return hb_bool_t
		 */
		public static native boolean hb_font_get_glyph_v_origin (long font, int glyph, IntBuffer x, IntBuffer y); /*
			return (jboolean) hb_font_get_glyph_v_origin((hb_font_t *) font, (hb_codepoint_t) glyph, (hb_position_t *) x, (hb_position_t *) y);
		*/

		/**
		 * @param font hb_font_t *
		 * @param left_glyph hb_codepoint_t
		 * @param right_glyph hb_codepoint_t
		 * @return hb_position_t
		 */
		public static native int hb_font_get_glyph_h_kerning (long font, int left_glyph, int right_glyph); /*
			return (jint) hb_font_get_glyph_h_kerning((hb_font_t *) font, (hb_codepoint_t) left_glyph, (hb_codepoint_t) right_glyph);
		*/

		/**
		 * @param font hb_font_t *
		 * @param top_glyph hb_codepoint_t
		 * @param bottom_glyph hb_codepoint_t
		 * @return hb_position_t
		 */
		public static native int hb_font_get_glyph_v_kerning (long font, int top_glyph, int bottom_glyph); /*
			return (jint) hb_font_get_glyph_v_kerning((hb_font_t *) font, (hb_codepoint_t) top_glyph, (hb_codepoint_t) bottom_glyph);
		*/

		/**
		 * Similar to {@link #FONT_EXTENTS_I_ASCENDER} but for hb_glyph_extents_t struct.
		 *
		 * left side of glyph from origin
		 * Note that height is negative in coordinate systems that grow up.
		 */
		public static final int GLYPH_EXTENTS_I_X_BEARING = 0;
		/** top side of glyph from origin */
		public static final int GLYPH_EXTENTS_I_Y_BEARING = 1;
		/** distance from left to right side */
		public static final int GLYPH_EXTENTS_I_WIDTH = 2;
		/** distance from top to bottom side */
		public static final int GLYPH_EXTENTS_I_HEIGHT = 3;
		public static final int GLYPH_EXTENTS_SIZE = 4;

		/**
		 * @param font hb_font_t *
		 * @param glyph hb_codepoint_t
		 * @param extents hb_glyph_extents_t *
		 * @return hb_bool_t
		 */
		public static native boolean hb_font_get_glyph_extents (long font, int glyph, int[] extents); /*
			hb_glyph_extents_t glyph_extents;
			if (hb_font_get_glyph_extents((hb_font_t *) font, (hb_codepoint_t) glyph, &glyph_extents)) {
				extents[0] = glyph_extents.x_bearing;
				extents[1] = glyph_extents.y_bearing;
				extents[2] = glyph_extents.width;
				extents[3] = glyph_extents.height;
				return JNI_TRUE;
			}
			return JNI_FALSE;
		*/

		/**
		 * @param font hb_font_t *
		 * @param glyph hb_codepoint_t
		 * @param point_index unsigned int
		 * @param x hb_position_t *
		 * @param y hb_position_t *
		 * @return hb_bool_t
		 */
		public static native boolean hb_font_get_glyph_contour_point (long font, int glyph, int point_index, IntBuffer x, IntBuffer y); /*
			return (jboolean) hb_font_get_glyph_contour_point((hb_font_t *) font, (hb_codepoint_t) glyph, (unsigned int) point_index, (hb_position_t *) x, (hb_position_t *) y);
		*/

		/**
		 * @param font hb_font_t *
		 * @param unicode hb_codepoint_t
		 * @param variation_selector hb_codepoint_t
		 * @param glyph hb_codepoint_t *
		 * @return hb_bool_t
		 */
		public static native boolean hb_font_get_glyph (long font, int unicode, int variation_selector, IntBuffer glyph); /*
			return (jboolean) hb_font_get_glyph((hb_font_t *) font, (hb_codepoint_t) unicode, (hb_codepoint_t) variation_selector, (hb_codepoint_t *) glyph);
		*/

		/**
		 * @see #FONT_EXTENTS_I_ASCENDER
		 * @param font hb_font_t *
		 * @param direction hb_direction_t
		 * @param extents hb_font_extents_t *
		 */
		public static native void hb_font_get_extents_for_direction (long font, int direction, int[] extents); /*
			hb_font_extents_t font_extents;
			hb_font_get_extents_for_direction((hb_font_t *) font, (hb_direction_t) direction, &font_extents);
			extents[0] = font_extents.ascender;
			extents[1] = font_extents.descender;
			extents[2] = font_extents.line_gap;
		*/

		/**
		 * @param font hb_font_t *
		 * @param glyph hb_codepoint_t
		 * @param direction hb_direction_t
		 * @param x hb_position_t *
		 * @param y hb_position_t *
		 */
		public static native void hb_font_get_glyph_advance_for_direction (long font, int glyph, int direction, IntBuffer x, IntBuffer y); /*
			hb_font_get_glyph_advance_for_direction((hb_font_t *) font, (hb_codepoint_t) glyph, (hb_direction_t) direction, (hb_position_t *) x, (hb_position_t *) y);
		*/

		/**
		 * @param font hb_font_t *
		 * @param glyph hb_codepoint_t
		 * @param direction hb_direction_t
		 * @param x hb_position_t *
		 * @param y hb_position_t *
		 */
		public static native void hb_font_get_glyph_origin_for_direction (long font, int glyph, int direction, IntBuffer x, IntBuffer y); /*
			hb_font_get_glyph_origin_for_direction((hb_font_t *) font, (hb_codepoint_t) glyph, (hb_direction_t) direction, (hb_position_t *) x, (hb_position_t *) y);
		*/

		/**
		 * @param font hb_font_t *
		 * @param first_glyph hb_codepoint_t
		 * @param second_glyph hb_codepoint_t
		 * @param direction hb_direction_t
		 * @param x hb_position_t *
		 * @param y hb_position_t *
		 */
		public static native void hb_font_get_glyph_kerning_for_direction (long font, int first_glyph, int second_glyph, int direction, IntBuffer x, IntBuffer y); /*
			hb_font_get_glyph_kerning_for_direction((hb_font_t *) font, (hb_codepoint_t) first_glyph, (hb_codepoint_t) second_glyph, (hb_direction_t) direction, (hb_position_t *) x, (hb_position_t *) y);
		*/

		/**
		 * @param font hb_font_t *
		 * @param x_scale int
		 * @param y_scale int
		 */
		public static native void hb_font_set_scale (long font, int x_scale, int y_scale); /*
			hb_font_set_scale((hb_font_t *) font, (int) x_scale, (int) y_scale);
		*/

		/**
		 * @param font hb_font_t *
		 * @param x_scale int *
		 * @param y_scale int *
		 */
		public static native void hb_font_get_scale (long font, IntBuffer x_scale, IntBuffer y_scale); /*
			hb_font_get_scale((hb_font_t *) font, (int *) x_scale, (int *) y_scale);
		*/

		/**
		 * A zero value means "no hinting in that direction"
		 * @param font hb_font_t *
		 * @param x_ppem unsigned int
		 * @param y_ppem unsigned int
		 */
		public static native void hb_font_set_ppem (long font, int x_ppem, int y_ppem); /*
			hb_font_set_ppem((hb_font_t *) font, (unsigned int) x_ppem, (unsigned int) y_ppem);
		*/

		/**
		 * @param font hb_font_t *
		 * @param x_ppem unsigned int *
		 * @param y_ppem unsigned int *
		 */
		public static native void hb_font_get_ppem (long font, IntBuffer x_ppem, IntBuffer y_ppem); /*
			hb_font_get_ppem((hb_font_t *) font, (unsigned int *) x_ppem, (unsigned int *) y_ppem);
		*/

		//region https://harfbuzz.github.io/harfbuzz-Shaping.html

		/**
		 * Similar to {@link #FONT_EXTENTS_I_ASCENDER} but for hb_feature_t struct.
		 *
		 * hb_tag_t
		 */
		public static final int FEATURE_TAG = 0;
		/** uint32_t */
		public static final int FEATURE_VALUE = 1;
		/** unsigned int */
		public static final int FEATURE_START = 2;
		/** unsigned int */
		public static final int FEATURE_END = 3;
		public static final int FEATURE_SIZE = 4;

		/**
		 * @param str const char * (+len int)
		 * @param feature hb_feature_t *
		 * @return hb_bool_t
		 */
		public static native boolean hb_feature_from_string (String str, int[] feature); /*
			hb_feature_t feat;
			if (hb_feature_from_string(str, env->GetStringUTFLength(obj_str), &feat)) {
				feature[0] = feat.tag;
				feature[1] = feat.value;
				feature[2] = feat.start;
				feature[3] = feat.end;
				return JNI_TRUE;
			}
			return JNI_FALSE;
		*/

		/**
		 * @param feature hb_feature_t *
		 */
		public static native String hb_feature_to_string (int[] feature); /*
			hb_feature_t feat = {
				.tag = (hb_tag_t) feature[0],
				.value = (uint32_t) feature[1],
				.start = (unsigned int) feature[2],
				.end = (unsigned int) feature[3] };
			char buf[128];
			hb_feature_to_string (&feat, buf, 128);
			return env->NewStringUTF(buf);
		*/

		public static int[] hb_feature_create (String tag, int value) {
			return new int[] {
					hb_tag_from_string(tag),
					value,
					0,
					~0 // Unsigned max
			};
		}

		public static final int[] NO_FEATURES = new int[0];

		/**
		 * @see #hb_shape(long, long, int[])
		 */
		public void shape(Buffer buffer, int[] features) {
			hb_shape(addr, buffer.addr, features);
		}

		/**
		 * @param font hb_font_t *
		 * @param buffer hb_buffer_t *
		 * @param features const hb_feature_t * (+ num_features unsigned int)
		 *                 Contains features, concatenated. Size should thus be divisible by 4.
		 *                 Not null.
		 */
		public static native void hb_shape (long font, long buffer, int[] features); /*
			jsize featureCount = env->GetArrayLength(obj_features) / 4;
			hb_feature_t feats[featureCount];
			for (int f = 0, i = 0; f < featureCount; f++) {
				feats[f].tag = (hb_tag_t) features[i++];
				feats[f].value = (uint32_t) features[i++];
				feats[f].start = (unsigned int) features[i++];
				feats[f].end = (unsigned int) features[i++];
			}

			hb_shape((hb_font_t *) font, (hb_buffer_t *) buffer, feats, featureCount);
		*/

		/**
		 * @param font hb_font_t *
		 * @param buffer hb_buffer_t *
		 * @param features const hb_feature_t * (+ num_features unsigned int)
		 *                 See {@link #hb_shape}
		 * @param shaper_list const char * const *
		 * @return hb_bool_t
		 */
		public static native boolean hb_shape_full (long font, long buffer, int[] features, String[] shaper_list); /*
			jsize featureCount = env->GetArrayLength(obj_features) / 4;
			hb_feature_t feats[featureCount];
			for (int f = 0, i = 0; f < featureCount; f++) {
				feats[f].tag = (hb_tag_t) features[i++];
				feats[f].value = (uint32_t) features[i++];
				feats[f].start = (unsigned int) features[i++];
				feats[f].end = (unsigned int) features[i++];
			}

			jsize shaperListSize = shaper_list == NULL ? 0 : env->GetArrayLength(shaper_list);
			const char *shaperList[shaperListSize + 1];
			shaperList[shaperListSize] = (char *) 0; // Null terminated
			for (int i = 0; i < shaperListSize; i++) {
				jstring shaperObj = (jstring) env->GetObjectArrayElement(shaper_list, i);
				shaperList[i] = env->GetStringUTFChars(shaperObj, 0);
			}

			jboolean result = (jboolean) hb_shape_full((hb_font_t *) font, (hb_buffer_t *) buffer, feats, featureCount, shaperList);

			for (int i = 0; i < shaperListSize; i++) {
				jstring shaperObj = (jstring) env->GetObjectArrayElement(shaper_list, i);
				env->ReleaseStringUTFChars(shaperObj, shaperList[i]);
			}

			return result;
		*/

		/**
		 * @return const char **
		 */
		public static native long hb_shape_list_shapers(); /*
			return (jlong) hb_shape_list_shapers();
		*/

		/**
		 * @return const char **
		 */
		public static native String[] hb_shape_list_shapers_strings(); /*
			// Zero terminated
			const char ** shapers = hb_shape_list_shapers();
			jsize shapersLen = 0;
			while (shapers[shapersLen] != 0) {
				shapersLen++;
			}

			jobjectArray result = env->NewObjectArray(shapersLen, env->FindClass("java/lang/String"), NULL);
			for (jsize i = 0; i < shapersLen; i++) {
				jstring str = env->NewStringUTF(shapers[i]);
				env->SetObjectArrayElement(result, i, str);
			}

			return result;
		*/

		//endregion

	}

	//endregion

	/**
	 * Those objects must be {@link #destroy()}ed when no longer used.
	 */
	public static abstract class Pointer implements Disposable {

		/**
		 * Address of the object in native code.
		 */
		public final long addr;

		Pointer(long addr) {
			assert addr != 0;
			this.addr = addr;
		}

		/**
		 * Increase ref-count so that one-more {@link #destroy()}
		 * call is needed to actually dispose the object.
		 */
		public abstract void reference();

		public abstract void destroy();

		/**
		 * Calls {@link #destroy()}.
		 */
		@Override
		public final void dispose() {
			destroy();
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;
			Pointer pointer = (Pointer) o;
			return addr == pointer.addr;
		}

		@Override
		public int hashCode() {
			final long addr = this.addr;
			return (int)(addr ^ (addr >>> 32));
			//return Long.hashCode(addr); -> inlined, as Java 8 only
		}

		@Override
		public String toString() {
			return getClass().getSimpleName()+"@"+addr;
		}
	}

	public static float toFloatFrom26p6(int fixedPoint26p6) {
		return fixedPoint26p6 / 64f;
	}

	public static long to26p6FromInt(int number) {
		return number * 64;
	}

	public static long to26p6FromFloat(float number) {
		return Math.round(number * 64.0);
	}
}
