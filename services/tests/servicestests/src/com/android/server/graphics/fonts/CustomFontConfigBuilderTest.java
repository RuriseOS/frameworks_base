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

import static com.google.common.truth.Truth.assertThat;

import android.graphics.fonts.FontStyle;
import android.os.LocaleList;
import android.text.FontConfig;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class CustomFontConfigBuilderTest {
    private FontConfig.FontFamily family(String path) {
        FontConfig.Font font = new FontConfig.Font(new File(path), null, path,
                new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT), 0, "", null, 0);
        return new FontConfig.FontFamily(List.of(font), LocaleList.getEmptyLocaleList(), 0);
    }

    @Test
    public void prependsCustomOutlinesAndRetainsLanguageFallbackAndWeightedAliases() {
        FontConfig.FontFamily original = family("/system/fonts/regular.ttf");
        FontConfig.FontFamily cjk = family("/system/fonts/cjk.otf");
        FontConfig.FontFamily custom = family("/data/fonts/custom/files/custom.font");
        FontConfig.NamedFamilyList sans = new FontConfig.NamedFamilyList(
                List.of(original), "sans-serif", null);
        FontConfig.NamedFamilyList mono = new FontConfig.NamedFamilyList(
                List.of(original), "monospace", null);
        FontConfig.NamedFamilyList emoji = new FontConfig.NamedFamilyList(
                List.of(original), "emoji", null);
        FontConfig.Alias medium = new FontConfig.Alias("sans-serif-medium", "sans-serif", 500);
        FontConfig base = new FontConfig(List.of(cjk), List.of(medium),
                List.of(sans, mono, emoji), List.of(), 17, 3);

        FontConfig result = CustomFontConfigBuilder.prepend(base, List.of(custom),
                new String[] {"sans-serif-medium", "monospace", "emoji"});
        assertThat(result.getNamedFamilyLists().get(0).getFamilies())
                .containsExactly(custom, original).inOrder();
        assertThat(result.getNamedFamilyLists().get(1)).isSameInstanceAs(mono);
        assertThat(result.getNamedFamilyLists().get(2)).isSameInstanceAs(emoji);
        assertThat(result.getFontFamilies()).containsExactly(cjk);
        assertThat(result.getAliases()).containsExactly(medium);
        assertThat(result.getConfigVersion()).isEqualTo(3);
    }

    @Test
    public void listsOnlyConfiguredFamiliesThatAreInstalled() {
        FontConfig base = new FontConfig(List.of(), List.of(),
                List.of(new FontConfig.NamedFamilyList(List.of(family("/serif.ttf")),
                        "serif", null)), List.of(), 0, 0);
        var entries = CustomFontConfigBuilder.builtIns(base,
                new String[] {"serif", "missing", "serif"});
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getId()).isEqualTo("builtin:serif");
    }

    @Test
    public void resolvesAliasChainsWithoutChangingAliasWeights() {
        FontConfig.FontFamily original = family("/brand.ttf");
        FontConfig.FontFamily custom = family("/custom.ttf");
        FontConfig base = new FontConfig(List.of(),
                List.of(new FontConfig.Alias("headline-medium", "headline", 500),
                        new FontConfig.Alias("headline", "brand", 400)),
                List.of(new FontConfig.NamedFamilyList(List.of(original), "brand", null)),
                List.of(), 0, 0);
        FontConfig result = CustomFontConfigBuilder.prepend(base, List.of(custom),
                new String[] {"headline-medium"});
        assertThat(result.getNamedFamilyLists().get(0).getFamilies())
                .containsExactly(custom, original).inOrder();
        assertThat(result.getAliases()).containsExactlyElementsIn(base.getAliases()).inOrder();
    }

    @Test
    public void materialMediumRoleKeepsItsWeightAndPrecedesDependentAliases() {
        FontConfig.Font medium = new FontConfig.Font(new File("/medium.ttf"), null, "Medium",
                new FontStyle(500, FontStyle.FONT_SLANT_UPRIGHT), 0, "", null, 0);
        FontConfig.FontFamily role = new FontConfig.FontFamily(
                List.of(medium), LocaleList.getEmptyLocaleList(), 0);
        FontConfig.Alias dependent = new FontConfig.Alias("title", "google-sans-medium", 400);
        FontConfig base = new FontConfig(List.of(), List.of(dependent),
                List.of(new FontConfig.NamedFamilyList(
                        List.of(role), "google-sans-medium", null)),
                List.of(), 0, 0);
        FontConfig result = CustomFontConfigBuilder.prepend(base, List.of(family("/custom.ttf")),
                new String[] {"google-sans-medium"});
        assertThat(result.getAliases()).hasSize(2);
        assertThat(result.getAliases().get(0).getName()).isEqualTo("google-sans-medium");
        assertThat(result.getAliases().get(0).getWeight()).isEqualTo(500);
        assertThat(result.getAliases().get(1)).isEqualTo(dependent);
    }
}
