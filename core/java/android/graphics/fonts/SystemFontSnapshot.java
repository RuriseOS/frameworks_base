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

package android.graphics.fonts;

import android.graphics.Typeface;
import android.util.LruCache;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Immutable font resources with a bounded, snapshot-local style cache. Main-thread resolution. */
final class SystemFontSnapshot {
    final long generation;
    final String selectedId;
    private final Map<String, Typeface> mFonts;
    private final Set<String> mFamilies;
    private final LruCache<Typeface, Typeface> mStyles = new LruCache<>(128);

    SystemFontSnapshot(long generation, String selectedId, Map<String, Typeface> fonts,
            String[] families) {
        this.generation = generation;
        this.selectedId = selectedId;
        // SystemFonts returns an ArrayMap whose entrySet does not support toArray(), which
        // Map.copyOf uses. Copy through HashMap's entry iterator before making it immutable.
        mFonts = Map.copyOf(new HashMap<>(fonts));
        mFamilies = new HashSet<>(Arrays.asList(families));
        if (!mFonts.containsKey("sans-serif")) {
            throw new IllegalArgumentException("Missing default font family");
        }
    }

    static SystemFontSnapshot build(CustomFontRuntimeConfig config) {
        // Font.Builder's SkData owns a JNI global reference to each mapped ByteBuffer. Native
        // fonts/layouts can outlive this Java snapshot safely. Never manually unmap these buffers.
        Map<String, FontFamily[]> fallback = SystemFonts.buildSystemFallback(config.getFontConfig());
        String id = config.getSelectedId();
        if (!id.isEmpty() && !id.startsWith(CustomFontInfo.BUILTIN_PREFIX)) {
            boolean loaded = false;
            FontFamily[] defaults = fallback.get("sans-serif");
            if (defaults != null) {
                for (FontFamily family : defaults) {
                    for (int i = 0; i < family.getSize(); i++) {
                        File file = family.getFont(i).getFile();
                        loaded |= file != null && file.getName().equals(id + ".font");
                    }
                }
            }
            // SystemFonts tolerates missing files; do not silently publish only the fallbacks.
            if (!loaded) throw new IllegalStateException("Selected font was not loaded");
        }
        return new SystemFontSnapshot(config.getGeneration(), id,
                SystemFonts.buildSystemTypefaces(config.getFontConfig(), fallback),
                config.getFamilies());
    }

    Typeface resolve(Typeface requested) {
        Typeface source = requested == null ? Typeface.DEFAULT : requested;
        String family = source.getSystemFontFamilyName();
        if (!source.isSystemFontForRuntime() || !mFamilies.contains(family)) return requested;
        Typeface base = mFonts.get(family);
        if (base == null) return requested;
        Typeface result = mStyles.get(source);
        if (result == null) {
            result = Typeface.createForFontSnapshot(base, source.getWeight(), source.isItalic());
            mStyles.put(source, result);
        }
        return result;
    }
}
