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

import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import design.knowledge.stamp.adoc.dialect.Segmenter;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.KonceptRef;
import design.knowledge.stamp.adoc.model.LineRange;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.element.Span;
import network.ike.komet.claude.anf.element.SegmentNarrative;

/**
 * A stamped document's prose as the narrative a lift reads ({@code dev-anf-element-isolation}, "The
 * written form"): the sentences the store holds for each of its blocks, in document order, each
 * named by its sentence vertex's identifier, so the lift's spans anchor to sentence segments and
 * survive an edit elsewhere in the note. Only a document stored as vertices has sentences
 * (IKE-Network/ike-issues#1313); the version read is the one pending in the store, so the lift
 * sees what the document card shows.
 */
public final class DocumentNarratives {

    /** A stored document a lift may read: its recorded path and its anchor. */
    public record Choice(String path, PublicId anchor) {
    }

    /**
     * Not-clinical marks written as annotations on a document ({@code dev-anf-element-isolation};
     * the segments design's decision 9, revised 2026-10-31): where they will be written, and the
     * annotations, one per mark, each the fragments of the sentences it covers.
     *
     * @param root        the working tree
     * @param file        the document, relative to it
     * @param head        the commit the fragments were read at
     * @param annotations the annotations
     */
    public record Recording(Path root, Path file, String head, List<Annotation> annotations) {

        /** @return the trailers, one per line, as the commit will carry them */
        public String trailers() {
            return String.join("\n", annotations.stream().map(Annotation::trailer).toList());
        }
    }

    /** Working trees the document card has opened this session: where a stored document's file is found. */
    private static final Set<Path> WORKING_TREES = ConcurrentHashMap.newKeySet();

    private DocumentNarratives() {
    }

    /** Notes a working tree the document card has opened, so a lift can find a stored document's file. */
    public static void workingTreeOpened(Path root) {
        if (root != null) {
            WORKING_TREES.add(root.toAbsolutePath().normalize());
        }
    }

    /**
     * The working tree holding a stored document: one the document card has opened whose file of
     * the recorded path is the document, by the identity the store minted for it.
     *
     * @param anchor the document anchor
     * @return the working tree; empty when no opened tree holds it
     */
    public static Optional<Path> workingTree(PublicId anchor) {
        Optional<String> path = GitDocumentMaterializer.recordedPath(anchor);
        if (path.isEmpty()) {
            return Optional.empty();
        }
        for (Path root : WORKING_TREES) {
            if (Files.isRegularFile(root.resolve(path.get())) && GitDocumentMaterializer.documentAnchor(root, path.get()).equals(anchor)) {
                return Optional.of(root);
            }
        }
        return Optional.empty();
    }

    /**
     * The narrative spans the document's annotations mark not clinical: each annotation in force
     * whose meaning is Not clinical, on the parts of the sentences it covers.
     *
     * @param anchor    the document anchor
     * @param narrative the document's narrative
     * @return the spans, in narrative order
     */
    public static List<Span> recordedNotClinical(PublicId anchor, SegmentNarrative narrative) {
        Optional<GitDocumentMaterializer.StoredDocument> loaded = GitDocumentMaterializer.load(anchor, true);
        if (loaded.isEmpty()) {
            return List.of();
        }
        long notClinical = IkeConcept.NOT_CLINICAL.nid();
        List<Span> spans = new ArrayList<>();
        for (List<GitDocumentMaterializer.StoredSentence> block : loaded.get().sentences().values()) {
            for (GitDocumentMaterializer.StoredSentence sentence : block) {
                String id = sentenceId(sentence.nid());
                for (GitDocumentMaterializer.StoredAnnotation a : sentence.annotations()) {
                    if (a.meaningNid() != notClinical || a.retracted()) {
                        continue;
                    }
                    if (a.whole()) {
                        narrative.span(id, 0, sentence.text().length()).ifPresent(spans::add);
                    }
                    for (GitDocumentMaterializer.TextRange r : a.ranges()) {
                        narrative.span(id, r.start(), r.end()).ifPresent(spans::add);
                    }
                }
            }
        }
        // the parts of one annotation in neighbouring sentences read back as one span, as they were marked
        spans.sort(Span::compareTo);
        List<Span> joined = new ArrayList<>();
        for (Span span : spans) {
            Span last = joined.isEmpty() ? null : joined.getLast();
            if (last != null && span.start() >= last.end() && narrative.narrative().substring(last.end(), span.start()).isBlank()) {
                joined.set(joined.size() - 1, new Span(last.start(), span.end()));
            } else if (last == null || !last.equals(span)) {
                joined.add(span);
            }
        }
        return joined;
    }

