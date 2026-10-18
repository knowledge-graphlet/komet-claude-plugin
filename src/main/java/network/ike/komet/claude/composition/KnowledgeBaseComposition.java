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
package network.ike.komet.claude.composition;

import dev.ikm.tinkar.common.id.LongIdCollection;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.FieldDefinitionForEntity;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.terms.KernelPattern;
import network.ike.komet.claude.doc.DocumentVocabulary;
import network.ike.komet.claude.stamp.StampNames;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the open knowledge base is made of, read from the registrations the IKE Registrar writes
 * into it (IKE-Network/ike-issues#1304). There are three kinds:
 * <ul>
 * <li><em>Release registrations.</em> There is one per release, identified by the importer, the
 * options that change its output, and the release's checksum. Each version records an importer
 * that read the release.</li>
 * <li><em>Assembly registrations.</em> There is one per distinct list of input checksums, and each
 * version is a build from those inputs.</li>
 * <li><em>Export registrations.</em> There is one per exported file, and each version is an export
 * that wrote it.</li>
 * </ul>
 *
 * <p>Every registration is on the Registry path, which a content view does not select, so every
 * committed version is read directly rather than through a view's stamp calculator. Fields are
 * read by index, in the order the kernel declares them.
 */
public final class KnowledgeBaseComposition {

    private static final DateTimeFormatter MOMENT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm zzz").withZone(ZoneId.systemDefault());
    /** Release dates are a publisher's dates, midnights in UTC. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    /** Whether the store can say what it is made of. */
    public enum Recorded {
        /** The store's starter set predates the registration patterns. */
        REGISTRY_ABSENT,
        /** The patterns are declared, but nothing registered anything. */
        NOT_RECORDED,
        /** At least one registration is present. */
        RECORDED
    }

    /**
     * One version of a release registration: an importer's reading of the release.
     *
     * @param stamp           the Registry stamp it was written under
     * @param releaseName     the source's name for the release
     * @param releaseDate     the publisher's release date
     * @param artifact        the release as it was named: a Maven coordinate or a file name
     * @param resolvedVersion the version the coordinate resolved to; empty for a file
     * @param checksum        {@code sha256:<hex>} of the release
     * @param importer        the importer's name and version
     * @param options         the options that change the importer's output, {@code name=value} lines
     * @param stampsWritten   the stamps the import wrote under, by nid
     * @param written         components written
     * @param versioned       components given a new version
     * @param unchanged       components already as the release has them
     * @param retired         semantics retired
     * @param baseReleases    the release registrations already in the store, by nid
     */
    public record ReleaseVersion(StampEntity<?> stamp, String releaseName, Instant releaseDate, String artifact,
                                 String resolvedVersion, String checksum, String importer, String options,
                                 List<Long> stampsWritten, long written, long versioned, long unchanged,
                                 long retired, List<Long> baseReleases) {

        /** @return the resolved version when it says more than the coordinate, else empty */
        public String resolvedBeyondArtifact() {
            return beyond(artifact, resolvedVersion);
        }

        /** @return the importer's name, without its version */
        public String importerName() {
            int space = importer.indexOf(' ');
            return space < 0 ? importer : importer.substring(0, space);
        }
    }

    /**
     * A release registration and every importer that has read the release, oldest first.
     *
     * @param nid      the registration's nid
     * @param versions its committed versions, oldest first; never empty
     */
    public record Release(long nid, List<ReleaseVersion> versions) {

        /** @return the latest reading of the release */
        public ReleaseVersion latest() {
            return versions.getLast();
        }

        /** @return how the release reads in a list: its importer and its name */
        public String label() {
            return latest().importerName() + " " + latest().releaseName();
        }
    }

    /**
     * One input of an assembly, as the registration records it.
     *
     * @param role            the input's role ({@code PB}, {@code SOURCE}, …)
     * @param artifact        its Maven coordinate or file name, as named
     * @param resolvedVersion the version the coordinate resolved to, or empty
     * @param checksum        {@code sha256:<hex>}, or empty when the line carried none
     */
    public record Input(String role, String artifact, String resolvedVersion, String checksum) {

        /** @return the resolved version when it says more than the coordinate, else empty */
        public String resolvedBeyondArtifact() {
            return beyond(artifact, resolvedVersion);
        }
    }

    /**
     * One version of an assembly registration: one build.
     *
     * @param nid        the registration's nid; a rebuild from the same inputs shares it
     * @param stamp      the Registry stamp, at the time the assembly ran
     * @param inputs     the assembly's inputs, in load order
     * @param releases   the release registrations, in the order they arrived, by nid
     * @param classifier the classifier that ran, or empty
     */
    public record Assembly(long nid, StampEntity<?> stamp, List<Input> inputs, List<Long> releases,
                           String classifier) {

        /** @return when the assembly ran */
        public Instant assembled() {
            return Instant.ofEpochMilli(stamp.time());
        }
    }

    /**
     * One version of an export registration: one export.
     *
     * @param stamp      the Registry stamp, at the time the export ran
     * @param file       the file the export wrote
     * @param selection  what it selected
     * @param checksum   {@code sha256:<hex>} of the file
     * @param components how many components it wrote
     */
    public record Export(StampEntity<?> stamp, String file, String selection, String checksum, long components) {
    }

    /**
     * What changed from one assembly to the next.
     *
     * @param added         inputs whose artifact the previous assembly did not load
     * @param removed       inputs of the previous assembly this one did not load
     * @param changed       inputs loaded by both, with a different checksum
     * @param releasesAdded release registrations this assembly holds and the previous did not
     */
    public record Changes(List<Input> added, List<Input> removed, List<Input> changed, List<Long> releasesAdded) {

        /** @return true when nothing changed: a rebuild */
        public boolean none() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty() && releasesAdded.isEmpty();
        }
    }

    /**
     * What the store holds.
     *
     * @param recorded   whether it can say, and did
     * @param releases   the releases, in the order they arrived
     * @param assemblies the assemblies, newest first
     * @param exports    the exports, newest first
     */
    public record Composition(Recorded recorded, List<Release> releases, List<Assembly> assemblies,
                              List<Export> exports) {

        /** @return the most recent assembly, if one was registered */
        public Optional<Assembly> latest() {
            return assemblies.stream().findFirst();
        }
    }

    private KnowledgeBaseComposition() {
    }

    /**
     * Reads what the open store is made of.
     *
     * @return the composition; {@link Recorded#REGISTRY_ABSENT} when the store cannot record it
     */
    public static Composition read() {
        if (DocumentVocabulary.entityAbsent(KernelPattern.RELEASE_REGISTRATION_PATTERN)
                || DocumentVocabulary.entityAbsent(KernelPattern.ASSEMBLY_REGISTRATION_PATTERN)
                || DocumentVocabulary.entityAbsent(KernelPattern.EXPORT_REGISTRATION_PATTERN)) {
            return new Composition(Recorded.REGISTRY_ABSENT, List.of(), List.of(), List.of());
        }
        Map<Long, Release> releases = new LinkedHashMap<>();
        EntityService.get().semanticsOfPattern(KernelPattern.RELEASE_REGISTRATION_PATTERN.nid()).forEach(semantic -> {
            List<ReleaseVersion> versions = committed(semantic).stream().map(KnowledgeBaseComposition::release).toList();
            if (!versions.isEmpty()) {
                releases.put(semantic.nid(), new Release(semantic.nid(), versions));
            }
        });
        List<Assembly> assemblies = new ArrayList<>();
        EntityService.get().semanticsOfPattern(KernelPattern.ASSEMBLY_REGISTRATION_PATTERN.nid())
                .forEach(semantic -> committed(semantic).stream().map(v -> assembly(semantic.nid(), v))
                        .forEach(assemblies::add));
        assemblies.sort(Comparator.comparingLong((Assembly a) -> a.stamp().time()).reversed());
        List<Export> exports = new ArrayList<>();
        EntityService.get().semanticsOfPattern(KernelPattern.EXPORT_REGISTRATION_PATTERN.nid())
                .forEach(semantic -> committed(semantic).stream().map(KnowledgeBaseComposition::export)
                        .forEach(exports::add));
        exports.sort(Comparator.comparingLong((Export e) -> e.stamp().time()).reversed());
        if (releases.isEmpty() && assemblies.isEmpty() && exports.isEmpty()) {
            return new Composition(Recorded.NOT_RECORDED, List.of(), List.of(), List.of());
        }
        return new Composition(Recorded.RECORDED, arrivalOrder(releases, assemblies), List.copyOf(assemblies),
                List.copyOf(exports));
    }

    /**
     * The releases in the order the latest assembly says they arrived, then any it does not name
     * (one imported since, or a store no assembly registered) by release date.
     */
    private static List<Release> arrivalOrder(Map<Long, Release> releases, List<Assembly> assemblies) {
        Set<Long> order = new LinkedHashSet<>();
        if (!assemblies.isEmpty()) {
            assemblies.getFirst().releases().stream().filter(releases::containsKey).forEach(order::add);
        }
        releases.values().stream()
                .sorted(Comparator.comparing((Release r) -> r.latest().releaseDate()))
                .forEach(r -> order.add(r.nid()));
        return order.stream().map(releases::get).toList();
    }

    /** The semantic's committed versions on stamps that are not cancelled, oldest first. */
    private static List<SemanticEntityVersion> committed(SemanticEntity<? extends SemanticEntityVersion> semantic) {
        List<SemanticEntityVersion> versions = new ArrayList<>();
        for (SemanticEntityVersion version : semantic.versions()) {
            if (version.time() != Long.MAX_VALUE && !version.stamp().canceled()) {
                versions.add(version);
            }
        }
        versions.sort(Comparator.comparingLong(SemanticEntityVersion::time));
        return versions;
    }

    private static ReleaseVersion release(SemanticEntityVersion version) {
        List<Object> fields = version.fieldValues().castToList();
        return new ReleaseVersion(version.stamp(), text(fields, 0), instant(fields, 1), text(fields, 2),
                text(fields, 3), text(fields, 4), text(fields, 5), text(fields, 6), nids(fields, 7),
                count(fields, 8), count(fields, 9), count(fields, 10), count(fields, 11), nids(fields, 12));
    }

    private static Assembly assembly(long nid, SemanticEntityVersion version) {
        List<Object> fields = version.fieldValues().castToList();
        return new Assembly(nid, version.stamp(), inputs(text(fields, 0)), nids(fields, 1), text(fields, 2));
    }

    private static Export export(SemanticEntityVersion version) {
        List<Object> fields = version.fieldValues().castToList();
        return new Export(version.stamp(), text(fields, 0), text(fields, 1), text(fields, 2), count(fields, 3));
    }

    /**
     * Parses an assembly's inputs: one per line, {@code ROLE artifact [resolvedVersion] sha256:<hex>}.
     * The role is the first word and the checksum the last. Between them, a Maven coordinate
     * (it holds a colon and no space) may be followed by its resolved version; anything else is a
     * file name, which may hold spaces.
     *
     * @param described the field's text
     * @return the inputs, in load order
     */
    public static List<Input> inputs(String described) {
        List<Input> inputs = new ArrayList<>();
        for (String line : described.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int first = trimmed.indexOf(' ');
            if (first < 0) {
                inputs.add(new Input(trimmed, "", "", ""));
                continue;
            }
            String role = trimmed.substring(0, first);
            String rest = trimmed.substring(first + 1).strip();
            String checksum = "";
            int last = rest.lastIndexOf(' ');
            if (last >= 0 && rest.substring(last + 1).startsWith("sha256:")) {
                checksum = rest.substring(last + 1);
                rest = rest.substring(0, last).strip();
            }
            String artifact = rest;
            String resolved = "";
            int space = rest.indexOf(' ');
            if (space > 0 && rest.substring(0, space).contains(":")) {
                artifact = rest.substring(0, space);
                resolved = rest.substring(space + 1).strip();
            }
            inputs.add(new Input(role, artifact, resolved, checksum));
        }
        return inputs;
    }

    /**
     * What changed from one assembly to the next. Inputs are matched by artifact as named, so a
     * snapshot that resolved to a new build reads as a changed checksum, and a new release under a
     * new coordinate as one input removed and one added.
     *
     * @param previous the earlier assembly
     * @param next     the later one
     * @return the changes
     */
    public static Changes changes(Assembly previous, Assembly next) {
        Map<String, Input> before = previous.inputs().stream()
                .collect(Collectors.toMap(Input::artifact, i -> i, (a, b) -> a, LinkedHashMap::new));
        Map<String, Input> after = next.inputs().stream()
                .collect(Collectors.toMap(Input::artifact, i -> i, (a, b) -> a, LinkedHashMap::new));
        List<Input> added = after.values().stream().filter(i -> !before.containsKey(i.artifact())).toList();
        List<Input> removed = before.values().stream().filter(i -> !after.containsKey(i.artifact())).toList();
        List<Input> changed = after.values().stream()
                .filter(i -> before.containsKey(i.artifact())
                        && !before.get(i.artifact()).checksum().equals(i.checksum()))
                .toList();
        List<Long> releasesAdded = next.releases().stream()
                .filter(nid -> !previous.releases().contains(nid)).toList();
        return new Changes(added, removed, changed, releasesAdded);
    }

    /**
     * A resolved version, unless the coordinate already ends with it: a release, or a snapshot
     * resolved from the local repository, resolves to the version it names.
     *
     * @param artifact the artifact as named
     * @param resolved the version it resolved to, or empty
     * @return the resolved version, or empty when it adds nothing
     */
    static String beyond(String artifact, String resolved) {
        return resolved.isEmpty() || artifact.endsWith(":" + resolved) ? "" : resolved;
    }

    /**
     * The modules of the stamps an import wrote under, by nid, in first-seen order.
     *
     * @param stamps the stamps, by nid
     * @return the modules, by nid
     */
    public static List<Long> modules(List<Long> stamps) {
        Set<Long> modules = new LinkedHashSet<>();
        for (long stamp : stamps) {
            modules.add(EntityService.get().getStampFast(stamp).moduleNid());
        }
        return List.copyOf(modules);
    }

    /**
     * The labels of a pattern's fields: the names of their meanings, in field order, so the view
     * shows the kernel's own words.
     *
     * @param view    the view that names them
     * @param pattern the pattern
     * @return the labels; empty when the store lacks the pattern
     */
    public static List<String> fieldLabels(ViewCalculator view, KernelPattern pattern) {
        Optional<PatternEntity> found = EntityHandle.get(pattern.nid()).asPattern();
        if (found.isEmpty()) {
            return List.of();
        }
        // The raw PatternEntity erases its versions' type; each is a PatternEntityVersion.
        PatternEntityVersion latest = null;
        for (Object version : found.get().versions()) {
            if (version instanceof PatternEntityVersion v && v.time() != Long.MAX_VALUE
                    && (latest == null || v.time() > latest.time())) {
                latest = v;
            }
        }
        if (latest == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        for (FieldDefinitionForEntity definition : latest.fieldDefinitions()) {
            labels.add(StampNames.name(view, definition.meaningNid()));
        }
        return labels;
    }

    /**
     * The name of a component, a release registration being named by its importer and release.
     *
     * @param view the view that names components
     * @param nid  the component's nid
     * @return its name
     */
    @SuppressWarnings("unchecked")
    public static String name(ViewCalculator view, long nid) {
        Optional<SemanticEntity> semantic = EntityHandle.get(nid).asSemantic()
                .filter(s -> s.patternNid() == KernelPattern.RELEASE_REGISTRATION_PATTERN.nid());
        if (semantic.isPresent()) {
            List<SemanticEntityVersion> versions = committed(semantic.get());
            if (!versions.isEmpty()) {
                return new Release(nid, versions.stream().map(KnowledgeBaseComposition::release).toList()).label();
            }
        }
        return StampNames.name(view, nid);
    }

    /**
     * The composition as plain text, for pasting into an issue or a note.
     *
     * @param view        the view that names components
     * @param composition what the store holds
     * @return the text
     */
    public static String text(ViewCalculator view, Composition composition) {
        if (composition.recorded() == Recorded.REGISTRY_ABSENT) {
            return "This knowledge base's starter set has no registry patterns; it cannot record what it is made of.\n";
        }
        if (composition.recorded() == Recorded.NOT_RECORDED) {
            return "This knowledge base holds no registrations: nothing that built it registered what it loaded.\n";
        }
        StringBuilder out = new StringBuilder();
        composition.latest().ifPresent(a -> {
            out.append("Assembled ").append(MOMENT.format(a.assembled()));
            if (!a.classifier().isBlank()) {
                out.append(", classified by ").append(a.classifier());
            }
            out.append('\n');
        });
        out.append("\nReleases (").append(composition.releases().size()).append("):\n");
        for (Release release : composition.releases()) {
            ReleaseVersion now = release.latest();
            out.append("- ").append(now.releaseName()).append(" (").append(DAY.format(now.releaseDate())).append(")\n")
                    .append("  ").append(now.artifact())
                    .append(now.resolvedBeyondArtifact().isEmpty() ? "" : " → " + now.resolvedBeyondArtifact())
                    .append(' ').append(now.checksum()).append('\n')
                    .append("  importer ").append(now.importer())
                    .append(now.options().isEmpty() ? "" : " (" + now.options().replace('\n', ' ') + ")").append('\n')
                    .append("  written ").append(now.written()).append(", versioned ").append(now.versioned())
                    .append(", unchanged ").append(now.unchanged()).append(", retired ").append(now.retired())
                    .append('\n');
            if (!now.baseReleases().isEmpty()) {
                out.append("  on ").append(now.baseReleases().stream().map(nid -> name(view, nid))
                        .collect(Collectors.joining(", "))).append('\n');
            }
            if (release.versions().size() > 1) {
                out.append("  also read by: ").append(release.versions().subList(0, release.versions().size() - 1)
                        .stream().map(ReleaseVersion::importer).collect(Collectors.joining(", "))).append('\n');
            }
        }
        composition.latest().ifPresent(a -> {
            out.append("\nAssembly inputs (").append(a.inputs().size()).append("):\n");
            a.inputs().forEach(i -> out.append("- ").append(i.role()).append(' ').append(i.artifact())
                    .append(i.resolvedBeyondArtifact().isEmpty() ? "" : " → " + i.resolvedBeyondArtifact())
                    .append(i.checksum().isEmpty() ? "" : " " + i.checksum()).append('\n'));
        });
        if (composition.assemblies().size() > 1) {
            out.append("\nAssembly history:\n");
            for (Assembly a : composition.assemblies()) {
                out.append("- ").append(MOMENT.format(a.assembled())).append(", ").append(a.inputs().size())
                        .append(" inputs").append(a.classifier().isBlank() ? "" : ", " + a.classifier()).append('\n');
            }
        }
        if (!composition.exports().isEmpty()) {
            out.append("\nExports:\n");
            for (Export e : composition.exports()) {
                out.append("- ").append(MOMENT.format(Instant.ofEpochMilli(e.stamp().time()))).append(' ')
                        .append(e.file()).append(", ").append(e.components()).append(" components")
                        .append(e.selection().isBlank() ? "" : ", " + e.selection()).append(' ').append(e.checksum())
                        .append('\n');
            }
        }
        return out.toString();
    }

    private static String text(List<Object> fields, int index) {
        return index < fields.size() && fields.get(index) != null ? String.valueOf(fields.get(index)) : "";
    }

    private static Instant instant(List<Object> fields, int index) {
        return index < fields.size() && fields.get(index) instanceof Instant instant ? instant : Instant.EPOCH;
    }

    private static long count(List<Object> fields, int index) {
        return index < fields.size() && fields.get(index) instanceof Number number ? number.longValue() : 0;
    }

    private static List<Long> nids(List<Object> fields, int index) {
        if (index < fields.size() && fields.get(index) instanceof LongIdCollection ids) {
            List<Long> nids = new ArrayList<>(ids.size());
            ids.forEach(nids::add);
            return List.copyOf(nids);
        }
        return List.of();
    }
}
