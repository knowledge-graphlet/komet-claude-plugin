/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.doc.print.settings;

import dev.ikm.tinkar.common.bind.EnumConceptBinding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the pinned concept identities of the print-settings value vocabularies. These UUIDs are the
 * concept identity each choice will carry once minted, so a changed annotation silently forks it —
 * every literal is asserted exactly. If this fails after an edit, the edit forked an identity:
 * revert it (identities are immutable; add NEW constants instead).
 */
class DocumentSurfaceSettingsTermsTest {

    @Test
    void pageSizeIdentitiesAreFrozen() {
        assertId(PageSize.LETTER, "bd326ef8-a439-59d3-9241-cecfea10ade3");
        assertId(PageSize.A4, "a44e53c1-e456-5627-b845-97c0a370bf70");
        assertId(PageSize.LEGAL, "a714e250-e3a7-5ebe-b7c5-7b59d913f8bf");
    }

    @Test
    void pageOrientationIdentitiesAreFrozen() {
        assertId(PageOrientation.PORTRAIT, "703e1b62-f5c3-5451-8762-82c21a769c92");
        assertId(PageOrientation.LANDSCAPE, "eb7ec990-3447-5e9c-b6ee-d353f4182844");
    }

    @Test
    void marginPresetIdentitiesAreFrozen() {
        assertId(MarginPreset.NARROW, "67887ae0-a7a4-5840-abca-0434caee122b");
        assertId(MarginPreset.NORMAL, "76a0a876-afde-511d-9475-43d9bc105c24");
        assertId(MarginPreset.WIDE, "302018d4-c223-5fcc-8090-5f375e914342");
    }

    @Test
    void documentThemeIdentitiesAreFrozen() {
        assertId(DocumentTheme.DEFAULT, "4bcbc690-4240-592f-b0c3-056470a68e01");
        assertId(DocumentTheme.MANUSCRIPT, "1f426059-a645-575c-905c-06cdbc68d953");
    }

    @Test
    void furnitureVisibilityIdentitiesAreFrozen() {
        assertId(FurnitureVisibility.SHOWN, "81d6f9f8-06f1-509b-9d42-f0f5c44368c4");
        assertId(FurnitureVisibility.HIDDEN, "d6645aca-ab37-57f1-af38-2e08d8cd4123");
    }

    @Test
    void pageNumberPlacementIdentitiesAreFrozen() {
        assertId(PageNumberPlacement.NONE, "1b46a001-86e2-5d72-8a7e-edc11e9275b9");
        assertId(PageNumberPlacement.HEADER_RIGHT, "3974b5cb-5d21-5fa5-ab44-e1060bf8fcff");
        assertId(PageNumberPlacement.FOOTER_CENTER, "97368440-8362-5626-94e9-149a9c5de877");
        assertId(PageNumberPlacement.FOOTER_RIGHT, "0c27123f-7767-5e80-8bf8-54a724d741ea");
    }

    @Test
    void everyValueConceptHasADistinctPinnedIdentity() {
        List<EnumConceptBinding> all = new ArrayList<>();
        all.addAll(List.of(PageSize.values()));
        all.addAll(List.of(PageOrientation.values()));
        all.addAll(List.of(MarginPreset.values()));
        all.addAll(List.of(DocumentTheme.values()));
        all.addAll(List.of(FurnitureVisibility.values()));
        all.addAll(List.of(PageNumberPlacement.values()));

        Set<UUID> seen = new HashSet<>();
        for (EnumConceptBinding concept : all) {
            assertTrue(seen.add(concept.publicId().asUuidArray()[0]),
                    "duplicate identity for " + concept);
        }
        assertEquals(16, seen.size(), "all value identities present and distinct");
    }

    @Test
    void settingKeyCarriesConceptIdentityAndDefault() {
        assertNotNull(DocumentSurfaceSettingKeys.PRINT_SETTINGS.publicId(),
                "the preference key is also a concept binding");
        assertEquals(PrintSettings.DEFAULT, DocumentSurfaceSettingKeys.PRINT_SETTINGS.defaultValue(),
                "the key carries the settings' default value");
    }

    private static void assertId(EnumConceptBinding concept, String expectedUuid) {
        assertEquals(UUID.fromString(expectedUuid), concept.publicId().asUuidArray()[0],
                "FROZEN identity forked for " + concept);
    }
}
