/*
 * Copyright 2023 Alexander Rvachev.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bitmapdecoder;

import android.content.res.*;
import android.graphics.*;
import android.graphics.Bitmap.Config;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.StateSet;
import android.util.TypedValue;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A subclass of {@link ShaderDrawable}, that can be used in XML
 */
public class IndexedDrawable extends ShaderDrawable {
    private static final String TAG = "pngs";

    private static final int DISABLE_TILING = 0xffffffff;

    public IndexedDrawable(@NonNull Resources resources, @DrawableRes int resourceId) {
        this();

        try {
            decode(resources, PngSupport.loadValue(resources, resourceId));
        } catch (IOException ioe) {
            throw new IllegalArgumentException(ioe);
        }
    }

    public IndexedDrawable(@NonNull ByteBuffer buffer) {
        this();

        if (!decode(buffer)) {
            throw new IllegalArgumentException(PngSupport.ERROR_CODE_DECODING_FAILED);
        }
    }

    public IndexedDrawable() {
        super(new Paint(), -1, -1, false);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet set, Resources.Theme theme) throws IOException, XmlPullParserException {
        super.inflate(r, parser, set, theme);

        final TypedArray typedArray;
        if (theme == null) {
            typedArray = r.obtainAttributes(set, R.styleable.IndexedDrawable);
        } else {
            typedArray = theme.obtainStyledAttributes(set, R.styleable.IndexedDrawable, 0, 0);
        }

        final TypedValue tv = new TypedValue();
        try {
            final boolean tiled;
            final int tileMode;

            if (typedArray.getValue(R.styleable.IndexedDrawable_android_tileMode, tv)) {
                tileMode = tv.data == DISABLE_TILING ? 0 : tv.data;
                tiled = tv.data != DISABLE_TILING;
            } else {
                tileMode = 0;
                tiled = false;
            }

            if (typedArray.getValue(R.styleable.IndexedDrawable_android_src, tv)) {
                if (decodePreview(r, typedArray) || decode(r, tv, tileMode)) {
                    float scale = applyDensity(r.getDisplayMetrics(), tv.density);

                    ColorStateList tintList = typedArray.getColorStateList(R.styleable.IndexedDrawable_android_tint);

                    int attributeConfigurations = typedArray.getChangingConfigurations();

                    if (tintList != null || attributeConfigurations != 0 || scale != 1.0 || tiled) {
                        state = new IndexedDrawableState(state, tintList, attributeConfigurations, scale, tiled);

                        if (tintList != null) {
                            applyTint(StateSet.NOTHING, getState(), tintList, true);
                        }
                    }

                    return;
                }
            }

            throw new XmlPullParserException(PngSupport.ERROR_CODE_DECODING_FAILED);
        } finally {
            typedArray.recycle();
        }
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        this.state = new IndexedDrawableState(state, tint, state.getChangingConfigurations(),
                state.getScale(), state.isTiled());

        applyTint(getState(), getState(), tint, true);
    }

    @Override
    public boolean isStateful() {
        final ColorStateList tint = getTint();

        return tint != null && tint.isStateful();
    }

    @Override
    public boolean setState(int[] stateSet) {
        final int[] oldState = getState();

        boolean changed = super.setState(stateSet);

        final ColorStateList stateList = getTint();
        if (stateList != null) {
            changed = applyTint(oldState, stateSet, stateList, false);
        }

        return changed;
    }

    private boolean applyTint(int[] oldState, int[] currentState, ColorStateList stateList, boolean force) {
        if (stateList == null) {
            setColorFilter(null);
            return true;
        }

        final int defaultColor = stateList.getDefaultColor();
        final int currentColor = stateList.getColorForState(oldState, defaultColor);
        final int newColor = stateList.getColorForState(currentState, defaultColor);

        if (force || newColor != currentColor) {
            setColorFilter(new PorterDuffColorFilter(newColor, PorterDuff.Mode.SRC_IN));
            return true;
        }

        return false;
    }

