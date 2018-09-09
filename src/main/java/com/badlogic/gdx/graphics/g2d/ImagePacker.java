/* *****************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.graphics.g2d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Pixmap.Blending;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.util.Comparator;

/** Packs {@link Pixmap pixmaps} into one or more {@link Page pages} to generate an atlas of pixmap instances. Provides means to
 * directly convert the pixmap atlas to a {@link TextureAtlas}. The packer supports padding and border pixel duplication,
 * specified during construction. The packer supports incremental inserts and updates of TextureAtlases generated with this class.
 * How bin packing is performed can be customized via {@link PackStrategy}.
 * <p>
 * One-off usage:
 * 
 * <pre>
 * // 512x512 pixel pages, RGB565 format, 2 pixels of padding, border duplication
 * PixmapPacker packer = new PixmapPacker(512, 512, Format.RGB565, 2, true);
 * packer.pack(&quot;First Pixmap&quot;, pixmap1);
 * packer.pack(&quot;Second Pixmap&quot;, pixmap2);
 * TextureAtlas atlas = packer.generateTextureAtlas(TextureFilter.Nearest, TextureFilter.Nearest, false);
 * packer.dispose();
 * // ...
 * atlas.dispose();
 * </pre>
 * 
 * With this usage pattern, disposing the packer will not dispose any pixmaps used by the texture atlas. The texture atlas must
 * also be disposed when no longer needed.
 * 
 * Incremental texture atlas usage:
 * 
 * <pre>
 * // 512x512 pixel pages, RGB565 format, 2 pixels of padding, no border duplication
 * PixmapPacker packer = new PixmapPacker(512, 512, Format.RGB565, 2, false);
 * TextureAtlas atlas = new TextureAtlas();
 * 
 * // potentially on a separate thread, e.g. downloading thumbnails
 * synchronized (packer) {
 *     packer.pack(&quot;thumbnail&quot;, thumbnail);
 * }
 * 
 * // on the rendering thread, every frame
 * synchronized (packer) {
 *     packer.updateTextureAtlas(atlas, TextureFilter.Linear, TextureFilter.Linear, false);
 * }
 *
 * // ...
 * atlas.dispose();
 * </pre>
 * 
 * Pixmap-only usage:
 * 
 * <pre>
 * PixmapPacker packer = new PixmapPacker(512, 512, Format.RGB565, 2, true);
 * packer.pack(&quot;First Pixmap&quot;, pixmap1);
 * packer.pack(&quot;Second Pixmap&quot;, pixmap2);
 * 
 * // do something interesting with the resulting pages
 * for (Page page : packer.getPages()) {
 * 	// ...
 * }
 * 
 * packer.dispose();
 * </pre>
 * 
 * @author mzechner
 * @author Nathan Sweet
 * @author Rob Rendell
 * @author Jan Polák */
public class ImagePacker implements Disposable {
	private boolean disposed;

	/** Size of a page. */
	public final int pageWidth, pageHeight;
	/** Pixmap/texture format used for pages. */
	public final Format pageFormat;
	/** Filter used by textures of pages. */
	public final TextureFilter minFilter, magFilter;

	/** The number of blank pixels to insert between pixmaps. Applies only to subsequent additions. */
	public int padding;
	/** Duplicate the border pixels of the inserted images to avoid seams when rendering with bi-linear filtering on.
	 * Applies only to subsequent additions. */
	public boolean duplicateBorder;

	/** If true, when a pixmap is packed to a page that has a texture, the portion of the texture where the pixmap
	 * was packed is immediately updated using glTexSubImage2D, so that subsequent {@link #updatePageTextures()}
	 * is a no-op (but make sure to still call it, as it isn't guaranteed).
	 * When packing many pixmaps, this may be slower than re-uploading the whole texture.
	 * <b>NOTE:</b> This setting is ignored if {@link #duplicateBorder} is true. */
	public boolean packToTexture = false;

	/** The default <code>color</code> of the {@link Page} background, applied when a new one created.
	 * Helps to avoid texture bleeding or to highlight the page for debugging.
	 * @see Page#Page(ImagePacker) */
	public final Color transparentColor = new Color(0f, 0f, 0f, 0f);

	/** The {@link Page} instances created so far. */
	public final Array<Page> pages = new Array<>();

	/** Used strategy. */
	public final PackStrategy packStrategy;

	/** Uses {@link GuillotineStrategy} and {@link TextureFilter#Linear} filters.
	 * @see ImagePacker#ImagePacker(int, int, Format, TextureFilter, TextureFilter, int, boolean, PackStrategy) */
	public ImagePacker(int pageWidth, int pageHeight, Format pageFormat, int padding, boolean duplicateBorder) {
		this(pageWidth, pageHeight, pageFormat, TextureFilter.Linear, TextureFilter.Linear, padding, duplicateBorder, GuillotineStrategy.INSTANCE);
	}

