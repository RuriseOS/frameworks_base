/*
 * Copyright (C) 2026 The RuriseOS Project
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

package com.android.server.graphics.fonts;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontFileUtil;
import android.graphics.fonts.FontStyle;
import android.os.LocaleList;
import android.text.FontConfig;
import android.text.TextPaint;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.NioUtils;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Validates single-face SFNT fonts and describes only styles their outlines actually support.
 * No PostScript-name substitution is performed on the trusted system font update map.
 */
final class CustomFontFile {
    private static final int FVAR = 0x66766172;
    private static final int WGHT = 0x77676874;
    private static final int ITAL = 0x6974616c;
    private static final int SLNT = 0x736c6e74;

    final String name;
    final FontConfig.FontFamily family;

    private CustomFontFile(String name, FontConfig.FontFamily family) {
        this.name = name;
        this.family = family;
    }

    CustomFontFile withFile(File file) {
        List<FontConfig.Font> fonts = new ArrayList<>();
        for (FontConfig.Font font : family.getFontList()) {
            fonts.add(new FontConfig.Font(file, null, name, font.getStyle(), 0,
                    font.getFontVariationSettings(), null, FontConfig.Font.VAR_TYPE_AXES_NONE));
        }
        return new CustomFontFile(name, new FontConfig.FontFamily(fonts,
                family.getLocaleList(), family.getVariant()));
    }

    /** Called after service startup, before an imported file is committed to the library. */
    static CustomFontFile validateForImport(File file) throws IOException {
        CustomFontFile parsed = read(file);
        validateRendering(file, parsed.family.getFontList());
        return parsed;
    }

