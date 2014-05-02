/*
 * Copyright (C) 2014 Socratica LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.socratica.mobile;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.socratica.mobile.imagemap.ImageMapListener;
import com.socratica.mobile.imagemap.ImageMapResourcesCache;
import com.socratica.mobile.imagemap.PaintType;
import com.socratica.mobile.imagemap.R;
import com.socratica.mobile.imagemap.SimpleResourceCache;
import com.socratica.mobile.imagemap.XmlMapParser;

import static android.content.Context.WINDOW_SERVICE;

/**
 * Image map implementation for Android platform. Allows you show an image with
 * active areas within it. Supports scaling and scrolling of the map image. The
 * areas can be define using regular html image map format. Only "poly" shape is
 * supported now.
 * 
 * Example xml to define a map:
 * 
 * <map name="map"> <area href="50" shape="poly"
 * coords="176,95,248,107,241,161,183,153,162,148,168,120"/> </map>
 * 
 * The "href" attribute must be an integer number, this identifier is passed to
 * the {@link com.socratica.mobile.imagemap.ImageMapListener#onAreaClicked(int)} when someone clicks the area.
 * 
 * 
 * To use the ImageMap in your app you should define it within your layout. See
 * the following example. You must specify the "android:src" attribute pointing
 * to the image. You must specify the "map" attribute pointing to the xml file
 * with active areas defined.
 * 
 * Usage example:
 * 
 * <com.socratica.ImageMap android:src="@drawable/map50"
 * map="@xml/usa_map" android:layout_width="fill_parent"
 * android:layout_height="fill_parent" />
 * 
 * @author aectann@gmail.com (Konstantin Burov)
 * 
 */
public class ImageMap extends BigImage {

  public static final int GREEN_OVERLAY_COLOR = 0xff00ff00;
  public static final PaintType PAINT_TYPE_DEFAULT = new PaintType(Style.FILL,
      GREEN_OVERLAY_COLOR);
  public static final int RED_OVERLAY_COLOR = 0xffff0000;
  public static final PaintType PAINT_TYPE_DEFAULT_RED = new PaintType(
      Style.FILL, RED_OVERLAY_COLOR);

  private Path[] areaPaths;
  private Path path;
  private Region region;
  private ImageMapListener imageMapListener;
  private int[] areasToDraw;
  private Paint paint;
  private PaintType[] colorsToDraw;
  private boolean pathsInitialized;
  private int[] taskAreasIds;
  private int mapResource;
  private WindowManager manager;
  private int boundPad;
  private SimpleResourceCache simpleResourceCache;
  private RectF bounds;

  public ImageMap(final Context context, AttributeSet attrs) {
    super(context, attrs);
    manager = (WindowManager) context.getSystemService(WINDOW_SERVICE);
    TypedArray attributes = context.obtainStyledAttributes(attrs, R.styleable.ImageMap);
    mapResource = attributes.getResourceId(R.styleable.ImageMap_map, 0);
    boundPad = attributes.getDimensionPixelSize(R.styleable.ImageMap_selectionPadding, 50);
    if (mapResource == 0) {
      throw new IllegalStateException("map attribute must be specified");
    }

    new Thread(new Runnable() {
      public void run() {
        try {
          synchronized (ImageMap.this) {
            areaPaths = getCache().getAreaPaths(getContext(), mapResource);
            ImageMap.this.pathsInitialized = true;
            ImageMap.this.notify();
            if (boundsInitialized && taskAreasIds != null) {
              showAreasSync(taskAreasIds, colorsToDraw);
            }
          }
        } catch (Exception e) {
          throw new IllegalStateException("Failed to init image map areas", e);
        }
      }
    }).start();

    region = new Region();
    bounds = new RectF();
    path = new Path();
    areasToDraw = new int[0];

    paint = new Paint();
    paint.setAntiAlias(true);
    paint.setColor(GREEN_OVERLAY_COLOR);
    paint.setStrokeWidth(4);
  }

  public int getMapResource() {
    return mapResource;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (boundsInitialized && pathsInitialized) {
      if (areasToDraw != null) {
        int i = 0;
        for (int areaIndex : areasToDraw) {
          path.reset();
          path.addPath(areaPaths[areaIndex]);
          path.transform(getImageMatrix());
          if (colorsToDraw != null && colorsToDraw.length > i) {
            PaintType paintType = colorsToDraw[i];
            paint.setColor(paintType.color);
            paint.setStyle(paintType.style);
            i++;
          }
          canvas.drawPath(path, paint);
        }
        paint.setColor(GREEN_OVERLAY_COLOR);
      }
    } else {
      initBounds();
    }
  }