	/** Creates a new ImagePacker which will insert all supplied pixmaps into one or more <code>pageWidth</code> by
	 * <code>pageHeight</code> pixmaps using the specified strategy.
	 * @param minFilter to use in pages. Page textures will have mipmaps generated based on {@link TextureFilter#isMipMap()}.
	 * @param magFilter to use in pages
	 * @param padding see {@link #padding}
	 * @param duplicateBorder see {@link #duplicateBorder}   */
	public ImagePacker(int pageWidth, int pageHeight, Format pageFormat, TextureFilter minFilter, TextureFilter magFilter, int padding, boolean duplicateBorder,
					   PackStrategy packStrategy) {
		this.pageWidth = pageWidth;
		this.pageHeight = pageHeight;
		this.pageFormat = pageFormat;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.padding = padding;
		this.duplicateBorder = duplicateBorder;
		this.packStrategy = packStrategy;
	}

	/** Inserts the pixmap.
	 * If you have multiple pixmaps to pack at the same time, sort them using {@link PackStrategy#compare(int, int, int, int)} first,
	 * to get them into optimal order for the selected strategy. Some packing strategies may rely heavily on the order
	 * in which images are added.
	 * <p>
	 * May be called from non GL thread if no other thread is using any part of the packer and only when
	 * {@link #packToTexture} is {@code false}.
	 *
	 * @param image to be packed, not null
	 * @param resultArea will be filled with the location at which the image has been packed to.
	 *                   Only integer coordinates will be used. Not null, but can be reused between calls.
	 * @return page to which the <code>image</code> has been stored to. One of {@link #pages}.
	 * @throws GdxRuntimeException in case the image did not fit due to the page size being too small */
	public Page pack (Pixmap image, Rectangle resultArea) {
		if (disposed)
			throw new GdxRuntimeException("Already disposed");

		resultArea.set(0, 0, image.getWidth(), image.getHeight());
		if (resultArea.getWidth() > pageWidth || resultArea.getHeight() > pageHeight) {
			throw new GdxRuntimeException("Page size too small for pixmap");
		}

		final Page page = packStrategy.pack(this, resultArea);

		final int rectX = (int) resultArea.x, rectY = (int) resultArea.y,
				rectWidth = (int) resultArea.width, rectHeight = (int) resultArea.height;

		if (packToTexture && !duplicateBorder && page.texture != null && !page.dirty) {
			page.texture.bind();
			Gdx.gl.glTexSubImage2D(page.texture.glTarget, 0, rectX, rectY, rectWidth, rectHeight, image.getGLFormat(),
				image.getGLType(), image.getPixels());
		} else
			page.dirty = true;

		page.pixmap.drawPixmap(image, rectX, rectY);

		if (duplicateBorder) {
			int imageWidth = image.getWidth(), imageHeight = image.getHeight();
			// Copy corner pixels to fill corners of the padding.
			page.pixmap.drawPixmap(image, 0, 0, 1, 1, rectX - 1, rectY - 1, 1, 1);
			page.pixmap.drawPixmap(image, imageWidth - 1, 0, 1, 1, rectX + rectWidth, rectY - 1, 1, 1);
			page.pixmap.drawPixmap(image, 0, imageHeight - 1, 1, 1, rectX - 1, rectY + rectHeight, 1, 1);
			page.pixmap.drawPixmap(image, imageWidth - 1, imageHeight - 1, 1, 1, rectX + rectWidth, rectY + rectHeight, 1, 1);
			// Copy edge pixels into padding.
			page.pixmap.drawPixmap(image, 0, 0, imageWidth, 1, rectX, rectY - 1, rectWidth, 1);
			page.pixmap.drawPixmap(image, 0, imageHeight - 1, imageWidth, 1, rectX, rectY + rectHeight, rectWidth, 1);
			page.pixmap.drawPixmap(image, 0, 0, 1, imageHeight, rectX - 1, rectY, 1, rectHeight);
			page.pixmap.drawPixmap(image, imageWidth - 1, 0, 1, imageHeight, rectX + rectWidth, rectY, 1, rectHeight);
		}

		page.statisticPixmapsPacked++;
		page.statisticPixelsUsed += rectWidth * rectHeight;

		return page;
	}

	/** Disposes any pixmap pages which don't have a texture.
	 * Page pixmaps that have a texture will not be disposed until their texture is disposed. */
	public void dispose () {
		for (Page page : pages) {
			if (page.texture == null) {
				page.pixmap.dispose();
			}
		}
		disposed = true;
	}

	/** Calls {@link Page#updateTexture() updateTexture} for each page and adds a region to
	 * the specified array for each page texture. */
	public  void updateTextureRegions (Array<TextureRegion> regions) {
		updatePageTextures();
		while (regions.size < pages.size)
			regions.add(new TextureRegion(pages.get(regions.size).texture));
	}

