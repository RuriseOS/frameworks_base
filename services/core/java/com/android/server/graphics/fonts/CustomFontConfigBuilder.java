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

import android.graphics.fonts.CustomFontInfo;
import android.graphics.fonts.FontStyle;
import android.text.FontConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Composes a selected UI family with the ROM's original named families and language fallbacks. */
final class CustomFontConfigBuilder {
    private CustomFontConfigBuilder() {}

    static List<CustomFontInfo> builtIns(FontConfig base, String[] allowedFamilies) {
        Set<String> available = new HashSet<>();
        for (FontConfig.NamedFamilyList family : base.getNamedFamilyLists()) {
            available.add(family.getName());
        }
        List<CustomFontInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : allowedFamilies) {
            if (available.contains(name) && seen.add(name)) {
                result.add(new CustomFontInfo(CustomFontInfo.BUILTIN_PREFIX + name, name));
            }
        }
        return result;
    }

    static FontConfig build(FontConfig base, String id, CustomFontStore store,
            String[] allowedFamilies, String[] uiFamilies) throws IOException {
        if (id.isEmpty()) return base;
        List<FontConfig.FontFamily> selected;
        if (id.startsWith(CustomFontInfo.BUILTIN_PREFIX)) {
            boolean allowed = builtIns(base, allowedFamilies).stream()
                    .anyMatch(info -> id.equals(info.getId()));
            if (!allowed) throw new IOException("Unknown built-in font");
            String name = id.substring(CustomFontInfo.BUILTIN_PREFIX.length());
            selected = effectiveFamilies(base).stream()
                    .filter(family -> name.equals(family.getName())).findFirst().get().getFamilies();
        } else {
            selected = List.of(store.parsed(id).family);
        }
        return prepend(base, selected, uiFamilies);
    }

    static FontConfig prepend(FontConfig base, List<FontConfig.FontFamily> selected,
            String[] uiFamilies) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (FontConfig.Alias alias : base.getAliases()) {
            aliases.put(alias.getName(), alias.getOriginal());
        }
        Set<String> targets = new HashSet<>();
        targets.add("sans-serif");
        for (String uiFamily : uiFamilies) {
            String name = uiFamily;
            Set<String> visited = new HashSet<>();
            while (aliases.containsKey(name) && visited.add(name)) name = aliases.get(name);
            if (name != null && !name.isEmpty() && !protectedFamily(name)) targets.add(name);
        }
        List<FontConfig.NamedFamilyList> named = new ArrayList<>();
        List<FontConfig.Alias> resultAliases = new ArrayList<>(base.getAliases());
        List<FontConfig.Alias> roleAliases = new ArrayList<>();
        Set<String> fallbackTargets = new HashSet<>();
        List<FontConfig.NamedFamilyList> effective = effectiveFamilies(base);
        for (FontConfig.NamedFamilyList family : effective) {
            if (family.getFallback() != null) fallbackTargets.add(family.getFallback());
        }
        for (FontConfig.NamedFamilyList family : effective) {
            if (!targets.contains(family.getName())) {
                named.add(family);
                continue;
            }
            FontStyle role = defaultRole(family);
            if (!"sans-serif".equals(family.getName())
                    && !fallbackTargets.contains(family.getName())
                    && role.getSlant() == FontStyle.FONT_SLANT_UPRIGHT
                    && role.getWeight() != FontStyle.FONT_WEIGHT_NORMAL) {
                // Some Material roles are named families containing only medium/bold outlines,
                // rather than weight aliases. Preserve that default weight after substitution.
                resultAliases.removeIf(alias -> alias.getName().equals(family.getName()));
                roleAliases.add(new FontConfig.Alias(
                        family.getName(), "sans-serif", role.getWeight()));
                continue;
            }
            List<FontConfig.FontFamily> families = new ArrayList<>(selected);
            for (FontConfig.FontFamily original : family.getFamilies()) {
                if (!families.contains(original)) families.add(original);
            }
            named.add(new FontConfig.NamedFamilyList(
                    families, family.getName(), family.getFallback()));
        }
        // Role aliases must precede aliases that refer to them.
        resultAliases.addAll(0, roleAliases);
        // Keep alias weights, locale customizations and original fallback order.
        return new FontConfig(base.getFontFamilies(), resultAliases, named,
                base.getLocaleFallbackCustomizations(), base.getLastModifiedTimeMillis(),
                base.getConfigVersion());
    }

    private static List<FontConfig.NamedFamilyList> effectiveFamilies(FontConfig base) {
        Map<String, FontConfig.NamedFamilyList> families = new LinkedHashMap<>();
        for (FontConfig.NamedFamilyList family : base.getNamedFamilyLists()) {
            // Match SystemFonts: a later trusted named-family update overrides an earlier one.
            families.put(family.getName(), family);
        }
        return new ArrayList<>(families.values());
    }

    private static FontStyle defaultRole(FontConfig.NamedFamilyList family) {
        FontStyle best = new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT);
        int bestScore = Integer.MAX_VALUE;
        if (family.getFamilies().isEmpty()) return best;
        for (FontConfig.Font font : family.getFamilies().get(0).getFontList()) {
            FontStyle style = font.getStyle();
            int score = Math.abs(style.getWeight() - 400)
                    + (style.getSlant() == FontStyle.FONT_SLANT_UPRIGHT ? 0 : 2000);
            if (score < bestScore) {
                best = style;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean protectedFamily(String name) {
        return name.contains("monospace") || name.contains("emoji") || name.equals("math");
    }
}
