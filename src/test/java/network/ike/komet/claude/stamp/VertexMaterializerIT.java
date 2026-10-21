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
package network.ike.komet.claude.stamp;

import dev.ikm.tinkar.common.id.LongIdList;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.boundary.CommitBoundary;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file's git history materialized as semantic vertices (IKE-Network/ike-issues#1313): its own
 * pattern derived from the Document vertex template, a vertex per block, line and reference with
 * its meaning and properties, parentage and order as Members, a new version only where a vertex
 * changed and an inactive one where a commit removed it; every write ephemeral, and every commit
 * inside the boundary, which this test enforces in fail mode.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VertexMaterializerIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path root;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
        System.setProperty(CommitBoundary.MODE_PROPERTY, "fail");
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        System.clearProperty(CommitBoundary.MODE_PROPERTY);
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    /** alice writes a topic with a listing; bob revises a claim and adds a section with a list; carol drops the listing. */
    @Test
    void aHistoryBecomesVersionedVertices() throws Exception {
        Path dir = Files.createDirectories(root.resolve("vertices"));
        git(dir, "init", "-q", "-b", "main");
        Path file = dir.resolve("topic.adoc");
        String listing = "[source,yaml]\n----\nkey: value\n----\n";
        String v1 = """
                :topic-id: dev-vertex-demo

                = Demo Title

                First claim, see xref:other-topic[] and ((a term)).
                Second claim.

                == Details

                """ + listing;
        commit(dir, file, v1, "alice", "2026-02-01T00:00:00Z", "topic");
        String v2 = v1.replace("Second claim.", "Second claim, revised.") + "\n== More\n\n* one\n* two\n";
        commit(dir, file, v2, "bob", "2026-02-02T00:00:00Z", "revise and extend");
        commit(dir, file, v2.replace(listing, ""), "carol", "2026-02-03T00:00:00Z", "drop the listing");

        GitDocumentMaterializer.Result result = GitDocumentMaterializer.materialize(dir, file);
        assertEquals(3, result.commits(), "every commit written, each inside the boundary in fail mode");

        // The document's own pattern, derived from the template, born ephemeral.
        SemanticEntity<?> rootEntity = EntityHandle.get(result.anchor()).expectSemantic();
        PatternEntityVersion pattern = (PatternEntityVersion) EntityHandle.get(rootEntity.patternNid()).expectPattern().versions().getFirst();
        assertEquals(IkeConcept.DOCUMENT_VERTEX.nid(), pattern.semanticMeaningNid(), "the document's pattern means Document vertex");
        assertEquals(DataKind.EPHEMERAL, EntityBoundaries.birthKind(rootEntity.patternNid()), "a document in git is ephemeral");
        assertEquals(rootEntity.patternNid(), rootEntity.referencedComponentNid(), "the root references the document's pattern");

        // The root: its meaning, title, topic id and members.
        EntityVertex rootVertex = latest(result.anchor().asUuidArray()[0]);
        assertEquals(IkeConcept.ASCIIDOC_DOCUMENT.nid(), rootVertex.getMeaningNid());
        assertEquals("Demo Title", property(rootVertex, IkeConcept.DOCUMENT_ELEMENT_TITLE));
        assertEquals("dev-vertex-demo", property(rootVertex, IkeConcept.TOPIC_ID));

        // A section and its heading line.
        long details = memberWithTitle(rootVertex, "Details");
        EntityVertex section = latest(details);
        assertEquals(IkeConcept.DOCUMENT_SECTION.nid(), section.getMeaningNid());
        assertEquals(1, property(section, IkeConcept.DOCUMENT_ELEMENT_LEVEL));
        EntityVertex heading = latest(members(section)[0]);
        assertEquals(IkeConcept.HEADING_LINE.nid(), heading.getMeaningNid(), "the section's first member is its heading line");
        assertEquals("== Details", property(heading, IkeConcept.LINE_SOURCE));
        assertEquals("Details", property(heading, IkeConcept.DOCUMENT_ELEMENT_TITLE));

        // A revised line keeps its identity: the same vertex, a second version with the new words;
        // its paragraph's Members did not change, so the paragraph has one version.
        long claim = findLine(rootVertex, "Second claim, revised.");
        assertEquals(2, activeVersions(claim), "the revision is a new version of the same line vertex");
        assertTrue(!retired(rootEntity.patternNid(), IkeConcept.PROSE_LINE, "Second claim."), "nothing was retired by the revision");
        long paragraph = parentOf(rootVertex, claim);
        assertEquals(IkeConcept.DOCUMENT_PARAGRAPH.nid(), latest(paragraph).getMeaningNid());
        assertEquals(1, activeVersions(paragraph), "the paragraph's Members did not change");

        // References hang from their line.
        long first = findLine(rootVertex, "First claim, see xref:other-topic[] and ((a term)).");
        long[] references = members(latest(first));
        assertEquals(2, references.length, "the line's two references are its members");
        EntityVertex xref = latest(references[0]);
        assertTrue(xref.properties().containsValue("other-topic"), "the cross reference carries its target");

        // The dropped listing: its verbatim line has an inactive latest version.
        assertTrue(retired(rootEntity.patternNid(), IkeConcept.VERBATIM_LINE, "key: value"), "the listing's verbatim line was retired");

        // Converges: a second run writes nothing.
        assertEquals(0, GitDocumentMaterializer.materialize(dir, file).commits(), "a later run skips the recorded commits");
    }

    /**
     * A working tree's module seeded before its store held Ephemeral module, as in every store
     * materialized before 2026-10-21, is stated a kind of it the next time a commit under it is
     * written, so what was written under it becomes ephemeral and its documents become scopes.
     */
    @Test
    void anOldModuleIsStatedEphemeralWhenNextWrittenUnder() {
        dev.ikm.tinkar.terms.EntityProxy.Concept module = dev.ikm.tinkar.terms.EntityProxy.Concept.make("old-clone",
                java.util.UUID.randomUUID());
        try (dev.ikm.tinkar.entity.transaction.StampedWriter writer = dev.ikm.tinkar.entity.transaction.StampedWriter.open("old module",
                State.ACTIVE, System.currentTimeMillis(), dev.ikm.tinkar.terms.KernelConcept.USER, IkeConcept.DEVELOPMENT_MODULE,
                dev.ikm.tinkar.terms.KernelConcept.DEVELOPMENT_PATH)) {
            writer.concept(module.publicId());
            writer.fullyQualifiedName(module.publicId(), "old-clone");
            writer.commit();
        }
        ModuleKinds.clear();
        assertEquals(DataKind.KNOWLEDGE, ModuleKinds.resolve(module.nid()).kind(), "as seeded before the terms existed");

        GitStampTerms.GitStamp stamp = new GitStampTerms.GitStamp(module, State.ACTIVE, System.currentTimeMillis(),
                dev.ikm.tinkar.terms.KernelConcept.USER, module, dev.ikm.tinkar.terms.KernelConcept.DEVELOPMENT_PATH);
        GitStampVocabulary.seedIfAbsent(stamp);
        assertEquals(DataKind.EPHEMERAL, ModuleKinds.resolve(module.nid()).kind(), "stated a kind of Ephemeral module");
        long axioms = EntityService.get().semanticsForComponentOfPattern(module.nid(),
                dev.ikm.tinkar.terms.KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()).count();
        GitStampVocabulary.seedIfAbsent(stamp);
        assertEquals(axioms, EntityService.get().semanticsForComponentOfPattern(module.nid(),
                dev.ikm.tinkar.terms.KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()).count(), "once");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void commit(Path dir, Path file, String text, String author, String date, String message) throws Exception {
        Files.writeString(file, text);
        git(dir, "add", file.getFileName().toString());
        git(dir, "-c", "user.name=" + author, "-c", "user.email=" + author + "@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=" + date, "-m", message);
    }

    private static EntityVertex latest(java.util.UUID id) {
        return latest(EntityService.get().nidForPublicId(dev.ikm.tinkar.common.id.PublicIds.of(id)));
    }

    private static EntityVertex latest(long nid) {
        return (EntityVertex) latestVersion(nid).fieldValues().get(0);
    }

    private static SemanticEntityVersion latestVersion(long nid) {
        SemanticEntityVersion latest = null;
        for (Object o : EntityHandle.get(nid).expectSemantic().versions()) {
            SemanticEntityVersion v = (SemanticEntityVersion) o;
            if (latest == null || v.time() >= latest.time()) {
                latest = v;
            }
        }
        return latest;
    }

    private static int activeVersions(long nid) {
        int count = 0;
        for (Object o : EntityHandle.get(nid).expectSemantic().versions()) {
            SemanticEntityVersion v = (SemanticEntityVersion) o;
            if (v.state() == State.ACTIVE) {
                count++;
            }
        }
        return count;
    }

    private static Object property(EntityVertex vertex, ConceptFacade key) {
        return vertex.properties().get(key.nid());
    }

    private static long[] members(EntityVertex vertex) {
        Object members = vertex.properties().get(IkeConcept.DOCUMENT_ELEMENT_MEMBERS.nid());
        return members instanceof LongIdList ids ? ids.toArray() : new long[0];
    }

    private static long memberWithTitle(EntityVertex parent, String title) {
        for (long member : members(parent)) {
            if (title.equals(property(latest(member), IkeConcept.DOCUMENT_ELEMENT_TITLE))) {
                return member;
            }
        }
        throw new AssertionError("no member titled " + title);
    }

    /** Depth-first through Members for the line vertex whose source is the text. */
    private static long findLine(EntityVertex from, String source) {
        List<Long> stack = new ArrayList<>();
        for (long m : members(from)) {
            stack.add(m);
        }
        while (!stack.isEmpty()) {
            long nid = stack.removeLast();
            EntityVertex vertex = latest(nid);
            if (source.equals(property(vertex, IkeConcept.LINE_SOURCE))) {
                return nid;
            }
            for (long m : members(vertex)) {
                stack.add(m);
            }
        }
        throw new AssertionError("no line " + source);
    }

    /** Whether a vertex of the meaning and line source has an inactive latest version. */
    private static boolean retired(long patternNid, ConceptFacade meaning, String source) {
        boolean[] found = {false};
        EntityService.get().forEachSemanticOfPattern(patternNid, semantic -> {
            SemanticEntityVersion latest = latestVersion(semantic.nid());
            if (latest.state() == State.INACTIVE && latest.fieldValues().get(0) instanceof EntityVertex v
                    && v.getMeaningNid() == meaning.nid() && source.equals(property(v, IkeConcept.LINE_SOURCE))) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /** The vertex whose Members hold the child, searched from a vertex down. */
    private static long parentOf(EntityVertex from, long child) {
        List<Long> stack = new ArrayList<>();
        for (long m : members(from)) {
            stack.add(m);
        }
        while (!stack.isEmpty()) {
            long nid = stack.removeLast();
            long[] own = members(latest(nid));
            for (long m : own) {
                if (m == child) {
                    return nid;
                }
                stack.add(m);
            }
        }
        throw new AssertionError("no parent of " + child);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
