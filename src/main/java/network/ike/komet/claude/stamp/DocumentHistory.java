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
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A stored document's history as the store keeps it, for the Graph view's History mode
 * (ike-lab-documents topic {@code dev-stamped-document-surface}; IKE-Network/ike-issues#1307):
 * every version of every line, oldest first, each a commit's STAMP, the text it wrote and the
 * kind of change it made; and the lines later commits retired, each placed after the surviving
 * line it followed in the last manifest that listed it, so a retired line can stand where it
 * stood.
 *
 * <p>The materializer writes a version of a line element under each commit that changed it and
 * an inactive version under the commit that removed it, and a manifest version, the line order,
 * under each commit that changed the file. The history reads those back; its placement is a
 * pure function of the manifest orders and the versions, so it is built from raw data for tests.
 */
public final class DocumentHistory {

    /** What a version did to its line. */
    public enum Change { INTRODUCED, RETEXTED, REFLOWED, RETIRED, UNCOMMITTED }

    /**
     * One version of a line.
     *
     * @param stamp  the commit's STAMP, or {@code null} where none is known
     * @param text   the text the version wrote, the previous text for a retirement
     * @param change what the version did
     */
    public record Version(StampEntity<?> stamp, String text, Change change) {

        /** @return the change in a word */
        public String word() {
            return change.name().toLowerCase();
        }
    }

    /**
     * A line a commit retired.
     *
     * @param nid        the line element's nid
     * @param text       its last text
     * @param introduced the STAMP of its first version, or {@code null}
     * @param retired    the STAMP of the retiring version, or {@code null} when the manifest alone records the removal
     * @param afterNid   the surviving line it followed in the last manifest that listed it, {@code 0} when it led the document
     */
    public record RetiredLine(long nid, String text, StampEntity<?> introduced, StampEntity<?> retired, long afterNid) { }

    /** A version as read from the store, before its change is named. */
    public record Raw(StampEntity<?> stamp, String text, boolean active) { }

    /** No history: every line as introduced by its one version, nothing retired. */
    public static final DocumentHistory NONE = new DocumentHistory(Map.of(), List.of());

    private final Map<Long, List<Version>> versions;
    private final List<RetiredLine> retired;
    private final Map<Long, List<RetiredLine>> retiredAfter = new HashMap<>();

    private DocumentHistory(Map<Long, List<Version>> versions, List<RetiredLine> retired) {
        this.versions = versions;
        this.retired = retired;
        for (RetiredLine line : retired) {
            retiredAfter.computeIfAbsent(line.afterNid(), k -> new ArrayList<>()).add(line);
        }
    }

    /**
     * The history from raw data: the manifest orders oldest first, and each line's versions
     * oldest first.
     *
     * @param orders   the line nids of every manifest version, oldest first; the last is the document as it stands
     * @param raw      every line's versions by nid, oldest first
     * @return the history
     */
    public static DocumentHistory from(List<List<Long>> orders, Map<Long, List<Raw>> raw) {
        Map<Long, List<Version>> versions = new LinkedHashMap<>();
        raw.forEach((nid, list) -> versions.put(nid, name(list)));
        List<RetiredLine> retired = new ArrayList<>();
        if (!orders.isEmpty()) {
            Set<Long> latest = new HashSet<>(orders.getLast());
            Set<Long> seen = new LinkedHashSet<>();
            for (List<Long> order : orders) {
                seen.addAll(order);
            }
            for (long nid : seen) {
                if (latest.contains(nid)) {
                    continue;
                }
                List<Long> last = null;
                for (List<Long> order : orders) {
                    if (order.contains(nid)) {
                        last = order;
                    }
                }
                if (last == null) {
                    continue;
                }
                long after = 0;
                for (int i = last.indexOf(nid) - 1; i >= 0; i--) {
                    if (latest.contains(last.get(i))) {
                        after = last.get(i);
                        break;
                    }
                }
                List<Version> of = versions.getOrDefault(nid, List.of());
                String text = "";
                StampEntity<?> introduced = null;
                StampEntity<?> retiring = null;
                for (Version v : of) {
                    if (v.change() == Change.RETIRED) {
                        retiring = v.stamp();
                    } else {
                        text = v.text();
                        if (introduced == null) {
                            introduced = v.stamp();
                        }
                    }
                }
                retired.add(new RetiredLine(nid, text, introduced, retiring, after));
            }
        }
        return new DocumentHistory(versions, retired);
    }

    /** Names each version's change from the one before it. */
    private static List<Version> name(List<Raw> raw) {
        List<Version> out = new ArrayList<>();
        String previous = null;
        for (Raw r : raw) {
            Change change;
            if (!r.active()) {
                change = Change.RETIRED;
            } else if (r.stamp() != null && r.stamp().time() == Long.MAX_VALUE) {
                change = Change.UNCOMMITTED;
            } else if (previous == null) {
                change = Change.INTRODUCED;
            } else if (!previous.equals(r.text())) {
                change = Change.RETEXTED;
            } else {
                change = Change.REFLOWED;
            }
            out.add(new Version(r.stamp(), r.active() ? r.text() : previous == null ? r.text() : previous, change));
            if (r.active()) {
                previous = r.text();
            }
        }
        return List.copyOf(out);
    }

    /**
     * Reads a document's history from the store.
     *
     * @param document the document
     * @param pending  whether to include an uncommitted version, as the document was loaded
     * @return the history, {@link #NONE} when the store holds no manifest
     */
    public static DocumentHistory load(StoredDocument document, boolean pending) {
        if (VertexReader.isVertexDocument(document.anchor())) {
            return VertexReader.history(document.anchor(), pending);      // IKE-Network/ike-issues#1313
        }
        long anchorNid = EntityService.get().nidForPublicId(document.anchor());
        List<SemanticEntity<SemanticEntityVersion>> manifests = EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.JOURNAL_MANIFEST_PATTERN.nid()).toList();
        if (manifests.isEmpty()) {
            return NONE;
        }
        List<SemanticEntityVersion> manifestVersions = new ArrayList<>();
        for (SemanticEntityVersion v : manifests.getFirst().versions()) {
            if (!v.canceled() && (v.time() != Long.MAX_VALUE || pending)) {
                manifestVersions.add(v);
            }
        }
        manifestVersions.sort(Comparator.comparingLong(SemanticEntityVersion::time));
        List<List<Long>> orders = new ArrayList<>();
        Set<Long> nids = new LinkedHashSet<>();
        for (SemanticEntityVersion v : manifestVersions) {
            List<Long> order = new ArrayList<>();
            for (long nid : ((LongIdList) v.fieldValues().get(0)).toArray()) {
                order.add(nid);
                nids.add(nid);
            }
            orders.add(order);
        }
        Map<Long, List<Raw>> raw = new LinkedHashMap<>();
        for (long nid : nids) {
            Optional<? extends Entity<? extends EntityVersion>> held = EntityHandle.get(nid).entity();
            if (held.isEmpty()) {
                continue;
            }
            List<SemanticEntityVersion> of = new ArrayList<>();
            for (EntityVersion version : held.get().versions()) {
                SemanticEntityVersion v = (SemanticEntityVersion) version;
                if (!v.canceled() && (v.time() != Long.MAX_VALUE || pending)) {
                    of.add(v);
                }
            }
            of.sort(Comparator.comparingLong(SemanticEntityVersion::time));
            List<Raw> list = new ArrayList<>();
            for (SemanticEntityVersion v : of) {
                list.add(new Raw(v.stamp(), String.valueOf(v.fieldValues().get(0)), v.state() == State.ACTIVE));
            }
            raw.put(nid, list);
        }
        return from(orders, raw);
    }

    /** @return a line's versions, oldest first; empty when the history does not know the line */
    public List<Version> versions(long lineNid) {
        return versions.getOrDefault(lineNid, List.of());
    }

    /** @return every retired line, in the order they were met */
    public List<RetiredLine> retired() {
        return retired;
    }

    /** @return the retired lines that followed the surviving line, in order */
    public List<RetiredLine> retiredAfter(long lineNid) {
        return retiredAfter.getOrDefault(lineNid, List.of());
    }

    /** @return the retired lines that led the document, before any surviving line */
    public List<RetiredLine> retiredAtStart() {
        return retiredAfter.getOrDefault(0L, List.of());
    }

    /** @return whether the history holds anything beyond the lines as they stand */
    public boolean isEmpty() {
        return retired.isEmpty() && versions.values().stream().allMatch(v -> v.size() <= 1);
    }
}