	/** Calls {@link Page#updateTexture() updateTexture} for each page and adds a region to
	 * the specified array for each page texture. */
	public  void updateTextures (Array<Texture> regions) {
		updatePageTextures();
		while (regions.size < pages.size)
			regions.add(pages.get(regions.size).texture);
	}

	/** Calls {@link Page#updateTexture() updateTexture} for each page. */
	public void updatePageTextures () {
		for (Page page : pages)
			page.updateTexture();
	}

	/** Contains additional debug info about how much the pages are filled up. */
	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append(getClass().getSimpleName()).append('(').append(packStrategy.getClass().getSimpleName()).append(", ").append(pages.size).append(" page(s)):");
		int i = 0;
		for (Page page : pages) {
			sb.append("\t[").append(i++).append("] ")
					.append(Math.round(page.statisticPixelsUsed * 100f / (float)(pageWidth * pageHeight)))
					.append("% filled by ").append(page.statisticPixmapsPacked).append(" pixmaps\n");
		}
		return sb.toString();
	}

	/** @author mzechner
	 * @author Nathan Sweet
	 * @author Rob Rendell */
	public static class Page {
		protected final ImagePacker packer;
		private Texture texture = null;
		private boolean dirty = false;

		/** Backing pixmap. */
		public final Pixmap pixmap;

		/** Statistical variable for tuning: How many pixels are already used? */
		public int statisticPixelsUsed = 0;
		/** Statistical variable for tuning: How many pixmaps were added to this page? */
		public int statisticPixmapsPacked = 0;

		/** Creates a new page filled with the color provided by the {@link ImagePacker#transparentColor}
		 * @param packer owner */
		public Page(ImagePacker packer) {
			this.packer = packer;
			pixmap = new Pixmap(packer.pageWidth, packer.pageHeight, packer.pageFormat);
			pixmap.setBlending(Blending.None);
			pixmap.setColor(packer.transparentColor);
			pixmap.fill();
		}

		/** Returns the texture for this page, or null if the texture has not been created.
		 * @see #updateTexture() */
		public Texture getTexture() {
			return texture;
		}

		/** Create the texture if it has not been created, or re-upload the entire page pixmap to the texture
		 * if the pixmap has changed since this method was last called.
		 * @return true if the texture was created or re-uploaded. */
		protected boolean updateTexture() {
			if (texture != null) {
				if (!dirty) return false;
				texture.load(texture.getTextureData());
			} else {
				texture = new Texture(new PixmapTextureData(pixmap, pixmap.getFormat(), packer.minFilter.isMipMap(), false, true)) {
					@Override
					public void dispose () {
						super.dispose();
						pixmap.dispose();
					}
				};
				texture.setFilter(packer.minFilter, packer.magFilter);
			}
			dirty = false;
			return true;
		}
	}

	/** Implements the strategy for choosing the page and location for each rectangle.
	 * @author Nathan Sweet */
	public interface PackStrategy {

		/** Use this comparison to sort pixmaps before passing them to be packed for better packing efficiency.
		 * Parameters are dimensions of first and second pixmap.
		 * @see Comparator */
		int compare(int width1, int height1, int width2, int height2);

		/** Returns the page the rectangle should be placed in and modifies the specified rectangle position. */
		Page pack(ImagePacker packer, Rectangle rect);
	}

	/** Does bin packing by inserting to the right or below previously packed rectangles.
	 * This is good at packing arbitrarily sized images.
	 * @author mzechner
	 * @author Nathan Sweet
	 * @author Rob Rendell */
	public static final class GuillotineStrategy implements PackStrategy {

		/** This class is a singleton and this is its instance. */
		public static final GuillotineStrategy INSTANCE = new GuillotineStrategy();

		private GuillotineStrategy() {
		}

		@Override
		public int compare(int width1, int height1, int width2, int height2) {
			return Integer.compare(Math.max(width1, height1), Math.max(width2, height2));
		}

		public Page pack (ImagePacker packer, Rectangle rect) {
			GuillotinePage page;
			if (packer.pages.size == 0) {
				// Add a page if empty.
				page = new GuillotinePage(packer);
				packer.pages.add(page);
			} else {
				// Always try to pack into the last page.
				page = (GuillotinePage)packer.pages.peek();
			}

			int padding = packer.padding;
			rect.width += padding;
			rect.height += padding;
			Node node = insert(page.root, rect);
			if (node == null) {
				// Didn't fit, pack into a new page.
				page = new GuillotinePage(packer);
				packer.pages.add(page);
				node = insert(page.root, rect);
			}
			node.full = true;
			rect.set(node.rect.x, node.rect.y, node.rect.width - padding, node.rect.height - padding);
			return page;
		}

		private Node insert (Node node, Rectangle rect) {
			if (!node.full && node.leftChild != null && node.rightChild != null) {
				Node newNode = insert(node.leftChild, rect);
				if (newNode == null) newNode = insert(node.rightChild, rect);
				return newNode;
			} else {
				if (node.full) return null;
				if (node.rect.width == rect.width && node.rect.height == rect.height) return node;
				if (node.rect.width < rect.width || node.rect.height < rect.height) return null;

				node.leftChild = new Node();
				node.rightChild = new Node();

				int deltaWidth = (int)node.rect.width - (int)rect.width;
				int deltaHeight = (int)node.rect.height - (int)rect.height;
				if (deltaWidth > deltaHeight) {
					node.leftChild.rect.x = node.rect.x;
					node.leftChild.rect.y = node.rect.y;
					node.leftChild.rect.width = rect.width;
					node.leftChild.rect.height = node.rect.height;

					node.rightChild.rect.x = node.rect.x + rect.width;
					node.rightChild.rect.y = node.rect.y;
					node.rightChild.rect.width = node.rect.width - rect.width;
					node.rightChild.rect.height = node.rect.height;
				} else {
					node.leftChild.rect.x = node.rect.x;
					node.leftChild.rect.y = node.rect.y;
					node.leftChild.rect.width = node.rect.width;
					node.leftChild.rect.height = rect.height;

					node.rightChild.rect.x = node.rect.x;
					node.rightChild.rect.y = node.rect.y + rect.height;
					node.rightChild.rect.width = node.rect.width;
					node.rightChild.rect.height = node.rect.height - rect.height;
				}

				return insert(node.leftChild, rect);
			}
		}

		private static final class Node {
			public Node leftChild;
			public Node rightChild;
			public final Rectangle rect = new Rectangle();
			public boolean full;
		}

		private static class GuillotinePage extends Page {
			Node root;

			public GuillotinePage (ImagePacker packer) {
				super(packer);
				root = new Node();
				root.rect.x = packer.padding;
				root.rect.y = packer.padding;
				root.rect.width = packer.pageWidth - packer.padding * 2;
				root.rect.height = packer.pageHeight - packer.padding * 2;
			}
		}
	}

	/** Does bin packing by inserting in rows. This is good at packing images that have similar heights.
	 * @author Nathan Sweet */
	public static final class SkylineStrategy implements PackStrategy {

		/** This class is a singleton and this is its instance. */
		public static final SkylineStrategy INSTANCE = new SkylineStrategy();

		private SkylineStrategy() {
		}

		@Override
		public int compare(int width1, int height1, int width2, int height2) {
			return Integer.compare(height1, height2);
		}

		public Page pack (ImagePacker packer, Rectangle rect) {
			int padding = packer.padding;
			int pageWidth = packer.pageWidth - padding * 2, pageHeight = packer.pageHeight - padding * 2;
			int rectWidth = (int)rect.width + padding, rectHeight = (int)rect.height + padding;
			for (int i = 0, n = packer.pages.size; i < n; i++) {
				SkylinePage page = (SkylinePage)packer.pages.get(i);
				SkylinePage.Row bestRow = null;
				// Fit in any row before the last.
				for (int ii = 0, nn = page.rows.size - 1; ii < nn; ii++) {
					SkylinePage.Row row = page.rows.get(ii);
					if (row.x + rectWidth >= pageWidth) continue;
					if (row.y + rectHeight >= pageHeight) continue;
					if (rectHeight > row.height) continue;
					if (bestRow == null || row.height < bestRow.height) bestRow = row;
				}
				if (bestRow == null) {
					// Fit in last row, increasing height.
					SkylinePage.Row row = page.rows.peek();
					if (row.y + rectHeight >= pageHeight) continue;
					if (row.x + rectWidth < pageWidth) {
						row.height = Math.max(row.height, rectHeight);
						bestRow = row;
					} else {
						// Fit in new row.
						bestRow = new SkylinePage.Row();
						bestRow.y = row.y + row.height;
						bestRow.height = rectHeight;
						page.rows.add(bestRow);
					}
				}
				rect.x = bestRow.x;
				rect.y = bestRow.y;
				bestRow.x += rectWidth;
				return page;
			}
			// Fit in new page.
			SkylinePage page = new SkylinePage(packer);
			packer.pages.add(page);
			SkylinePage.Row row = new SkylinePage.Row();
			row.x = padding + rectWidth;
			row.y = padding;
			row.height = rectHeight;
			page.rows.add(row);
			rect.x = padding;
			rect.y = padding;
			return page;
		}

		private static final class SkylinePage extends Page {
			Array<Row> rows = new Array<>();

			SkylinePage (ImagePacker packer) {
				super(packer);
			}

			static final class Row {
				int x, y, height;
			}
		}
	}
}
