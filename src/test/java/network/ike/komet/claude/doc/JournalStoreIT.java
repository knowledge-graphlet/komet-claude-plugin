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
package network.ike.komet.claude.doc;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.AfterAll;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link JournalStore} against an ephemeral store seeded with the Tinkar
 * starter data — the regression gate for the conversation-journal chronology model
 * ({@code IKE-Network/ike-issues#807}).
 *
 * <p>Two behaviors asserted here are <em>load-bearing</em> and not covered anywhere upstream: a
 * semantic re-composed at the same public id gains a new version (the composer suite only proves
 * this for concepts; the semantic path rides {@code putEntity → PrimitiveData.merge}), and a
 * component-id-list field round-trips in order (the upstream IT for it is {@code @Disabled}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JournalStoreIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/tinkar-starter-data-reasoned-pb.zip");

    private ViewCalculator view;
    private JournalStore store;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(),
                "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath()
                        + " (copied by maven-dependency-plugin in process-test-resources).");
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertTrue(count > 0, "Should load entities from the starter-data protobuf file");
        view = Calculators.View.Default();
        store = new JournalStore(() -> view);
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void bootstrapIsIdempotent() {
        JournalStore.bootstrapIfAbsent();
        int authorVersions = versionCount(RichSurfaceTerms.KOMET_ASSISTANT_AUTHOR.publicId());
        int patternVersions = versionCount(RichSurfaceTerms.PROSE_ELEMENT_PATTERN.publicId());
        assertTrue(authorVersions > 0, "assistant author seeded");
        assertTrue(patternVersions > 0, "prose pattern seeded");

        JournalStore.bootstrapIfAbsent();
        assertEquals(authorVersions, versionCount(RichSurfaceTerms.KOMET_ASSISTANT_AUTHOR.publicId()),
                "second bootstrap must not add author versions");
        assertEquals(patternVersions, versionCount(RichSurfaceTerms.PROSE_ELEMENT_PATTERN.publicId()),
                "second bootstrap must not add pattern versions");
    }

    @Test
    void appendAndLoadRoundTripsMarkdownOrderAndRole() {
        PublicId anchor = store.appendExchange(null, "Round-trip conversation",
                "What is **aortic stenosis**?", "A narrowing of the aortic valve.\n\n- one\n- two");
        assertNotNull(anchor, "first append mints the anchor");

        Optional<List<JournalStore.TurnRecord>> loaded = store.load(anchor);
        assertTrue(loaded.isPresent(), "journal loads");
        List<JournalStore.TurnRecord> turns = loaded.get();
        assertEquals(2, turns.size(), "one exchange = two turns");
        assertEquals("What is **aortic stenosis**?", turns.get(0).markdown(), "user markdown exact");
        assertFalse(turns.get(0).assistantAuthored(), "first turn is the user's (STAMP author)");
        assertTrue(turns.get(1).assistantAuthored(), "second turn is the assistant's (STAMP author)");
        assertTrue(turns.get(1).markdown().contains("- two"), "assistant markdown exact");
    }

    @Test
    void secondAppendGrowsTheManifestAsANewVersionInOrder() {
        PublicId anchor = store.appendExchange(null, "Growing conversation", "u1", "a1");
        int manifestNid = soleManifestNid(anchor);
        int versionsAfterFirst = EntityService.get().getEntityFast(manifestNid).versions().size();

        PublicId confirmed = store.appendExchange(anchor, "Growing conversation", "u2", "a2");
        assertEquals(anchor, confirmed, "append to an existing journal confirms the same anchor");
        assertEquals(manifestNid, soleManifestNid(anchor), "still exactly one manifest");
        assertEquals(versionsAfterFirst + 1,
                EntityService.get().getEntityFast(manifestNid).versions().size(),
                "each exchange appends exactly one manifest version (semantic re-compose merges)");

        List<JournalStore.TurnRecord> turns = store.load(anchor).orElseThrow();
        assertEquals(4, turns.size(), "two exchanges = four turns");
        assertEquals(List.of("u1", "a1", "u2", "a2"),
                turns.stream().map(JournalStore.TurnRecord::markdown).toList(),
                "element order preserved across manifest versions (id-list order round-trips)");
        assertEquals(List.of(false, true, false, true),
                turns.stream().map(JournalStore.TurnRecord::assistantAuthored).toList(),
                "roles alternate user/assistant by STAMP author");
    }

    @Test
    void appendFromAFreshStoreInstanceContinuesTheSameJournal() {
        PublicId anchor = store.appendExchange(null, "Handed-off conversation", "u1", "a1");
        JournalStore fresh = new JournalStore(() -> view);
        fresh.appendExchange(anchor, "Handed-off conversation", "u2", "a2");
        assertEquals(4, fresh.load(anchor).orElseThrow().size(),
                "a fresh JournalStore instance appends to the same journal");
    }

    @Test
    void appendWithNoViewNeverTruncatesTheManifest() {
        // The critical review finding: the append base must come from the manifest chronology's
        // raw head, never through the (possibly torn-down, past-positioned, or filtered) card
        // view. A store with NO calculator appends without losing a single prior turn.
        PublicId anchor = store.appendExchange(null, "No-view conversation", "u1", "a1");
        JournalStore blind = new JournalStore(() -> null);
        blind.appendExchange(anchor, "No-view conversation", "u2", "a2");
        assertEquals(List.of("u1", "a1", "u2", "a2"),
                store.load(anchor).orElseThrow().stream()
                        .map(JournalStore.TurnRecord::markdown).toList(),
                "a view-less append keeps every prior turn, in order");
    }

    @Test
    void loadOfUnknownOrNullAnchorIsEmpty() {
        assertTrue(store.load(null).isEmpty(), "null anchor loads nothing");
        assertTrue(store.load(dev.ikm.tinkar.common.id.PublicIds.newRandom()).isEmpty(),
                "unknown anchor loads nothing");
    }

    private static int versionCount(PublicId publicId) {
        return EntityService.get()
                .getEntityFast(EntityService.get().nidForPublicId(publicId))
                .versions().size();
    }

    private static int soleManifestNid(PublicId anchor) {
        int[] nids = EntityService.get().semanticNidsForComponentOfPattern(
                EntityService.get().nidForPublicId(anchor),
                RichSurfaceTerms.JOURNAL_MANIFEST_PATTERN.nid());
        assertEquals(1, nids.length, "exactly one manifest per journal");
        return nids[0];
    }
}