  @Override
  public void reset() {
    super.reset();
    areasToDraw = null;
    colorsToDraw = null;
  }

  @Override
  protected synchronized void initBounds() {
    super.initBounds();
    if (pathsInitialized && taskAreasIds != null) {
      showAreasSync(taskAreasIds, colorsToDraw);
    }
  }

  /**
   * Allows clients to set listener that will be aware of some events happening
   * on the map.
   * 
   * @param imageMapListener
   */
  public void setImageMapListener(ImageMapListener imageMapListener) {
    this.imageMapListener = imageMapListener;
  }

  /**
   * Highlights the area specified by the areaId with the green color.
   * Automatically adjusts scale and centers the area on the screen.
   * 
   * @param areaId
   *          - the id of area to show
   */
  public void showArea(int areaId) {
    showAreas(new int[] { areaId }, new PaintType[] { PAINT_TYPE_DEFAULT });
  }

  /**
   * Highlights the area specified by the areaId. Automatically adjusts scale
   * and centers the area on the screen.
   * 
   * @param areaId
   *          - the id of area to show
   */
  public void showArea(int areaId, PaintType paintType) {
    showAreas(new int[] { areaId }, new PaintType[] { paintType });
  }

  /**
   * Highlights the areas specified by showAreaIds array. Colors to use are
   * obtained from the colors array. If the colors array is null the default
   * green color will be used. If the colors array is less than showAreaIds --
   * the last color would be used for all areas that are out of the range.
   * 
   * @param showAreaIds
   *          - areas to show
   * @param colors
   *          - colors to use for highlighting
   */
  public void showAreas(final int[] showAreaIds, final PaintType[] colors) {
    if (!(pathsInitialized && boundsInitialized)) {
      taskAreasIds = showAreaIds;
      colorsToDraw = colors;
      return;
    }
    postDelayed(new Runnable() {
      public void run() {
        showAreasSync(showAreaIds, colors);
      }
    }, 0);
  }

  void showAreasSync(final int[] showAreaIds, final PaintType[] colors) {
    Path p = new Path();
    areasToDraw = new int[showAreaIds.length];
    colorsToDraw = colors;
    int j = 0;
    for (int i : showAreaIds) {
      p.addPath(areaPaths[i]);
      areasToDraw[j++] = i;
    }
    p.computeBounds(bounds, false);

    DisplayMetrics outMetrics = new DisplayMetrics();
    manager.getDefaultDisplay().getMetrics(outMetrics);

    float w = bounds.width() + boundPad;
    float h = bounds.height() + boundPad;
    scale = Math.min(viewWidth / w, viewHeight / h);
    dx = (-bounds.left - bounds.width() / 2) * scale + viewWidth / 2;
    dy = (-bounds.top - bounds.height() / 2) * scale + viewHeight / 2;
    updateMatrix();
  }

  public int getDataId(int areaId) {
    return getCache().getDataId(getContext(), mapResource, areaId);
  }

  public int getAreaId(int dataId) {
    return getCache().getAreaId(getContext(), mapResource, dataId);
  }

  private ImageMapResourcesCache getCache() {
    Context applicationContext = getContext().getApplicationContext();
    if (applicationContext instanceof ImageMapResourcesCache) {
      return (ImageMapResourcesCache) applicationContext;
    } else {
      if (simpleResourceCache == null) {
        simpleResourceCache = new SimpleResourceCache(new XmlMapParser());
      }
      return simpleResourceCache;
    }
  }

  @Override
  public boolean onSingleTapUp(MotionEvent e) {
    boolean superCallResult = super.onSingleTapUp(e);
    if (!superCallResult) {
      int x = (int) ((e.getX() - dx) / scale);
      int y = (int) ((e.getY() - dy) / scale);
      float targetScale = initScale * 3;
      if (scale < targetScale) {
        scaleTo(x, y, targetScale);
      } else {
        int length = areaPaths.length;
        Region region = this.region;
        RectF bounds = this.bounds;
        Path[] areaPaths = this.areaPaths;
        for (int i = 0; i < length; i++) {
          Path areaPath = areaPaths[i];
          areaPath.computeBounds(bounds, false);
          region.set((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
          if (region.contains(x, y)) {
            region.setPath(areaPath, region);
            if (region.contains(x, y)) {
              if (imageMapListener != null) {
                imageMapListener.onAreaClicked(i);
              }
              return true;
            }
          }
        }
      }
    }
    return superCallResult;
  }
}
