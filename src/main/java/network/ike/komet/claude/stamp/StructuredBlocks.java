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

import design.knowledge.stamp.adoc.dialect.Block;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntity;
import network.ike.komet.claude.block.LocalConcepts;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The structured block kinds the surface knows, by style (IKE-Network/ike-issues#1312): the kinds
 * the module path provides, plus any a host or a test registers. A block is structured when it is
 * a listing block whose style a registered kind names; any other block renders and edits as it
 * always has.
 */
public final class StructuredBlocks {

    private static final Map<String, StructuredBlockKind> REGISTERED = new ConcurrentHashMap<>();
    private static volatile List<StructuredBlockKind> provided;

    private StructuredBlocks() {
    }

    /**
     * Registers a kind beside the provided ones; a registered kind wins over a provided kind of
     * the same style.
     *
     * @param kind the kind
     */
    public static void register(StructuredBlockKind kind) {
        REGISTERED.put(key(kind.style()), kind);
    }

    /**
     * Removes a registered kind.
     *
     * @param style the kind's style
     */
    public static void unregister(String style) {
        REGISTERED.remove(key(style));
    }

    private static String key(String style) {
        return style.strip().toLowerCase(Locale.ROOT);
    }

    private static List<StructuredBlockKind> provided() {
        List<StructuredBlockKind> kinds = provided;
        if (kinds == null) {
            List<StructuredBlockKind> loaded = new ArrayList<>();
            ServiceLoader.load(StructuredBlockKind.class, StructuredBlocks.class.getClassLoader())
                    .stream().forEach(provider -> loaded.add(provider.get()));
            kinds = List.copyOf(loaded);
            provided = kinds;
        }
        return kinds;
    }

    /**
     * The style of a block's attribute line: its first positional attribute, without a leading
     * role or id shorthand. {@code [anf,type=performance]} has style {@code anf};
     * {@code [source,java]} has style {@code source}.
     *
     * @param parameters the block's attribute line, verbatim
     * @return the style, or empty when the line names none
     */
    static Optional<String> style(String parameters) {
        if (parameters == null) {
            return Optional.empty();
        }
        String line = parameters.strip();
        if (!line.startsWith("[") || !line.endsWith("]")) {
            return Optional.empty();
        }
        String first = line.substring(1, line.length() - 1).split(",", 2)[0].strip();
        int shorthand = first.indexOf('.') >= 0 ? first.indexOf('.') : -1;
        int id = first.indexOf('#');
        int cut = shorthand < 0 ? id : id < 0 ? shorthand : Math.min(shorthand, id);
        if (cut >= 0) {
            first = first.substring(0, cut);
        }
        if (first.isEmpty() || first.contains("=") || first.startsWith("\"")) {
            return Optional.empty();
        }
        return Optional.of(first);
    }

    /**
     * The kind of a block, when it is structured: a listing block whose style a kind names.
     *
     * @param block the block
     * @return the kind
     */
    public static Optional<StructuredBlockKind> kindOf(StoredBlock block) {
        // a listing, ----, or an open block, --, the form the ANF blocks of the lab documents take
        if (block == null || (block.kind() != Block.Kind.LISTING && block.kind() != Block.Kind.OPEN)) {
            return Optional.empty();
        }
        return style(block.parameters()).flatMap(StructuredBlocks::kindFor);
    }

    /**
     * The kind a style names.
     *
     * @param style a block style
     * @return the kind
     */
    public static Optional<StructuredBlockKind> kindFor(String style) {
        String key = key(style);
        StructuredBlockKind registered = REGISTERED.get(key);
        if (registered != null) {
            return Optional.of(registered);
        }
        return provided().stream().filter(kind -> key(kind.style()).equals(key)).findFirst();
    }

    /**
     * A structured block's source: its attribute line and its lines in document order. The
     * attribute line is read from the lines when they hold one, since an edit of the block's text
     * changes the lines, a write back's health price among them, and not the parameters stored
     * when the block was materialized.
     *
     * @param document the document holding the block
     * @param block    the block
     * @return the source
     */
    public static StructuredBlockKind.Source source(StoredDocument document, StoredBlock block) {
        List<String> lines = lines(document, block).stream().map(StoredLine::text).toList();
        String parameters = StructuredBlockKind.Source.of(String.join("\n", lines)).parameters();
        return new StructuredBlockKind.Source(parameters.isEmpty() ? block.parameters() : parameters, lines);
    }

    /**
     * How a block stands to its document, for what it writes: whether the document is paired, and
     * what its blocks call their concepts, since a symbol ties a tree's label to the complex concept
     * that defines it.
     *
     * @param block    the nid of the block's element semantic
     * @param previous the block's text before this edit, empty for a new block
     * @return the context
     */
    public static LocalConcepts.Context context(long block, Optional<String> previous) {
        if (!(EntityHandle.get(block).orNull() instanceof SemanticEntity<?> element)) {
            return new LocalConcepts.Context(false, LocalConcepts.Names.NONE, previous);
        }
        PublicId anchor = PrimitiveData.publicId(element.referencedComponentNid());
        return new LocalConcepts.Context(DocumentPairing.paired(anchor), GitDocumentMaterializer.load(anchor, true)
                .map(StructuredBlocks::names).orElse(LocalConcepts.Names.NONE), previous);
    }

    /**
     * @param document a document
     * @return what its structured blocks call their concepts
     */
    public static LocalConcepts.Names names(StoredDocument document) {
        List<String> blocks = new ArrayList<>();
        for (StoredBlock block : document.blocks().values()) {
            if (kindOf(block).isPresent()) {
                blocks.add(source(document, block).content());
            }
        }
        return LocalConcepts.Names.of(blocks);
    }

    /**
     * Every line of a block in document order, its children's included, the members being in
     * document order: an open block's content is read as AsciiDoc, so its lines sit in child
     * blocks, while a listing holds its own.
     *
     * @param document the block's document
     * @param block    the block
     * @return the lines
     */
    public static List<StoredLine> lines(StoredDocument document, StoredBlock block) {
        List<StoredLine> lines = new ArrayList<>();
        for (long member : block.members()) {
            Optional<StoredLine> line = document.line(member);
            if (line.isPresent()) {
                lines.add(line.get());
            } else {
                StoredBlock child = document.blocks().get(member);
                if (child != null) {
                    lines.addAll(lines(document, child));
                }
            }
        }
        return lines;
    }
}
