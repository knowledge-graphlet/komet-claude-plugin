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

import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PublicIdentifierRecord;
import dev.ikm.tinkar.entity.RecordListBuilder;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.SemanticRecordBuilder;
import dev.ikm.tinkar.entity.SemanticVersionRecord;
import dev.ikm.tinkar.entity.SemanticVersionRecordBuilder;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkePattern;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.KometPreferencesImpl;
import network.ike.komet.claude.doc.DocumentVocabulary;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Edits a materialized document in the graph, one block at a time, as uncommitted versions the
 * person settles by committing the file in git (ike-lab-documents topic {@code dev-komet-actions}:
 * an edit lands as uncommitted versions, like the activate and inactivate actions).
 *
 * <p>An edit replaces the lines of one block. The old and new lines are matched on their text:
 * an unchanged line keeps its element; a changed line gets a new version of its element with the
 * new text, keeping the identity git gave it; a new line gets a provisional element, since its
 * identity, {@code line:<sha>:<position>}, comes from a commit that does not exist yet; a removed
 * line leaves the manifest, and the commit retires it. The manifest and the block get new
 * versions listing the result.
 *
 * <p>Every edit to one document writes into one pending transaction, under one stamp, so editing
 * a block again replaces its pending versions rather than stacking a second set, and the whole
 * pending edit can be cancelled at once: when the person's commit is materialized, {@link #settle}
 * cancels the transaction, the commit's own lines stand in its place, and what the commit did not
 * carry is reported.
 */
