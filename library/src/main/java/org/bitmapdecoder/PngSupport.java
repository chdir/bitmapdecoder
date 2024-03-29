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

import android.annotation.TargetApi;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.*;
import androidx.annotation.*;
import org.bitmapdecoder.PngDecoder.DecodingResult;
import org.bitmapdecoder.PngDecoder.PngHeaderInfo;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

import static org.bitmapdecoder.PngDecoder.OPTION_DECODE_AS_MASK;
import static org.bitmapdecoder.PngDecoder.OPTION_EXTRACT_MASK;

public final class PngSupport {
    private static final String TAG = "pngs";

    public static final String ERROR_CODE_DECODING_FAILED      = "Error 0001";
    public static final String ERROR_CODE_BAD_ATTRIBUTE        = "Error 0002";
    public static final String ERROR_CODE_FAILED_ALPHA8_UPLOAD = "Error 0005";
    public static final String ERROR_CODE_STATEFUL_TINT_LIST   = "Error 0006";
    public static final String ERROR_SOFTWARE_CANVAS           = "Error 0007";

    private PngSupport() {}

    public static final int FLAG_TILED    = 0b001;
    public static final int FLAG_MIRRORED = 0b010;

    @IntDef(value = { FLAG_TILED, FLAG_MIRRORED, OPTION_DECODE_AS_MASK, OPTION_EXTRACT_MASK }, flag = true)
    @Retention(RetentionPolicy.SOURCE)
    public static @interface Options {
    }

    public static @Nullable Drawable getDrawable(@NonNull ByteBuffer source, @Options int options) {
        PngHeaderInfo headerInfo = PngDecoder.getImageInfo(source);
        if (headerInfo == null || !headerInfo.isPaletteOrGreyscale()) {
            return null;
        }
        return createDrawable(source, headerInfo, options);
    }

    public static @Nullable Paint getPaint(@NonNull ByteBuffer source, @Options int options) {
        PngHeaderInfo headerInfo = PngDecoder.getImageInfo(source);
        if (headerInfo == null || !headerInfo.isPaletteOrGreyscale()) {
            return null;
        }
        return createPaint(source, headerInfo, options);
    }

    @TargetApi(33)
    public static @Nullable Shader getShader(@NonNull ByteBuffer source, @Options int options) {
        PngHeaderInfo headerInfo = PngDecoder.getImageInfo(source);
        if (headerInfo == null || !headerInfo.isIndexed()) {
            return null;
        }
        return createShader(source, headerInfo, options);
    }

    private static Paint createPaint(ByteBuffer source, PngHeaderInfo headerInfo, @Options int options) {
        final Bitmap rawImageBitmap = Bitmap.createBitmap(headerInfo.width, headerInfo.height, Bitmap.Config.ALPHA_8);

        final DecodingResult result = PngDecoder.decodeIndexed(source, rawImageBitmap, options | PngDecoder.DEFAULT_DECODER_FLAGS);
        if (result == null) {
            return null;
        }
        return createPaint(result, rawImageBitmap, options);
    }

    private static Drawable createDrawable(ByteBuffer source, PngHeaderInfo headerInfo, @Options int options) {
        final Bitmap rawImageBitmap = Bitmap.createBitmap(headerInfo.width, headerInfo.height, Bitmap.Config.ALPHA_8);

        final DecodingResult result = PngDecoder.decodeIndexed(source, rawImageBitmap, options | PngDecoder.DEFAULT_DECODER_FLAGS);
        if (result == null) {
            return null;
        }
        final Paint paint = createPaint(result, rawImageBitmap, options);
        if (paint == null) {
            return null;
        }
        return new ShaderDrawable(paint, headerInfo.width, headerInfo.height, result);
    }

    static int makeStateSpec(boolean isOpaque) {
        if (isOpaque) {
            return ShaderDrawable.OPAQUE_MASK;
        } else {
            return 0;
        }
    }

    static int makeStateSpec(PngDecoder.DecodingResult result) {
        if (result.isOpaque()) {
            return ShaderDrawable.OPAQUE_MASK;
        } else if (result.decodedAsMask()) {
            int color = color(result.palette.array());
            return color & ShaderDrawable.COLOR_MASK;
        } else {
            return 0;
        }
    }

    static Paint createPaint(@NonNull DecodingResult result, Bitmap rawImageBitmap, @Options int options) {
        final Paint paint = new Paint();

        if (result.decodedAsMask()) {
            final byte[] palette = result.palette.array();
            final Shader.TileMode tileMode = toTileMode(options);
            paint.setColorFilter(new PorterDuffColorFilter(color(palette), PorterDuff.Mode.SRC_IN));
            paint.setShader(new BitmapShader(result.bitmap, tileMode, tileMode));
        } else if (result.decodedAsGreyscale()) {
            final Shader.TileMode tileMode = toTileMode(options);
            paint.setColorFilter(new ColorMatrixColorFilter(alphaToGrayFilter()));
            paint.setShader(new BitmapShader(result.bitmap, tileMode, tileMode));
        } else {
            if (Build.VERSION.SDK_INT < 33) {
                return null;
            }
            final Shader shader = createShader(result, rawImageBitmap, options);
            paint.setShader(shader);
        }
        return paint;
    }