    private float applyDensity(DisplayMetrics metrics, int sDensity) {
        int tDensity = metrics.densityDpi;

        if (sDensity == 0) {
            sDensity = DisplayMetrics.DENSITY_MEDIUM;
        }

        if (tDensity == sDensity) return 1.0f;

        if (sDensity == TypedValue.DENSITY_NONE || tDensity == TypedValue.DENSITY_NONE) {
            return 1.0f;
        }

        int w = state.width;
        int h = state.height;

        state = new State(state.paint, scale(w, sDensity, tDensity), scale(h, sDensity, tDensity), state.opaque);

        return state.width / (float) w;
    }

    private static int scale(int size, int sourceDensity, int targetDensity) {
        return ((size * targetDensity) + (sourceDensity >> 1)) / sourceDensity;
    }

    private ColorStateList getTint() {
        if (!(state instanceof IndexedDrawableState)) {
            return null;
        }

        return ((IndexedDrawableState) state).tint;
    }

    private void decode(Resources r, TypedValue tv) throws IOException {
        if (!decode(r, tv, 0)) {
            throw new IOException(PngSupport.ERROR_CODE_DECODING_FAILED);
        }

        float scale = applyDensity(r.getDisplayMetrics(), tv.density);
        if (scale != 1.0) {
            state = new IndexedDrawableState(state, getTint(), state.getChangingConfigurations(),
                    scale, state.isTiled());
        }
    }

    private boolean decode(Resources r, TypedValue tv, int tileMode) throws IOException {
        final AssetManager am = r.getAssets();

        try (AssetFileDescriptor stream = am.openNonAssetFd(tv.assetCookie, tv.string.toString())) {
            final ByteBuffer buffer = PngSupport.loadIndexedPng(stream);
            if (decode(buffer, tileMode)) {
                return true;
            }
            return decodeFallback(r, tv, tileMode);
        }
    }

    private boolean decode(ByteBuffer buffer) {
        return decode(buffer, 0);
    }

    private boolean decode(ByteBuffer buffer, int tileMode) {
        final PngDecoder.PngHeaderInfo headerInfo = PngDecoder.getImageInfo(buffer);
        if (headerInfo == null) {
            return false;
        }
        final Bitmap imageBitmap = Bitmap.createBitmap(headerInfo.width, headerInfo.height, Config.ALPHA_8);
        final PngDecoder.DecodingResult result = PngDecoder.decodeIndexed(buffer, imageBitmap, PngDecoder.OPTION_DECODE_AS_MASK);
        final Paint paint = result == null ? null : PngSupport.createPaint(result, imageBitmap, tileMode);
        if (paint != null) {
            state = new State(paint, headerInfo.width, headerInfo.height, result.isOpaque());
            return true;
        }
        // fall back to ARGB_8888 Bitmap
        imageBitmap.recycle();
        return false;
    }

    private static Shader.TileMode toTileMode(int tileMode) {
        return Shader.TileMode.values()[tileMode];
    }

    private boolean decodePreview(Resources r, TypedArray typedArray) {
        return PngSupport.isPreview(typedArray);
    }

    private boolean decodeFallback(Resources r, TypedValue tv, int tileMode) {
        final Bitmap bitmap = BitmapFactory.decodeResource(r, tv.resourceId);
        if (bitmap == null) {
            return false;
        }
        final Paint fallback = new Paint();
        final Shader.TileMode tiled = toTileMode(tileMode);
        fallback.setShader(new BitmapShader(bitmap, tiled, tiled));
        state = new State(fallback, bitmap.getWidth(), bitmap.getHeight(), !bitmap.hasAlpha());
        return true;
    }

    private static class IndexedDrawableState extends State {
        final ColorStateList tint;

        private final int configurations;
        private final float scale;
        private final boolean tiled;

        private IndexedDrawableState(@NonNull State state,
                                     ColorStateList tint,
                                     int configurations,
                                     float scale,
                                     boolean tiled) {
            super(state.paint, state.width, state.height, state.opaque);

            this.tint = tint;
            this.scale = scale;
            this.tiled = tiled;
            this.configurations = configurations | getConfigurations(tint);
        }

        @Override
        public int getChangingConfigurations() {
            return configurations;
        }

        @Override
        protected boolean isTiled() {
            return tiled;
        }

        @Override
        protected float getScale() {
            return scale;
        }

        private static int getConfigurations(ColorStateList colorStateList) {
            if (Build.VERSION.SDK_INT >= 23 && colorStateList != null) {
                return colorStateList.getChangingConfigurations();
            }

            return 0;
        }
    }
}