public final class DocumentEditor {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DocumentEditor.class);

    private DocumentEditor() { }

    /**
     * What one edit did.
     *
     * @param block   the edited block's nid
     * @param changed lines whose text changed
     * @param added   new lines, with provisional elements
     * @param removed lines that left the block
     */
    public record Edit(long block, int changed, int added, int removed) {

        /** @return whether the edit changed anything */
        public boolean changedAnything() {
            return changed + added + removed > 0;
        }
    }

    /**
     * Replaces the lines of one block with new text.
     *
     * @param anchor the document anchor's id
     * @param block  the nid of a block whose members are all lines (a paragraph, a listing)
     * @param text   the block's new text; one line per line
     * @param edit   the edit coordinate whose author and path the edit is written under
     * @return what the edit did
     * @throws IllegalArgumentException when the block is not one of the document's, holds child
     *                                  blocks, or the new text is empty
     */
    public static synchronized Edit editBlock(PublicId anchor, long block, String text, EditCoordinate edit) {
        return editBlock(anchor, block, text, edit.getAuthorNidForChanges(), edit.getDefaultPathNid());
    }

    /** The edit under an author and path given directly: the edit coordinate's, or, on a rebase, the edit's own. */
    private static Edit editBlock(PublicId anchor, long block, String text, long authorNid, long pathNid) {
        DocumentVocabulary.requirePresent();
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(anchor, true)
                .orElseThrow(() -> new IllegalArgumentException("The store holds no document " + anchor.idString()));
        GitDocumentMaterializer.StoredBlock stored = document.blocks().get(block);
        if (stored == null) {
            throw new IllegalArgumentException("Block " + block + " is not a block of " + anchor.idString());
        }
        List<Long> manifest = new ArrayList<>(document.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList());
        Set<Long> lineNids = Set.copyOf(manifest);
        if (!lineNids.containsAll(stored.members())) {
            throw new IllegalArgumentException("A " + stored.kind() + " block holding other blocks is not edited as text");
        }
        int start = manifest.indexOf(stored.members().getFirst());
        for (int i = 0; i < stored.members().size(); i++) {
            if (start + i >= manifest.size() || manifest.get(start + i).longValue() != stored.members().get(i)) {
                throw new IllegalArgumentException("The block's lines are not contiguous in the document");
            }
        }
        List<String> newLines = text.lines().toList();
        if (newLines.isEmpty()) {
            throw new IllegalArgumentException("A block keeps at least one line; removing a block is not an edit of its text");
        }
        List<Long> oldNids = stored.members();
        List<String> oldLines = oldNids.stream().map(nid -> document.line(nid).orElseThrow().text()).toList();
        List<Step> steps = diff(oldLines, newLines);
        if (steps.stream().allMatch(step -> step.kind == Kind.KEEP)) {
            return new Edit(block, 0, 0, 0);
        }

        Transaction transaction = pending(anchor).orElseGet(
                () -> Transaction.make("Edit document " + anchor.idString() + BASE + document.commit()));
        long manifestNid = manifestNid(anchor);
        SemanticEntityVersion manifestHead = GitDocumentMaterializer.latest(semantic(manifestNid), true);
        StampEntity<?> stamp = transaction.getStamp(State.ACTIVE, Long.MAX_VALUE, authorNid, manifestHead.moduleNid(), pathNid);
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        List<Long> newNids = new ArrayList<>();
        int changed = 0;
        int added = 0;
        int removed = 0;
        for (Step step : steps) {
            switch (step.kind) {
                case KEEP -> newNids.add(oldNids.get(step.oldIndex));
                case RETEXT -> {
                    long nid = oldNids.get(step.oldIndex);
                    writeVersion(transaction, nid, stamp, Lists.immutable.of(newLines.get(step.newIndex)));
                    newNids.add(nid);
                    changed++;
                }
                case INSERT -> {
                    newNids.add(writeNew(transaction, PublicIds.newRandom(), IkePattern.PROSE_ELEMENT_PATTERN.publicId(),
                            anchorNid, stamp, Lists.immutable.of(newLines.get(step.newIndex))));
                    added++;
                }
                case REMOVE -> removed++;
            }
        }
        List<Long> order = new ArrayList<>(manifest.subList(0, start));
        order.addAll(newNids);
        order.addAll(manifest.subList(start + oldNids.size(), manifest.size()));
        writeVersion(transaction, manifestNid, stamp, Lists.immutable.of(LongIds.list.of(order.stream().mapToLong(Long::longValue).toArray())));
        SemanticEntityVersion blockHead = GitDocumentMaterializer.latest(semantic(block), true);
        List<Object> blockFields = new ArrayList<>(blockHead.fieldValues().castToList());
        blockFields.set(4, LongIds.list.of(newNids.stream().mapToLong(Long::longValue).toArray()));
        writeVersion(transaction, block, stamp, Lists.immutable.ofAll(blockFields));
        return new Edit(block, changed, added, removed);
    }

    /**
     * @param anchor the document anchor's id
     * @return the transaction holding the document's pending edit, if any
     */
    public static Optional<Transaction> pending(PublicId anchor) {
        if (!PrimitiveData.get().hasPublicId(anchor)) {
            return Optional.empty();
        }
        long manifestNid = manifestNid(anchor);
        if (manifestNid == 0) {
            return Optional.empty();
        }
        for (SemanticEntityVersion version : semantic(manifestNid).versions()) {
            if (version.uncommitted() && !version.canceled()) {
                Optional<Transaction> transaction = Transaction.forVersion(version);
                if (transaction.isPresent()) {
                    return transaction;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Cancels the document's pending edit, so the document reads as committed again.
     *
     * @param anchor the document anchor's id
     * @return whether there was a pending edit to cancel
     */
    public static synchronized boolean cancel(PublicId anchor) {
        Optional<Transaction> transaction = pending(anchor);
        if (transaction.isPresent()) {
            GitDocumentMaterializer.load(anchor, true).ifPresent(pending -> keep(anchor, pending));
            transaction.get().cancel();
        }
        return transaction.isPresent();
    }

    /**
     * What settling a pending edit found.
     *
     * @param settled       whether a pending edit was settled: a commit newer than the one it was
     *                      made against, and touching the blocks it edits, has been materialized
     * @param rebased       whether the pending edit was carried forward instead, onto a newer commit
     *                      that touches none of the blocks it edits; it stays pending
     * @param carried       whether the commit holds the document exactly as the pending edit had it
     * @param notCarried    lines written in Komet that the commit does not hold, in document order;
     *                      their pending versions are cancelled with the rest
     * @param committedOnly lines the commit holds that the pending edit did not: edits made to the
     *                      file outside Komet, or lines the edit removed that the person kept
     */
    public record Settlement(boolean settled, boolean rebased, boolean carried, List<String> notCarried, List<String> committedOnly) {

        static final Settlement NONE = new Settlement(false, false, false, List.of(), List.of());
        static final Settlement REBASED = new Settlement(false, true, false, List.of(), List.of());
    }

    /**
     * Settles the document's pending edit once the person's commit is materialized. The commit
     * is the person's word on the edit: whatever it holds is the document, and every pending
     * version is cancelled, whether the commit carried it or not, so nothing is left pending
     * against an older commit. What the commit did not carry, and what it holds that the edit did
     * not, is reported. The lines are matched in order, so a line repeated in the document (a
     * listing delimiter, a list marker) pairs with its counterpart by position, never by its text
     * alone.
     *
     * <p>Nothing is settled while the document's latest commit is still the one the edit was made
     * against: the edit is still being made. Nor is anything settled by a commit that touches none
     * of the edited blocks (a pull of someone else's work, the person's own commit to another part
     * of the file): the edit is carried forward onto that commit and stays pending. A commit touches
     * a block when the block, or any line it held when the edit was made, has a committed version
     * newer than the edit's base; the walker's line identities make that exact.
     *
     * @param anchor the document anchor's id
     * @return what settling found
     */
    public static synchronized Settlement settle(PublicId anchor) {
        Optional<Transaction> transaction = pending(anchor);
        if (transaction.isEmpty()) {
            return Settlement.NONE;
        }
        String base = baseCommit(transaction.get());
        GitDocumentMaterializer.StoredDocument committed = GitDocumentMaterializer.load(anchor, false).orElseThrow();
        if (committed.commit().equals(base)) {
            return Settlement.NONE;
        }
        GitDocumentMaterializer.StoredDocument pending = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        long baseTime = baseTime(anchorNid, base);
        List<Long> edited = editedBlocks(pending);
        if (baseTime != Long.MIN_VALUE && !edited.isEmpty() && edited.stream().noneMatch(b -> touchedSince(b, baseTime))) {
            return rebase(anchor, transaction.get(), pending, edited);
        }
        List<String> committedLines = committed.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList();
        List<String> pendingLines = pending.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList();
        boolean[][] matched = matchInOrder(pendingLines, committedLines);
        List<String> notCarried = new ArrayList<>();
        for (int i = 0; i < pendingLines.size(); i++) {
            if (!matched[0][i] && pending.lines().get(i).latest().time() == Long.MAX_VALUE) {
                notCarried.add(pendingLines.get(i));
            }
        }
        List<String> committedOnly = new ArrayList<>();
        for (int j = 0; j < committedLines.size(); j++) {
            if (!matched[1][j]) {
                committedOnly.add(committedLines.get(j));
            }
        }
        keep(anchor, pending);
        transaction.get().cancel();
        return new Settlement(true, false, committedLines.equals(pendingLines), List.copyOf(notCarried), List.copyOf(committedOnly));
    }

    /** How a kept edit's block is found in the document as now committed. */
    public enum Match {
        /** The block it edited still exists under its identity; it is restored there without asking. */
        EXACT,
        /** The block's identity is gone and exactly one block of its kind lies where it was: the person confirms it. */
        CONFIRM,
        /** The block's identity is gone and more than one block, or none, could be it: the person picks one or cancels. */
        CHOOSE
    }

    /**
     * An edit that was cancelled, by settling against a commit that did not carry all of it or by
     * discarding it, and can be brought back as pending.
     *
     * @param index      the edit's place among the document's kept edits, which {@link #restore(PublicId, Map)} takes
     * @param match      how its block was found
     * @param target     the block it would be restored over: the block itself when {@link Match#EXACT}, the one
     *                   candidate when {@link Match#CONFIRM}, 0 when {@link Match#CHOOSE}
     * @param candidates the blocks it could be restored over, for the person to pick from when the block's identity
     *                   is gone: those of its kind between the lines it sat between, or every block of its kind when
     *                   none lies there
     * @param lines      the edit's lines, the lines it left alone as now committed where they still are
     * @param author     the edit's author
     * @param path       the edit's path
     */
    public record Restorable(int index, Match match, long target, List<Long> candidates, List<Kept> lines, long author, long path) {

        /** @return the block's text with the edit restored over it, the untouched lines taken from {@code over} */
        public String text(GitDocumentMaterializer.StoredDocument over) {
            List<String> text = new ArrayList<>();
            for (Kept line : lines) {
                if (line.edited()) {
                    text.add(line.text());
                } else {
                    line.nid().stream().mapToObj(over::line).flatMap(Optional::stream)
                            .map(GitDocumentMaterializer.StoredLine::text).forEach(text::add);
                }
            }
            return String.join("\n", text);
        }
    }

    /**
     * One line of a kept edit.
     *
     * @param nid    the line's nid, when the store still holds it
     * @param edited whether the edit wrote it
     * @param text   the edited text, when it did
     */
    public record Kept(OptionalLong nid, boolean edited, String text) { }

    /** Where cancelled edits are kept, one node per document, in the knowledge base's configuration preferences. */
    private static final String RESTORABLE = "network/ike/komet/claude/restorable-edits";

    /**
     * The cancelled edits that could be brought back. A cancelled version is withdrawn from its
     * record when the record is read, so what an edit wrote is kept when it is cancelled, in the
     * knowledge base's configuration preferences, which survive a restart but stay with that store:
     * for each edited block, its identity and kind, the unedited lines it sat between, and its lines
     * in order, with whether the edit wrote each and, if so, its text. Restored, each line the edit
     * wrote comes from the edit and each line it left alone from the document as now committed, so
     * what the commit changed there stays.
     *
     * <p>Where the block went is explicit. When its identity still exists it is the block
     * ({@link Match#EXACT}). When a commit rewrote the line it took its identity from, the block is
     * new, and only the person can say which it is: exactly one block of its kind between the lines
     * it sat between is offered for confirmation ({@link Match#CONFIRM}); several, or none, are
     * offered to choose from ({@link Match#CHOOSE}). An edit whose restored text equals its block's
     * committed text is not listed.
     *
     * @param anchor the document anchor's id
     * @return the restorable edits, in document order
     */
    public static List<Restorable> restorable(PublicId anchor) {
        Optional<GitDocumentMaterializer.StoredDocument> loaded = GitDocumentMaterializer.load(anchor, false);
        if (loaded.isEmpty()) {
            return List.of();
        }
        GitDocumentMaterializer.StoredDocument committed = loaded.get();
        KometPreferences kept = keptEdits(anchor);
        List<Long> order = committed.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList();
        List<Restorable> found = new ArrayList<>();
        int blocks = Integer.parseInt(kept.get("blocks", "0"));
        for (int k = 0; k < blocks; k++) {
            String b = "b." + k + ".";
            if (kept.get(b + "id", "").isEmpty()) {
                continue;                                        // restored or cancelled away
            }
            List<Kept> lines = new ArrayList<>();
            int members = Integer.parseInt(kept.get(b + "size", "0"));
            for (int i = 0; i < members; i++) {
                String m = b + "m." + i + ".";
                lines.add(new Kept(nidOf(kept.get(m + "id", "")), Boolean.parseBoolean(kept.get(m + "edited", "false")),
                        kept.get(m + "text", "")));
            }
            OptionalLong author = nidOf(kept.get(b + "author", ""));
            OptionalLong path = nidOf(kept.get(b + "path", ""));
            if (author.isEmpty() || path.isEmpty()) {
                continue;
            }
            String kind = kept.get(b + "kind", "");
            OptionalLong blockNid = nidOf(kept.get(b + "id", ""));
            Restorable restorable;
            if (blockNid.isPresent() && committed.blocks().containsKey(blockNid.getAsLong())) {
                restorable = new Restorable(k, Match.EXACT, blockNid.getAsLong(), List.of(blockNid.getAsLong()), List.copyOf(lines),
                        author.getAsLong(), path.getAsLong());
            } else {
                List<Long> between = between(committed, kind, nidOf(kept.get(b + "after", "")), nidOf(kept.get(b + "before", "")));
                if (between.size() == 1) {
                    restorable = new Restorable(k, Match.CONFIRM, between.getFirst(), between, List.copyOf(lines),
                            author.getAsLong(), path.getAsLong());
                } else {
                    List<Long> candidates = between.isEmpty() ? ofKind(committed, kind) : between;
                    restorable = new Restorable(k, Match.CHOOSE, 0, candidates, List.copyOf(lines), author.getAsLong(), path.getAsLong());
                }
            }
            if (restorable.match() != Match.CHOOSE) {
                GitDocumentMaterializer.StoredBlock block = committed.blocks().get(restorable.target());
                List<String> current = block.members().stream()
                        .map(nid -> committed.line(nid).map(GitDocumentMaterializer.StoredLine::text).orElse(null))
                        .filter(java.util.Objects::nonNull).toList();
                if (String.join("\n", current).equals(restorable.text(committed))) {
                    continue;                                    // the commit carried it
                }
            }
            found.add(restorable);
        }
        found.sort(java.util.Comparator.comparingInt(r -> r.candidates().isEmpty() ? Integer.MAX_VALUE
                : order.indexOf(committed.blocks().get(r.candidates().getFirst()).members().getFirst())));
        return List.copyOf(found);
    }

    /**
     * Brings back the kept edits whose block still exists ({@link Match#EXACT}); the others wait for
     * the person to confirm or pick their block, through {@link #restore(PublicId, Map)}.
     *
     * @param anchor the document anchor's id
     * @return how many blocks were restored
     */
    public static synchronized int restore(PublicId anchor) {
        Map<Integer, Long> exact = new java.util.LinkedHashMap<>();
        for (Restorable r : restorable(anchor)) {
            if (r.match() == Match.EXACT) {
                exact.put(r.index(), r.target());
            }
        }
        return restore(anchor, exact);
    }

    /**
     * Brings kept edits back as pending over the blocks the person confirmed or picked: each edit's
     * restored text is written over its block as now committed, in a pending transaction based on the
     * document's latest commit, under the edit's own author and path. Where the commit changed a line
     * the edit also changed, the edit's text stands over it as pending, for the person to resolve
     * before committing. The edits restored are no longer kept; the rest stay restorable.
     *
     * @param anchor  the document anchor's id
     * @param targets the block to restore each edit over, by {@link Restorable#index()}; an edit not
     *                named stays kept
     * @return how many blocks were restored
     * @throws IllegalArgumentException when a target is not one of the edit's candidates
     */
    public static synchronized int restore(PublicId anchor, Map<Integer, Long> targets) {
        GitDocumentMaterializer.StoredDocument committed = GitDocumentMaterializer.load(anchor, false).orElseThrow();
        int restored = 0;
        for (Restorable r : restorable(anchor)) {
            Long target = targets.get(r.index());
            if (target == null) {
                continue;
            }
            if (!r.candidates().contains(target)) {
                throw new IllegalArgumentException("Block " + target + " is not one this edit can be restored over");
            }
            editBlock(anchor, target, r.text(committed), r.author(), r.path());
            forget(anchor, r.index());
            restored++;
        }
        return restored;
    }

    /** Keeps what the pending edit wrote, block by block, before its transaction is cancelled. */
    private static void keep(PublicId anchor, GitDocumentMaterializer.StoredDocument pending) {
        List<Long> edited = editedBlocks(pending);
        SemanticEntityVersion manifest = pendingManifestVersion(anchor);
        KometPreferences kept = keptEdits(anchor);
        clear(kept, anchor);
        if (manifest == null) {
            return;
        }
        List<GitDocumentMaterializer.StoredLine> order = pending.lines();
        kept.put("blocks", Integer.toString(edited.size()));
        for (int k = 0; k < edited.size(); k++) {
            String b = "b." + k + ".";
            List<Long> members = pending.blocks().get(edited.get(k)).members();
            kept.put(b + "id", uuidOf(edited.get(k)));
            kept.put(b + "kind", pending.blocks().get(edited.get(k)).kind().name());
            kept.put(b + "author", uuidOf(manifest.stamp().authorNid()));
            kept.put(b + "path", uuidOf(manifest.stamp().pathNid()));
            kept.put(b + "size", Integer.toString(members.size()));
            int first = indexOf(order, members.getFirst());
            int last = indexOf(order, members.getLast());
            for (int i = first - 1; i >= 0; i--) {
                if (order.get(i).latest().time() != Long.MAX_VALUE) {
                    kept.put(b + "after", uuidOf(order.get(i).nid()));
                    break;
                }
            }
            for (int i = last + 1; i < order.size(); i++) {
                if (order.get(i).latest().time() != Long.MAX_VALUE) {
                    kept.put(b + "before", uuidOf(order.get(i).nid()));
                    break;
                }
            }
            for (int i = 0; i < members.size(); i++) {
                String m = b + "m." + i + ".";
                Optional<GitDocumentMaterializer.StoredLine> line = pending.line(members.get(i));
                boolean written = line.map(l -> l.latest().time() == Long.MAX_VALUE).orElse(false);
                kept.put(m + "id", uuidOf(members.get(i)));
                kept.put(m + "edited", Boolean.toString(written));
                if (written) {
                    kept.put(m + "text", line.get().text());
                }
            }
        }
        flush(kept);
    }

    private static int indexOf(List<GitDocumentMaterializer.StoredLine> lines, long nid) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).nid() == nid) {
                return i;
            }
        }
        return -1;
    }

    /** The blocks of a kind that start strictly between two lines of the committed document. */
    private static List<Long> between(GitDocumentMaterializer.StoredDocument committed, String kind, OptionalLong after, OptionalLong before) {
        List<Long> order = committed.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList();
        int from = after.isPresent() ? order.indexOf(after.getAsLong()) : -1;
        int to = before.isPresent() ? order.indexOf(before.getAsLong()) : order.size();
        if ((after.isPresent() && from < 0) || (before.isPresent() && to < 0)) {
            return List.of();                                    // a line it sat between is gone too
        }
        List<Long> found = new ArrayList<>();
        for (GitDocumentMaterializer.StoredBlock block : committed.blocks().values()) {
            if (block.kind().name().equals(kind) && !block.members().isEmpty()) {
                int at = order.indexOf(block.members().getFirst());
                if (at > from && at < to) {
                    found.add(block.nid());
                }
            }
        }
        found.sort(java.util.Comparator.comparingInt(n -> order.indexOf(committed.blocks().get(n).members().getFirst())));
        return found;
    }

    /** Every block of a kind, in document order. */
    private static List<Long> ofKind(GitDocumentMaterializer.StoredDocument committed, String kind) {
        return between(committed, kind, OptionalLong.empty(), OptionalLong.empty());
    }

    /** Drops one kept edit, leaving the others restorable. */
    private static void forget(PublicId anchor, int index) {
        KometPreferences kept = keptEdits(anchor);
        kept.put("b." + index + ".id", "");
        flush(kept);
    }

    private static void clear(KometPreferences kept, PublicId anchor) {
        try {
            kept.clear();
        } catch (java.util.prefs.BackingStoreException e) {
            LOG.warn("Could not clear the kept edit of {}", anchor.idString(), e);
        }
    }

    private static KometPreferences keptEdits(PublicId anchor) {
        return KometPreferencesImpl.getConfigurationRootPreferences().node(RESTORABLE + "/" + anchor.asUuidArray()[0]);
    }

    private static void flush(KometPreferences preferences) {
        try {
            preferences.flush();
        } catch (java.util.prefs.BackingStoreException e) {
            LOG.warn("Could not save the kept edit", e);
        }
    }

    private static String uuidOf(long nid) {
        return EntityHandle.get(nid).orNull() instanceof dev.ikm.tinkar.entity.Entity<?> entity
                ? entity.publicId().asUuidArray()[0].toString() : "";
    }

    private static OptionalLong nidOf(String uuid) {
        if (uuid.isEmpty()) {
            return OptionalLong.empty();
        }
        PublicId id = PublicIds.of(java.util.UUID.fromString(uuid));
        return PrimitiveData.get().hasPublicId(id) ? OptionalLong.of(PrimitiveData.nid(id)) : OptionalLong.empty();
    }

    private static SemanticEntityVersion pendingManifestVersion(PublicId anchor) {
        SemanticEntityVersion pendingManifest = null;
        for (SemanticEntityVersion version : semantic(manifestNid(anchor)).versions()) {
            if (version.uncommitted() && !version.canceled()) {
                pendingManifest = version;
            }
        }
        return pendingManifest;
    }

    /**
     * Carries the pending edit forward onto the newest commit: each edited block's pending text is
     * written again over the block as now committed, in a new pending transaction based on that
     * commit, under the edit's own author and path; the old transaction is cancelled.
     */
    private static Settlement rebase(PublicId anchor, Transaction old, GitDocumentMaterializer.StoredDocument pending, List<Long> edited) {
        List<String> texts = new ArrayList<>();
        for (long block : edited) {
            texts.add(pending.blocks().get(block).members().stream()
                    .map(nid -> pending.line(nid).map(GitDocumentMaterializer.StoredLine::text).orElse(""))
                    .collect(java.util.stream.Collectors.joining("\n")));
        }
        SemanticEntityVersion pendingManifest = pendingManifestVersion(anchor);
        long author = pendingManifest.stamp().authorNid();
        long path = pendingManifest.stamp().pathNid();
        old.cancel();
        for (int i = 0; i < edited.size(); i++) {
            editBlock(anchor, edited.get(i), texts.get(i), author, path);
        }
        return Settlement.REBASED;
    }

    /** The blocks with a pending version, in document order. */
    private static List<Long> editedBlocks(GitDocumentMaterializer.StoredDocument pending) {
        List<Long> edited = new ArrayList<>();
        for (GitDocumentMaterializer.StoredBlock block : pending.blocks().values()) {
            if (block.stamp().time() == Long.MAX_VALUE) {
                edited.add(block.nid());
            }
        }
        List<Long> order = pending.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList();
        edited.sort(java.util.Comparator.comparingInt(b -> order.indexOf(pending.blocks().get(b).members().getFirst())));
        return edited;
    }

    /**
     * Whether a commit since the base touched a block: the block, or a line it held at the base,
     * has a committed version newer than the base.
     */
    private static boolean touchedSince(long block, long baseTime) {
        SemanticEntityVersion atBase = null;
        for (SemanticEntityVersion version : semantic(block).versions()) {
            if (version.canceled() || version.time() == Long.MAX_VALUE) {
                continue;
            }
            if (version.time() > baseTime) {
                return true;
            }
            if (atBase == null || version.time() > atBase.time()) {
                atBase = version;
            }
        }
        if (atBase == null) {
            return true;
        }
        for (long line : ((dev.ikm.tinkar.common.id.LongIdList) atBase.fieldValues().get(4)).toArray()) {
            for (SemanticEntityVersion version : semantic(line).versions()) {
                if (!version.canceled() && version.time() != Long.MAX_VALUE && version.time() > baseTime) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The time of the commit an edit was made against, from the git-source version recording it. */
    private static long baseTime(long anchorNid, String base) {
        for (SemanticEntity<SemanticEntityVersion> source : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.GIT_SOURCE_PATTERN.nid()).toList()) {
            for (SemanticEntityVersion version : source.versions()) {
                if (!version.canceled() && version.time() != Long.MAX_VALUE && version.fieldValues().size() > 1
                        && base.equals(String.valueOf(version.fieldValues().get(1)))) {
                    return version.time();
                }
            }
        }
        return Long.MIN_VALUE;
    }

    /**
     * Which lines of each list the longest common subsequence pairs, in order.
     *
     * @return {@code [0]} for the first list, {@code [1]} for the second
     */
    static boolean[][] matchInOrder(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        boolean[][] matched = {new boolean[n], new boolean[m]};
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                matched[0][i++] = true;
                matched[1][j++] = true;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return matched;
    }

    private static final String BASE = " on commit ";

    /** The commit an edit was made against, carried in its transaction's name, which is saved with it. */
    private static String baseCommit(Transaction transaction) {
        String name = transaction.name() == null ? "" : transaction.name();
        int at = name.lastIndexOf(BASE);
        return at < 0 ? "" : name.substring(at + BASE.length());
    }

    private static long manifestNid(PublicId anchor) {
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        return EntityService.get().semanticsForComponentOfPattern(anchorNid, IkePattern.JOURNAL_MANIFEST_PATTERN.nid())
                .findFirst().map(SemanticEntity::nid).orElse(0L);
    }

    @SuppressWarnings("unchecked")
    private static SemanticEntity<SemanticEntityVersion> semantic(long nid) {
        return (SemanticEntity<SemanticEntityVersion>) EntityHandle.get(nid).expectSemantic();
    }

    private static void writeVersion(Transaction transaction, long nid, StampEntity<?> stamp, ImmutableList<Object> values) {
        SemanticRecord record = (SemanticRecord) EntityHandle.get(nid).orNull();
        SemanticRecord updated = record.with(new SemanticVersionRecord(record, stamp.nid(), values)).build();
        transaction.addComponent(updated);
        EntityService.get().putEntity(updated);
    }

    private static long writeNew(Transaction transaction, PublicId id, PublicId pattern, long referencedNid,
                                 StampEntity<?> stamp, ImmutableList<Object> values) {
        long nid = EntityService.get().nidForSemantic(pattern, id);
        PublicIdentifierRecord identifier = PublicIdentifierRecord.make(id);
        RecordListBuilder<SemanticVersionRecord> versions = RecordListBuilder.make();
        SemanticRecord semantic = SemanticRecordBuilder.builder()
                .nid(nid)
                .mostSignificantBits(identifier.mostSignificantBits())
                .leastSignificantBits(identifier.leastSignificantBits())
                .additionalUuidLongs(identifier.additionalUuidLongs())
                .patternNid(EntityService.get().nidForPattern(pattern))
                .referencedComponentNid(referencedNid)
                .versions(versions)
                .build();
        versions.add(SemanticVersionRecordBuilder.builder().chronology(semantic).stampNid(stamp.nid()).fieldValues(values).build());
        SemanticRecord built = SemanticRecordBuilder.builder(semantic).versions(versions.toImmutable()).build();
        transaction.addComponent(built);
        EntityService.get().putEntity(built);
        return nid;
    }

    // ── line matching ──────────────────────────────────────────────────────────

    private enum Kind { KEEP, RETEXT, INSERT, REMOVE }

    private record Step(Kind kind, int oldIndex, int newIndex) { }

    /**
     * Old to new lines: the longest common subsequence of equal lines is kept; between two kept
     * lines, old and new lines pair off as re-texts in order, and what is left over is inserted
     * or removed.
     */
    static List<Step> diff(List<String> oldLines, List<String> newLines) {
        int n = oldLines.size();
        int m = newLines.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = oldLines.get(i).equals(newLines.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Step> steps = new ArrayList<>();
        int i = 0;
        int j = 0;
        List<Integer> gapOld = new ArrayList<>();
        List<Integer> gapNew = new ArrayList<>();
        while (i < n || j < m) {
            if (i < n && j < m && oldLines.get(i).equals(newLines.get(j))) {
                flush(gapOld, gapNew, steps);
                steps.add(new Step(Kind.KEEP, i++, j++));
            } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                gapNew.add(j++);
            } else {
                gapOld.add(i++);
            }
        }
        flush(gapOld, gapNew, steps);
        return steps;
    }

    private static void flush(List<Integer> gapOld, List<Integer> gapNew, List<Step> steps) {
        int pairs = Math.min(gapOld.size(), gapNew.size());
        for (int k = 0; k < pairs; k++) {
            steps.add(new Step(Kind.RETEXT, gapOld.get(k), gapNew.get(k)));
        }
        for (int k = pairs; k < gapOld.size(); k++) {
            steps.add(new Step(Kind.REMOVE, gapOld.get(k), -1));
        }
        for (int k = pairs; k < gapNew.size(); k++) {
            steps.add(new Step(Kind.INSERT, -1, gapNew.get(k)));
        }
        gapOld.clear();
        gapNew.clear();
    }

}