    private static float[] alphaToGrayFilter() {
        return new float[] {
                0, 0, 0, 1, 0,
                0, 0, 0, 1, 0,
                0, 0, 0, 1, 0,
                0, 0, 0, 0, 255
        };
    }

    private static Shader.TileMode toTileMode(int options) {
        if ((options & FLAG_MIRRORED) != 0) {
            return Shader.TileMode.MIRROR;
        } else if ((options & FLAG_TILED) != 0) {
            return Shader.TileMode.REPEAT;
        } else {
            return Shader.TileMode.CLAMP;
        }
    }

    @TargetApi(33)
    private static PaletteShader createShader(ByteBuffer source, PngHeaderInfo headerInfo, @Options int options) {
        final Bitmap rawImageBitmap = Bitmap.createBitmap(headerInfo.width, headerInfo.height, Bitmap.Config.ALPHA_8);

        final DecodingResult result = PngDecoder.decodeIndexed(source, rawImageBitmap, 0);
        if (result == null) {
            return null;
        }
        return createShader(result, rawImageBitmap, options);
    }

    @TargetApi(33)
    private static PaletteShader createShader(@NonNull DecodingResult result, Bitmap rawImageBitmap, @Options int options) {
        final int colorCount = result.palette.limit() / 4;
        final Bitmap paletteBitmap = Bitmap.createBitmap(colorCount, 1, Bitmap.Config.ARGB_8888);

        paletteBitmap.copyPixelsFromBuffer(result.palette);

        final BitmapShader rawImageShader, paletteShader;

        rawImageShader = newImageShader(rawImageBitmap, toTileMode(options));
        paletteShader = newPaletteShader(paletteBitmap);

        rawImageShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);
        paletteShader.setFilterMode(BitmapShader.FILTER_MODE_NEAREST);

        return new PaletteShader(paletteShader, rawImageShader);
    }

    static TypedValue loadValue(Resources r, @AnyRes int resourceId) {
        TypedValue typedValue = new TypedValue();
        r.getValue(resourceId, typedValue, true);
        return typedValue;
    }

    static @NonNull ByteBuffer loadIndexedPng(AssetFileDescriptor input) throws IOException {
        try (FileInputStream fileInputStream = new FileInputStream(input.getFileDescriptor());
             FileChannel channel = fileInputStream.getChannel()) {

            return prepareBuffer(channel, input.getLength(), input.getStartOffset());
        }
    }

    private static ByteBuffer prepareBuffer(FileChannel channel, long imageSize, long startOffset) throws IOException {
        ByteBuffer buffer;

        if (imageSize > 16 * 1024) {
            buffer = channel.map(FileChannel.MapMode.READ_ONLY, startOffset, imageSize);
        } else {
            buffer = ByteBuffer.allocateDirect((int) imageSize);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, startOffset) == -1) {
                    break;
                }
            }
            buffer.flip();
        }

        return buffer.order(ByteOrder.BIG_ENDIAN);
    }

    static boolean isPreview(TypedArray typedArray) {
        return typedArray.getClass().getName().contains("BridgeTypedArray");
    }

    static ColorStateList toColor(Resources.Theme theme, TypedValue typedValue) {
        return toColor(theme.getResources(), theme, typedValue);
    }

    @SuppressWarnings("deprecation")
    static ColorStateList toColor(Resources resources, Resources.Theme theme, TypedValue typedValue) {
        if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return ColorStateList.valueOf(typedValue.data);
        }

        if (Build.VERSION.SDK_INT >= 23) {
            return resources.getColorStateList(typedValue.resourceId, theme);
        } else {
            return resources.getColorStateList(typedValue.resourceId);
        }
    }

    static boolean canApplyThemeToColor(TypedValue typedValue) {
        if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return false;
        }

        return Build.VERSION.SDK_INT >= 23;
    }

    private static volatile boolean noHwAlpha8;

    private static BitmapShader newImageShader(Bitmap rawImage, Shader.TileMode tileMode) {
        if (Build.VERSION.SDK_INT >= 26 && !noHwAlpha8) {
            Bitmap hw = rawImage.copy(Bitmap.Config.HARDWARE, false);
            if (hw != null) {
                rawImage.recycle();

                return new BitmapShader(hw, tileMode, tileMode);
            }

            Log.w(TAG, PngSupport.ERROR_CODE_FAILED_ALPHA8_UPLOAD);

            noHwAlpha8 = true;
        }

        return new BitmapShader(rawImage, tileMode, tileMode);
    }

    private static BitmapShader newPaletteShader(Bitmap palette) {
        Bitmap bm = palette;

        if (Build.VERSION.SDK_INT >= 26) {
            bm = palette.copy(Bitmap.Config.HARDWARE, false);
            if (bm != null) {
                palette.recycle();
            } else {
                bm = palette;
            }
        }

        return new BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    }

    // TODO test it
    static @ColorInt int color(byte[] palette) {
        int i;
        for (i = 0; i < palette.length; i += 4) {
            if (palette[i + 3] != 0) {
                break;
            }
        }

        int r = palette[i] & 0xFF;
        int g = palette[i + 1] & 0xFF;
        int b = palette[i + 2] & 0xFF;

        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