    /**
     * Prepares not-clinical marks as annotations of the document's committed text: one annotation
     * per mark, its fragments the parts of the sentences the mark covers, found in the file as
     * committed at HEAD.
     *
     * @param anchor    the document anchor
     * @param root      the working tree holding it
     * @param narrative the document's narrative
     * @param marks     the marks, as narrative spans
     * @return the recording
     * @throws IOException           when git cannot be read
     * @throws IllegalStateException when a marked sentence is not in the committed text
     */
    public static Recording notClinical(PublicId anchor, Path root, SegmentNarrative narrative, List<Span> marks) throws IOException {
        String path = GitDocumentMaterializer.recordedPath(anchor)
                .orElseThrow(() -> new IllegalStateException("the store records no path for the document"));
        Path file = Path.of(path);
        String head;
        List<String> committed;
        try (GitRepo repo = GitRepo.open(root)) {
            head = repo.headRevision();
            committed = repo.linesAt(head, file);
        }
        // each narrative sentence's counterpart in the committed file, matched by text in document order
        List<Segmenter.Sentence> found = Segmenter.of(IkeDialectParser.parse(committed)).sentences();
        Map<String, Segmenter.Sentence> counterpart = new java.util.HashMap<>();
        int cursor = 0;
        for (SegmentNarrative.Sentence sentence : narrative.sentences()) {
            for (int i = cursor; i < found.size(); i++) {
                if (found.get(i).text().equals(sentence.text())) {
                    counterpart.put(sentence.id(), found.get(i));
                    cursor = i + 1;
                    break;
                }
            }
        }
        KonceptRef meaning = new KonceptRef(IkeConcept.NOT_CLINICAL.publicId().asUuidArray()[0], "Not clinical");
        List<Annotation> annotations = new ArrayList<>();
        for (Span mark : marks) {
            List<LineRange.Fragment> fragments = new ArrayList<>();
            for (SegmentNarrative.Piece piece : narrative.pieces(mark)) {
                Segmenter.Sentence sentence = counterpart.get(piece.sentence().id());
                if (sentence == null) {
                    throw new IllegalStateException("\"" + piece.sentence().text() + "\" is not in the committed text of " + path
                            + "; commit the document first.");
                }
                fragments.add(sentence.fragment(piece.from(), piece.to()));
            }
            if (!fragments.isEmpty()) {
                annotations.add(new Annotation(meaning, LineRange.of(file, fragments, head, committed), null, null, null));
            }
        }
        return new Recording(root, file, head, annotations);
    }

    /** The documents stored as vertices, by recorded path. */
    public static List<Choice> documents() {
        List<Choice> choices = new ArrayList<>();
        GitDocumentMaterializer.storedDocumentsByPath().forEach((path, anchors) -> {
            for (PublicId anchor : anchors) {
                if (VertexReader.isVertexDocument(anchor)) {
                    choices.add(new Choice(path, anchor));
                }
            }
        });
        return choices;
    }

    /** The document's narrative: each block's sentences a paragraph, in document order; empty when it has none. */
    public static Optional<SegmentNarrative> narrative(PublicId anchor) {
        Optional<GitDocumentMaterializer.StoredDocument> loaded = GitDocumentMaterializer.load(anchor, true);
        if (loaded.isEmpty() || loaded.get().root() == null || loaded.get().sentences().isEmpty()) {
            return Optional.empty();
        }
        GitDocumentMaterializer.StoredDocument document = loaded.get();
        List<List<SegmentNarrative.Sentence>> paragraphs = new ArrayList<>();
        walk(document.root(), document.blocks(), document.sentences(), paragraphs, new HashSet<>());
        SegmentNarrative narrative = SegmentNarrative.of(paragraphs);
        return narrative.sentences().isEmpty() ? Optional.empty() : Optional.of(narrative);
    }

    /** The document a sentence belongs to, by the sentence vertex's identifier; empty when the store lacks it. */
    public static Optional<PublicId> documentOf(String sentenceId) {
        PublicId id;
        try {
            id = PublicIds.of(UUID.fromString(sentenceId));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
        if (!PrimitiveData.get().hasPublicId(id)) {
            return Optional.empty();
        }
        Entity<?> entity = EntityHandle.get(PrimitiveData.nid(id)).orNull();
        if (entity instanceof SemanticEntity<?> semantic) {
            return Optional.of(PrimitiveData.publicId(semantic.referencedComponentNid()));
        }
        return Optional.empty();
    }

    private static void walk(GitDocumentMaterializer.StoredBlock block, Map<Long, GitDocumentMaterializer.StoredBlock> blocks,
                             Map<Long, List<GitDocumentMaterializer.StoredSentence>> sentences,
                             List<List<SegmentNarrative.Sentence>> paragraphs, Set<Long> seen) {
        if (!seen.add(block.nid())) {
            return;
        }
        List<GitDocumentMaterializer.StoredSentence> own = sentences.getOrDefault(block.nid(), List.of());
        if (!own.isEmpty()) {
            paragraphs.add(own.stream()
                    .map(s -> new SegmentNarrative.Sentence(sentenceId(s.nid()), s.text()))
                    .toList());
        }
        for (long member : block.members()) {
            GitDocumentMaterializer.StoredBlock child = blocks.get(member);
            if (child != null) {
                walk(child, blocks, sentences, paragraphs, seen);
            }
        }
    }

    /** A sentence vertex's identifier: the UUID the store derives from its segment key. */
    static String sentenceId(long nid) {
        return PrimitiveData.publicId(nid).asUuidArray()[0].toString();
    }
}
