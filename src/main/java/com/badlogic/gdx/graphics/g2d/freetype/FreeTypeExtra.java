package com.badlogic.gdx.graphics.g2d.freetype;

/**
 *
 */
@SuppressWarnings("unused")
public final class FreeTypeExtra {
	// @off
	/*JNI
	#include <ft2build.h>
	#include FT_FREETYPE_H
	#include FT_STROKER_H
	 */


	/** https://www.freetype.org/freetype2/docs/reference/ft2-base_interface.html#FT_Size_Request_Type */
	public static final int FT_Size_Request_Type_FT_SIZE_REQUEST_TYPE_NOMINAL = 0;
	public static final int FT_Size_Request_Type_FT_SIZE_REQUEST_TYPE_REAL_DIM = 1;
	public static final int FT_Size_Request_Type_FT_SIZE_REQUEST_TYPE_BBOX = 2;
	public static final int FT_Size_Request_Type_FT_SIZE_REQUEST_TYPE_CELL = 3;
	public static final int FT_Size_Request_Type_FT_SIZE_REQUEST_TYPE_SCALES = 4;

	public static int FT_Request_Size(FreeType.Face face, int type, long width, long height, int horiResolution, int vertResolution) {
		return FT_Request_Size(face.address, type, width, height, horiResolution, vertResolution);
	}

	/** See:
	 * https://www.freetype.org/freetype2/docs/reference/ft2-base_interface.html#FT_Request_Size
	 * https://www.freetype.org/freetype2/docs/reference/ft2-base_interface.html#FT_Size_RequestRec */
	public native static int FT_Request_Size(long face, int type, long width, long height, int horiResolution, int vertResolution);/*
		FT_Size_RequestRec request;
		request.type = (FT_Size_Request_Type) type;
		request.width = (FT_Long) width;
		request.height = (FT_Long) height;
		request.horiResolution = (FT_UInt) horiResolution;
		request.vertResolution = (FT_UInt) vertResolution;
		return (jint) FT_Request_Size((FT_Face)face, &request);
	*/
}