    /** Reads font metadata without depending on the system Typeface map being initialized. */
    static CustomFontFile read(File file) throws IOException {
        // The store limits the size before invoking any parser.
        Map<Integer, Axis> axes;
        String name;
        int packed;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteBuffer buffer = in.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    0, in.getChannel().size());
            try {
                axes = readAxes(buffer);
                name = FontFileUtil.getPostScriptName(buffer, 0);
                packed = FontFileUtil.analyzeStyle(buffer, 0, null);
            } finally {
                NioUtils.freeDirectBuffer(buffer);
            }
        }
        if (name == null || name.isEmpty() || name.length() > 128
                || name.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            throw new IOException("Invalid PostScript name");
        }
        if (!FontFileUtil.isSuccess(packed)) throw new IOException("Invalid font style");
        int weight = FontFileUtil.unpackWeight(packed);
        if (weight < FontStyle.FONT_WEIGHT_MIN || weight > FontStyle.FONT_WEIGHT_MAX) {
            throw new IOException("Invalid font weight");
        }
        boolean italic = FontFileUtil.unpackItalic(packed);
        Axis wght = axes.get(WGHT);
        Axis ital = axes.get(ITAL);
        Axis slnt = axes.get(SLNT);
        TreeSet<Integer> weights = new TreeSet<>();
        if (wght == null) {
            weights.add(weight);
        } else {
            int min = Math.max(FontStyle.FONT_WEIGHT_MIN, (int) Math.ceil(wght.min));
            int max = Math.min(FontStyle.FONT_WEIGHT_MAX, (int) Math.floor(wght.max));
            if (min > max) throw new IOException("Unsupported weight range");
            weights.add(min);
            weights.add(max);
            weights.add(Math.max(min, Math.min(max, Math.round(wght.def))));
            for (int value = 100; value <= 900; value += 100) {
                if (value >= min && value <= max) weights.add(value);
            }
        }
        boolean hasItal = ital != null && ital.contains(0) && ital.contains(1);
        boolean hasSlnt = !hasItal && slnt != null && slnt.contains(0)
                && (slnt.min < 0 || slnt.max > 0);
        float angle = hasSlnt
                ? (slnt.min < 0 ? Math.max(-12, slnt.min) : Math.min(12, slnt.max)) : 0;
        List<FontConfig.Font> fonts = new ArrayList<>();
        for (int value : weights) {
            String variations = wght == null ? "" : "'wght' " + value;
            if (hasItal || hasSlnt) {
                String tag = hasItal ? "ital" : "slnt";
                add(fonts, file, name, value, false, append(variations, tag, 0));
                add(fonts, file, name, value, true,
                        append(variations, tag, hasItal ? 1 : angle));
            } else {
                add(fonts, file, name, value, italic, variations);
            }
        }
        FontConfig.FontFamily family = new FontConfig.FontFamily(
                fonts, LocaleList.getEmptyLocaleList(), FontConfig.FontFamily.VARIANT_DEFAULT);
        return new CustomFontFile(name, family);
    }

    private static String append(String settings, String tag, float value) {
        return (settings.isEmpty() ? "" : settings + ",") + "'" + tag + "' " + value;
    }

    private static void add(List<FontConfig.Font> fonts, File file, String name, int weight,
            boolean italic, String axes) {
        fonts.add(new FontConfig.Font(file, null, name, new FontStyle(weight,
                italic ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT),
                0, axes, null, FontConfig.Font.VAR_TYPE_AXES_NONE));
    }

    private static void validateRendering(File file, List<FontConfig.Font> fonts)
            throws IOException {
        // Fixed output dimensions keep extreme font metrics from allocating an unbounded bitmap.
        Bitmap bitmap = Bitmap.createBitmap(320, 96, Bitmap.Config.ALPHA_8);
        try {
            Canvas canvas = new Canvas(bitmap);
            TextPaint paint = new TextPaint();
            paint.setTextSize(24);
            for (FontConfig.Font description : fonts) {
                Font font = new Font.Builder(file)
                        .setWeight(description.getStyle().getWeight())
                        .setSlant(description.getStyle().getSlant())
                        .setFontVariationSettings(description.getFontVariationSettings()).build();
                paint.setTypeface(new Typeface.CustomFallbackBuilder(
                        new FontFamily.Builder(font).build()).build());
                canvas.drawText("Aa 0123 繁體中文", 0, 40, paint);
            }
        } catch (RuntimeException e) {
            throw new IOException("Font rendering failed", e);
        } finally {
            bitmap.recycle();
        }
    }

    static final class Axis {
        final float min;
        final float def;
        final float max;
        Axis(float min, float def, float max) throws IOException {
            if (min > def || def > max) throw new IOException("Invalid variation range");
            this.min = min;
            this.def = def;
            this.max = max;
        }
        boolean contains(float value) { return min <= value && value <= max; }
    }

    /** Checks table bounds before reading fvar; collections/WOFF are deliberately unsupported. */
    static Map<Integer, Axis> readAxes(ByteBuffer source) throws IOException {
        ByteBuffer b = source.duplicate().order(ByteOrder.BIG_ENDIAN);
        bounds(b, 0, 12);
        int magic = b.getInt(0);
        if (magic != 0x00010000 && magic != 0x4f54544f) {
            throw new IOException("Expected a single-face TTF or OTF");
        }
        int count = Short.toUnsignedInt(b.getShort(4));
        if (count == 0 || count > 256) throw new IOException("Invalid table count");
        bounds(b, 12, count * 16L);
        Map<Integer, Axis> result = new HashMap<>();
        boolean found = false;
        for (int i = 0; i < count; i++) {
            int record = 12 + i * 16;
            long offset = Integer.toUnsignedLong(b.getInt(record + 8));
            long length = Integer.toUnsignedLong(b.getInt(record + 12));
            bounds(b, offset, length);
            if (b.getInt(record) != FVAR) continue;
            if (found || length < 16) throw new IOException("Invalid fvar table");
            found = true;
            int start = (int) offset;
            if (b.getInt(start) != 0x00010000) throw new IOException("Unsupported fvar version");
            int axisOffset = Short.toUnsignedInt(b.getShort(start + 4));
            int axisCount = Short.toUnsignedInt(b.getShort(start + 8));
            int axisSize = Short.toUnsignedInt(b.getShort(start + 10));
            if (axisOffset < 16 || axisSize < 20 || axisCount > 64
                    || axisOffset + (long) axisCount * axisSize > length) {
                throw new IOException("Invalid axis records");
            }
            for (int j = 0; j < axisCount; j++) {
                int at = start + axisOffset + j * axisSize;
                int tag = b.getInt(at);
                Axis axis = new Axis(b.getInt(at + 4) / 65536f,
                        b.getInt(at + 8) / 65536f, b.getInt(at + 12) / 65536f);
                if (result.put(tag, axis) != null) throw new IOException("Duplicate axis");
            }
        }
        return result;
    }

    private static void bounds(ByteBuffer b, long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || offset > b.limit() || length > b.limit() - offset) {
            throw new IOException("Font table outside file");
        }
    }
}
