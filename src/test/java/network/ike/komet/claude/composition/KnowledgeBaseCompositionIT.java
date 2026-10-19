package network.ike.komet.claude.composition;

import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Assembly;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Changes;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Composition;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Input;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Recorded;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Release;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.ReleaseVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition reader against the IKE starter set, with registrations written the way the
 * importer API's {@code Registry} writes them (IKE-Network/ike-issues#1304): Registry stamps by
 * the Registrar, each registration referring to its own pattern, fields in the kernel's order.
 * The store holds a release that two importer versions read, a second release layered on it, a
 * new release of the first source, an assembly rebuilt once, a second assembly from different
 * inputs, and an export. It is read directly and through the assistant's {@code kb_composition}
 * tool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KnowledgeBaseCompositionIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertTrue(count > 0, "Should load entities from the starter-data protobuf file");
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void readsReleasesInArrivalOrderAssembliesAndExportsNewestFirst() {
        // The starter set registers its own reasoning build: one assembly, no release, no export.
        Composition starter = KnowledgeBaseComposition.read();
        assertEquals(Recorded.RECORDED, starter.recorded());
        assertEquals(List.of(), starter.releases());
        assertEquals(List.of(), starter.exports());
        assertEquals(1, starter.assemblies().size(), "the starter set's own assembly");
        long starterBuilt = starter.assemblies().getFirst().stamp().time();
        // Everything this test registers comes after the starter set's build.
        registeredFrom = starterBuilt;

        PublicId firstId = id("release: first 2026-01-01");
        long first = release(at(1_000L), firstId, "First 2026-01-01", "g:first:zip:2026-01-01", "", "sha256:aa",
                "first 1", "", List.of());
        release(at(1_500L), firstId, "First 2026-01-01", "g:first:zip:2026-01-01", "", "sha256:aa",
                "first 2", "", List.of());
        long second = release(at(2_000L), id("release: second"), "Second 2026-02-01", "second.zip", "", "sha256:bb",
                "second 1", "edition=full", List.of(first));
        assemble(at(3_000L), "inputs one", "PB g:starter:zip:1-SNAPSHOT 1-20261017.1 sha256:00\n"
                + "SOURCE g:first:zip:2026-01-01 sha256:aa\nSOURCE second.zip sha256:bb", List.of(first, second), "");
        assemble(at(5_000L), "inputs one", "PB g:starter:zip:1-SNAPSHOT 1-20261017.1 sha256:00\n"
                + "SOURCE g:first:zip:2026-01-01 sha256:aa\nSOURCE second.zip sha256:bb", List.of(first, second), "");
        long third = release(at(4_000L), id("release: first 2026-03-01"), "First 2026-03-01", "g:first:zip:2026-03-01", "",
                "sha256:cc", "first 2", "", List.of(first, second));
        assemble(at(6_000L), "inputs two", "PB g:starter:zip:1-SNAPSHOT 1-20261018.2 sha256:01\n"
                + "SOURCE g:first:zip:2026-03-01 sha256:cc\nSOURCE second.zip sha256:bb",
                List.of(first, second, third), "ElkSnomed");
        export(at(7_000L), "kb-reasoned-pb.zip", "all modules", "sha256:ee", 42L);

        Composition composition = KnowledgeBaseComposition.read();
        assertEquals(Recorded.RECORDED, composition.recorded());
        assertEquals(List.of(first, second, third), composition.releases().stream().map(Release::nid).toList(),
                "the order the latest assembly says they arrived");
        Release firstRelease = composition.releases().getFirst();
        assertEquals(List.of("first 1", "first 2"), firstRelease.versions().stream().map(ReleaseVersion::importer).toList(),
                "each importer version that read the release is a version, oldest first");
        assertEquals("first", firstRelease.latest().importerName());
        assertEquals("first First 2026-01-01", firstRelease.label());
        ReleaseVersion secondRelease = composition.releases().get(1).latest();
        assertEquals("edition=full", secondRelease.options());
        assertEquals(List.of(first), secondRelease.baseReleases());
        assertEquals(10L, secondRelease.written());
        assertEquals(3L, secondRelease.retired());
        assertEquals(List.of(KernelConcept.REGISTRY_MODULE.nid()),
                KnowledgeBaseComposition.modules(secondRelease.stampsWritten()));
        assertEquals("sha256:cc", composition.releases().get(2).latest().checksum(),
                "a new release of a source is a registration of its own");

        assertEquals(List.of(at(6_000L), at(5_000L), at(3_000L), starterBuilt),
                composition.assemblies().stream().map(a -> a.stamp().time()).toList(), "assembly builds newest first");
        Assembly latest = composition.latest().orElseThrow();
        assertEquals("ElkSnomed", latest.classifier());
        assertEquals(new Input("PB", "g:starter:zip:1-SNAPSHOT", "1-20261018.2", "sha256:01"), latest.inputs().getFirst());
        assertEquals(new Input("SOURCE", "second.zip", "", "sha256:bb"), latest.inputs().get(2));
        assertEquals("1-20261018.2", latest.inputs().getFirst().resolvedBeyondArtifact());
        assertEquals("", new Input("PB", "g:starter:zip:1-SNAPSHOT", "1-SNAPSHOT", "sha256:01").resolvedBeyondArtifact(),
                "a version that only repeats the coordinate's says nothing more");
        assertEquals(composition.assemblies().get(1).nid(), composition.assemblies().get(2).nid(),
                "a rebuild from the same inputs is a version of the same registration");

        assertTrue(KnowledgeBaseComposition.changes(composition.assemblies().get(2), composition.assemblies().get(1)).none());
        Changes changes = KnowledgeBaseComposition.changes(composition.assemblies().get(1), latest);
        assertEquals(List.of("g:first:zip:2026-03-01"), changes.added().stream().map(Input::artifact).toList());
        assertEquals(List.of("g:first:zip:2026-01-01"), changes.removed().stream().map(Input::artifact).toList());
        assertEquals(List.of("g:starter:zip:1-SNAPSHOT"), changes.changed().stream().map(Input::artifact).toList(),
                "a snapshot resolved to a new build reads as a new checksum");
        assertEquals(List.of(third), changes.releasesAdded());

        assertEquals(1, composition.exports().size());
        assertEquals(42L, composition.exports().getFirst().components());

        String text = KnowledgeBaseComposition.text(Calculators.View.Default(), composition);
        assertTrue(text.contains("First 2026-03-01"), text);
        assertTrue(text.contains("also read by: first 1"), text);
        assertTrue(text.contains("classified by ElkSnomed"), text);
        assertTrue(text.contains("on first First 2026-01-01"), text);
        assertTrue(text.contains("kb-reasoned-pb.zip, 42 components"), text);
        assertEquals("first First 2026-01-01", KnowledgeBaseComposition.name(Calculators.View.Default(), first),
                "a release registration is named by its importer and release");

        AnthropicTool tool = KnowledgeBaseCompositionTools.kbComposition(Calculators.View::Default);
        assertEquals("kb_composition", tool.name());
        assertEquals(text, tool.execute(Map.of()), "the tool answers in the card's words");
    }

    @Test
    void compositionToolIsReadOnlyAndNeedsAView() {
        AnthropicTool tool = KnowledgeBaseCompositionTools.tools(() -> null).getFirst();
        assertEquals(List.of(), tool.inputSchema().get("required"));
        assertEquals("No active knowledge-base view is available.", tool.execute(Map.of()));
    }

    private long registeredFrom;

    private long at(long offset) {
        return registeredFrom + offset;
    }

    private static PublicId id(String key) {
        return PublicIds.of(UUID.nameUUIDFromBytes(key.getBytes()));
    }

    private static StampedWriter registry(long time) {
        return StampedWriter.open("registration", State.ACTIVE, time, KernelConcept.REGISTRAR,
                KernelConcept.REGISTRY_MODULE, KernelConcept.REGISTRY_PATH);
    }

    private static long release(long time, PublicId id, String name, String artifact, String resolved, String checksum,
                                String importer, String options, List<Long> base) {
        try (StampedWriter writer = registry(time)) {
            // Stamps written: the registration's own, so the module they name is the Registry module.
            long nid = writer.semantic(id, KernelPattern.RELEASE_REGISTRATION_PATTERN.publicId(),
                    KernelPattern.RELEASE_REGISTRATION_PATTERN.publicId(),
                    name, Instant.ofEpochMilli(time), artifact, resolved, checksum, importer, options,
                    LongIds.set.of(writer.stamp().nid()), 10L, 5L, 2L, 3L,
                    LongIds.set.of(base.stream().mapToLong(Long::longValue).toArray()));
            writer.commit();
            return nid;
        }
    }

    private static void assemble(long time, String key, String inputs, List<Long> releases, String classifier) {
        try (StampedWriter writer = registry(time)) {
            writer.semantic(id("assembly: " + key), KernelPattern.ASSEMBLY_REGISTRATION_PATTERN.publicId(),
                    KernelPattern.ASSEMBLY_REGISTRATION_PATTERN.publicId(), inputs,
                    LongIds.list.of(releases.stream().mapToLong(Long::longValue).toArray()), classifier);
            writer.commit();
        }
    }

    private static void export(long time, String file, String selection, String checksum, long components) {
        try (StampedWriter writer = registry(time)) {
            writer.semantic(id("export: " + checksum), KernelPattern.EXPORT_REGISTRATION_PATTERN.publicId(),
                    KernelPattern.EXPORT_REGISTRATION_PATTERN.publicId(), file, selection, checksum, components);
            writer.commit();
        }
    }
}
