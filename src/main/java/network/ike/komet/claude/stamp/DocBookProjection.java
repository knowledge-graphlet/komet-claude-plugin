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
import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.stamp.DocumentGraph.Kind;
import network.ike.komet.claude.stamp.DocumentGraph.Markup;
import network.ike.komet.claude.stamp.DocumentGraph.Node;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.InlineMarkup.Span;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The DocBook projection of a stored document (IKE-Network/ike-issues#1313): the vertex tree
 * read as a DocBook editor's structure view shows a document. Nothing in the store changes; it
 * is another rendering of the same blocks and lines.
 *
 * <ul>
 * <li>Blocks are elements, as the tree holds them.</li>
 * <li>Markup lines fold into their block: a heading or block title line becomes the block's
 * {@code Title} element, a block attribute line its attributes (style, language, role, id,
 * options and the rest by name), an anchor line its id; delimiters and blank lines are not
 * shown; the header's attribute entries are the document's attributes.</li>
 * <li>A paragraph's physical lines, a list item's text and a table cell's are joined into mixed
 * content: text runs interleaved with the inline elements {@link InlineMarkup} parses from the
 * text. Verbatim bodies are one text node.</li>
 * <li>A table's cells are read from its lines and grouped into rows by its column count.</li>
 * <li>A resolved include is the included document, in place; an unresolved one an
 * {@code Include} element.</li>
 * </ul>
 *
 * <p>No line is a node. Every node keeps the lines it came from in its {@link Markup}, and the
 * surface ordinals they span, so selecting a text run or an emphasis selects its lines on the
 * other views. What each line is comes from {@link LineRoles} over the member's text, the
 * classification the materializer stored as each line vertex's meaning.
 */
final class DocBookProjection {

    private static final Pattern UNORDERED = Pattern.compile("^\\s*(\\*+|-)\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*(\\.+|\\d+\\.)\\s+(.*)$");
    private static final Pattern DESCRIPTION = Pattern.compile("^(?!\\s)(?![a-z]+::)(.+?)(::+)(?:\\s+(.*))?$");
    private static final Pattern ADMONITION = Pattern.compile("^(NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s+");
    private static final Pattern BLOCK_MACRO = Pattern.compile("^([a-z]+)::([^\\[]*)\\[(.*)\\]$");
    private static final Pattern CELL = Pattern.compile("(?:^|(?<=\\s))((?:\\d+(?:\\.\\d+)?[+*])?[<^>]?(?:\\.[<^>])?[aehlmdsv]?)\\|");
    private static final Set<Block.Kind> VERBATIM = Set.of(Block.Kind.LISTING, Block.Kind.LITERAL, Block.Kind.DIAGRAM);
    /** The lines that say something of the block they precede: its attributes, its id, its title. */
    private static final Set<ConceptFacade> METADATA = Set.of(IkeConcept.BLOCK_ATTRIBUTE_LINE, IkeConcept.ANCHOR_LINE, IkeConcept.BLOCK_TITLE_LINE);
    /** How many characters of text a label shows. */
    private static final int LABEL_CHARS = 72;

    private final DocumentAssembly assembly;
    private final Map<DocumentAssembly.Member, Map<Long, LineRoles.Role>> roles = new IdentityHashMap<>();
    private final Map<DocumentAssembly.Member, Map<Long, StoredLine>> lines = new IdentityHashMap<>();

    private DocBookProjection(DocumentAssembly assembly) {
        this.assembly = assembly;
    }

    /**
     * The projection of an assembly.
     *
     * @param assembly the assembly
     * @return the root: the host's document element
     */
    static Node of(DocumentAssembly assembly) {
        return new DocBookProjection(assembly).document(assembly.host(), null, "");
    }

    // ── documents and blocks ──────────────────────────────────────────────────

    /** A member's document element; included by {@code include}, it spans that line too. */
    private Node document(DocumentAssembly.Member member, StoredLine include, String href) {
        StoredDocument document = member.document();
        String fallback = document.attributes().isEmpty() || document.attributes().getFirst().isBlank()
                ? (member.path().isEmpty() ? "Document" : member.fileName()) : document.attributes().getFirst();
        List<StoredLine> own = new ArrayList<>();
        if (include != null) {
            own.add(include);
        }
        Element element;
        if (document.root() == null) {
            element = new Element("Document");
            for (StoredLine line : document.lines()) {
                if (!line.text().isBlank()) {
                    element.children.add(text(member, List.of(line), line.text()));
                }
            }
        } else {
            element = content(member, document.root(), List.of(), Set.of());
            own.addAll(element.lines);
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        if (!href.isEmpty()) {
            attributes.put("href", href);
        }
        attributes.putAll(element.attributes);
        String title = element.title == null ? fallback : element.title.markup().text();
        List<Node> children = element.withTitle();
        String detail = member.path();
        if (document.root() == null) {
            detail = detail.isEmpty() ? "no structure in the store; re-materialize to add it" : detail + " — no structure in the store; re-materialize to add it";
        }
        int[] span = span(own, children);
        return new Node(Kind.DOCUMENT, document.root() == null ? 0 : document.root().nid(), title, detail, member, span[0], span[1],
                document.root() == null ? null : document.root().stamp(), document.root(), null, null, null, null, List.of(),
                new Markup("Document", frozen(attributes), title, List.copyOf(own)),
                List.copyOf(children));
    }

    /** What a block's members project to: its title, attributes, content, and the lines it folded. */
    private static final class Element {
        String name;
        Node title;
        final Map<String, String> attributes = new LinkedHashMap<>();
        final List<Node> children = new ArrayList<>();
        final List<StoredLine> lines = new ArrayList<>();

        Element(String name) {
            this.name = name;
        }

        List<Node> withTitle() {
            if (title == null) {
                return children;
            }
            List<Node> all = new ArrayList<>();
            all.add(title);
            all.addAll(children);
            return all;
        }
    }

    /**
     * A block's nodes: the block's element, the included document an include block is, or none
     * for a block with nothing to show. {@code leading} are metadata lines the block before it
     * held; {@code carried} the nids of its own trailing ones, which are the next block's.
     */
    private List<Node> block(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> leading, Set<Long> carried) {
        Element element = content(member, block, leading, carried);
        List<Node> children = element.withTitle();
        if (block.kind() == Block.Kind.OPAQUE && element.attributes.isEmpty() && element.title == null) {
            if (children.isEmpty()) {
                return List.of();                                // only markup: an endif, a stray continuation, metadata with nothing after it
            }
            if (children.size() == 1 && element.name.equals(DocumentGraph.kindName(Block.Kind.OPAQUE))) {
                return children;                                 // an include, a comment, a conditional: the element it holds
            }
        }
        int[] span = span(element.lines, children);
        String label = label(block, element);
        return List.of(new Node(Kind.BLOCK, block.nid(), label, block.parameters(), member, span[0], span[1], block.stamp(), block, null, null,
                null, null, List.of(), new Markup(element.name, frozen(element.attributes), text(element), List.copyOf(element.lines)),
                List.copyOf(children)));
    }

    /**
     * Folds a block's members: markup lines into its title and attributes, content lines into
     * mixed content, child blocks into elements, in the order the tree holds them.
     *
     * <p>The dialect keeps a metadata line written straight after a paragraph's text, with no
     * blank line between, inside the paragraph; it says something of the block after it. So a
     * child's trailing metadata lines are carried to the next child block: they fold into that
     * block, as {@code leading}, and the child leaves them out, as {@code carried}. With no block
     * after it, they stay where the dialect put them.
     */
    private Element content(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> leading, Set<Long> carried) {
        Element element = new Element(DocumentGraph.kindName(block.kind()));
        Flow flow = new Flow(member);
        List<StoredLine> comment = new ArrayList<>();
        List<StoredLine> verbatim = new ArrayList<>();
        boolean fenced = false;
        String continuing = null;                                // an attribute entry whose value goes on to the next line
        boolean admonitionLabel = block.kind() == Block.Kind.ADMONITION;
        List<StoredBlock> rows = new ArrayList<>();
        List<Long> members = new ArrayList<>();
        leading.forEach(line -> members.add(line.nid()));
        block.tree().stream().filter(nid -> !carried.contains(nid)).forEach(members::add);
        List<StoredLine> pending = List.of();                    // the previous child's trailing metadata, for the next child block
        for (int i = 0; i < members.size(); i++) {
            long nid = members.get(i);
            StoredLine line = line(member, nid);
            if (line == null) {
                StoredBlock child = member.document().blocks().get(nid);
                if (child == null) {
                    continue;
                }
                flow.flush(element.children);
                flushComment(member, comment, element.children);
                if (block.kind() == Block.Kind.TABLE && child.kind() == Block.Kind.TABLE_ROW) {
                    rows.add(child);
                } else {
                    List<StoredLine> trailing = blockFollows(member, members, i) ? trailingMetadata(member, child) : List.of();
                    Set<Long> out = new HashSet<>();
                    trailing.forEach(l -> out.add(l.nid()));
                    element.children.addAll(block(member, child, pending, out));
                    pending = trailing;
                }
                continue;
            }
            element.lines.add(line);
            LineRoles.Role role = role(member, line);
            ConceptFacade meaning = role.meaning();
            String text = line.text();
            if (continuing != null) {
                String value = element.attributes.get(continuing);
                element.attributes.put(continuing, joinContinued(value, text));
                continuing = text.endsWith("\\") ? continuing : null;
                continue;
            }
            if (meaning != IkeConcept.COMMENT_LINE) {
                flushComment(member, comment, element.children);
            }
            if (meaning == IkeConcept.BLANK_LINE) {
                if (!verbatim.isEmpty() || fenced) {
                    continue;                                    // inside a verbatim body: the body's range holds it
                }
                flow.flush(element.children);
            } else if (meaning == IkeConcept.HEADING_LINE || meaning == IkeConcept.BLOCK_TITLE_LINE) {
                flow.flush(element.children);
                String title = String.valueOf(role.properties().getOrDefault(IkeConcept.DOCUMENT_ELEMENT_TITLE, text));
                element.title = title(member, line, title, "Title");
            } else if (meaning == IkeConcept.ANCHOR_LINE) {
                element.attributes.put("id", String.valueOf(role.properties().get(IkeConcept.ANCHOR_ID)));
            } else if (meaning == IkeConcept.BLOCK_ATTRIBUTE_LINE) {
                element.attributes.putAll(attributes(text, block.kind()));
            } else if (meaning == IkeConcept.DELIMITER_LINE) {
                flow.flush(element.children);
                if (fenced && !verbatim.isEmpty()) {
                    element.children.add(verbatim(member, verbatim));
                    verbatim.clear();
                }
                fenced = !fenced && (VERBATIM.contains(block.kind()) || block.kind() == Block.Kind.OPAQUE);
            } else if (meaning == IkeConcept.COMMENT_LINE) {
                flow.flush(element.children);
                if (fenced) {
                    verbatim.add(line);
                    element.name = "Comment";
                } else {
                    comment.add(line);
                }
            } else if (meaning == IkeConcept.ATTRIBUTE_ENTRY_LINE) {
                flow.flush(element.children);
                String name = String.valueOf(role.properties().get(IkeConcept.ASCIIDOC_ATTRIBUTE_NAME));
                String value = String.valueOf(role.properties().getOrDefault(IkeConcept.ATTRIBUTE_VALUE, ""));
                if (block.kind() != Block.Kind.DOCUMENT) {
                    element.name = "Attribute entry";
                }
                element.attributes.put(name, value.endsWith("\\") ? value.substring(0, value.length() - 1).strip() : value);
                continuing = value.endsWith("\\") ? name : null;
            } else if (meaning == IkeConcept.ASCIIDOC_INCLUDE) {
                flow.flush(element.children);
                element.children.add(include(member, line, String.valueOf(role.properties().get(IkeConcept.ASCIIDOC_REFERENCE_TARGET))));
            } else if (meaning == IkeConcept.ASCIIDOC_CONDITIONAL) {
                flow.flush(element.children);
                if (!text.startsWith("endif::")) {               // the end of a conditional is its closing delimiter
                    Map<String, String> attributes = new LinkedHashMap<>();
                    attributes.put(text.startsWith("ifndef::") ? "unless defined" : "if defined",
                            String.valueOf(role.properties().get(IkeConcept.ASCIIDOC_ATTRIBUTE_NAME)));
                    element.children.add(projected(Kind.ELEMENT, member, "Conditional", attributes, text.substring(0, text.indexOf("::")),
                            List.of(line), List.of()));
                }
            } else if (meaning == IkeConcept.VERBATIM_LINE) {
                flow.flush(element.children);
                if (fenced || VERBATIM.contains(block.kind())) {
                    verbatim.add(line);
                    if (block.kind() == Block.Kind.OPAQUE) {
                        element.name = "Passthrough";
                    }
                } else {
                    macro(member, line, block.kind(), element);
                }
            } else if (meaning == IkeConcept.ITEM_LINE) {
                flow.flush(element.children);
                item(member, line, role, element, flow);
            } else {                                             // a prose line: mixed content
                int offset = 0;
                if (block.kind() == Block.Kind.BIBLIOGRAPHY_ENTRY && flow.isEmpty()) {
                    Matcher marker = UNORDERED.matcher(text);
                    if (marker.matches()) {
                        offset = marker.start(2);
                    }
                }
                if (admonitionLabel && flow.isEmpty()) {
                    Matcher label = ADMONITION.matcher(text);
                    if (label.lookingAt()) {
                        element.attributes.putIfAbsent("style", label.group(1));
                        offset = label.end();
                    }
                    admonitionLabel = false;
                }
                flow.add(line, offset);
            }
        }
        flow.flush(element.children);
        flushComment(member, comment, element.children);
        if (!verbatim.isEmpty()) {
            element.children.add(verbatim(member, verbatim));
        }
        if (!rows.isEmpty()) {
            element.children.addAll(rows(member, element.attributes, rows));
        }
        if (block.kind() == Block.Kind.LIST && !element.attributes.containsKey("style")) {
            listStyle(member, block).ifPresent(style -> element.attributes.put("style", style));
        }
        return element;
    }

    /** Whether the member after {@code index}, past blank lines, is a block. */
    private boolean blockFollows(DocumentAssembly.Member member, List<Long> members, int index) {
        for (int i = index + 1; i < members.size(); i++) {
            StoredLine line = line(member, members.get(i));
            if (line == null) {
                return member.document().blocks().containsKey(members.get(i));
            }
            if (role(member, line).meaning() != IkeConcept.BLANK_LINE) {
                return false;
            }
        }
        return false;
    }

    /**
     * A block's metadata lines after its last content line, from the first of them to its end;
     * none when it has no content line before them, or ends in a child block.
     */
    private List<StoredLine> trailingMetadata(DocumentAssembly.Member member, StoredBlock block) {
        List<Long> tree = block.tree();
        int last = tree.size() - 1;
        while (last >= 0) {
            StoredLine line = line(member, tree.get(last));
            if (line == null) {
                break;
            }
            ConceptFacade meaning = role(member, line).meaning();
            if (meaning != IkeConcept.BLANK_LINE && !METADATA.contains(meaning)) {
                break;
            }
            last--;
        }
        if (last < 0) {
            return List.of();                                    // only metadata: nothing for it to come after
        }
        List<StoredLine> trailing = new ArrayList<>();
        for (int i = last + 1; i < tree.size(); i++) {
            StoredLine line = line(member, tree.get(i));
            if (!trailing.isEmpty() || METADATA.contains(role(member, line).meaning())) {
                trailing.add(line);
            }
        }
        return trailing;
    }

    /** A list item's first line: its marker dropped, a description item's term as a {@code Term} element, the rest the item's text. */
    private void item(DocumentAssembly.Member member, StoredLine line, LineRoles.Role role, Element element, Flow flow) {
        String text = line.text();
        Matcher unordered = UNORDERED.matcher(text);
        Matcher ordered = ORDERED.matcher(text);
        if (role.properties().containsKey(IkeConcept.ASCIIDOC_TERM_TEXT)) {
            Matcher description = DESCRIPTION.matcher(text);
            if (description.matches()) {
                element.children.add(title(member, line, description.group(1).strip(), "Term"));
                if (description.group(3) != null) {
                    flow.add(line, description.start(3));
                }
                return;
            }
        }
        if (unordered.matches()) {
            flow.add(line, unordered.start(2));
        } else if (ordered.matches()) {
            flow.add(line, ordered.start(2));
        } else {
            flow.add(line, 0);
        }
    }

    /** A block macro line that is not a block of its own kind: an image's target and attributes, else a {@code Macro} element. */
    private void macro(DocumentAssembly.Member member, StoredLine line, Block.Kind kind, Element element) {
        Matcher macro = BLOCK_MACRO.matcher(line.text());
        if (!macro.matches()) {
            element.children.add(text(member, List.of(line), line.text()));
            return;
        }
        if (kind == Block.Kind.IMAGE && macro.group(1).equals("image")) {
            element.attributes.put("fileref", macro.group(2).strip());
            Map<String, String> parsed = attributes("[" + macro.group(3) + "]", Block.Kind.IMAGE);
            element.attributes.putAll(parsed);
            return;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("name", macro.group(1));
        if (!macro.group(2).isBlank()) {
            attributes.put("target", macro.group(2).strip());
        }
        if (!macro.group(3).isBlank()) {
            attributes.put("attributes", macro.group(3));
        }
        element.children.add(projected(Kind.ELEMENT, member, "Macro", attributes, macro.group(1) + "::" + macro.group(2), List.of(line), List.of()));
    }

    /** An include line: the included document when the assembly resolved it, else an {@code Include} element saying why not. */
    private Node include(DocumentAssembly.Member member, StoredLine line, String target) {
        Optional<DocumentAssembly.Include> include = assembly.includeOf(line.nid());
        if (include.isPresent() && include.get().resolved()) {
            return document(include.get().child(), line, target);
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("href", target);
        Node node = projected(Kind.ELEMENT, member, "Include", attributes, target, List.of(line), List.of());
        String note = include.map(DocumentAssembly.Include::note).orElse("");
        return note.isEmpty() ? node : withDetail(node, note + "\n" + node.detail());
    }

    private void flushComment(DocumentAssembly.Member member, List<StoredLine> comment, List<Node> into) {
        if (comment.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (StoredLine line : comment) {
            String body = line.text().replaceFirst("^//\\s?", "");
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(body);
        }
        into.add(projected(Kind.ELEMENT, member, "Comment", Map.of(), text.toString(), List.copyOf(comment), List.of()));
        comment.clear();
    }

    /** A verbatim body: one text node, every line from its first to its last as the surface holds them, blank ones included. */
    private Node verbatim(DocumentAssembly.Member member, List<StoredLine> body) {
        int first = assembly.ordinal(body.getFirst().nid());
        int last = assembly.ordinal(body.getLast().nid());
        List<StoredLine> all = first >= 0 && last >= first ? assembly.lines().subList(first, last + 1) : body;
        StringBuilder text = new StringBuilder();
        for (StoredLine line : all) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(line.text());
        }
        return text(member, List.copyOf(all), text.toString());
    }

    /** The list's style from its first item's marker: unordered, ordered or description. */
    private Optional<String> listStyle(DocumentAssembly.Member member, StoredBlock list) {
        for (long nid : list.tree()) {
            StoredBlock item = member.document().blocks().get(nid);
            if (item == null) {
                continue;
            }
            for (long itemMember : item.tree()) {
                StoredLine line = line(member, itemMember);
                if (line == null) {
                    continue;
                }
                LineRoles.Role role = role(member, line);
                if (role.meaning() != IkeConcept.ITEM_LINE) {
                    continue;
                }
                if (role.properties().containsKey(IkeConcept.ASCIIDOC_TERM_TEXT)) {
                    return Optional.of("description");
                }
                Object marker = role.properties().get(IkeConcept.LIST_MARKER);
                if (marker instanceof String m && !m.isEmpty()) {
                    return Optional.of(m.charAt(0) == '*' || m.charAt(0) == '-' ? "unordered" : "ordered");
                }
            }
            break;
        }
        return Optional.empty();
    }

    // ── tables ────────────────────────────────────────────────────────────────

    /** One cell as written: its spec and its text, which may run over several lines. */
    private static final class Cell {
        final String spec;
        final List<StoredLine> lines = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        final List<Integer> ends = new ArrayList<>();

        Cell(String spec) {
            this.spec = spec;
        }

        int width() {
            Matcher span = Pattern.compile("^(\\d+)(?:\\.(\\d+))?\\+").matcher(spec);
            return span.lookingAt() ? Integer.parseInt(span.group(1)) : 1;
        }
    }

    /**
     * A table's rows: its cells read from its lines, a {@code |} starting each, grouped by the
     * column count its {@code cols} attribute gives, or the cells on its first line. The dialect's
     * rows are the line groups blank lines separate, which is where the rows are only when a
     * table writes one row per group; a projected row that is exactly one of them keeps it.
     */
    private List<Node> rows(DocumentAssembly.Member member, Map<String, String> attributes, List<StoredBlock> stored) {
        List<Cell> cells = new ArrayList<>();
        Integer firstLineCells = null;
        Map<Long, StoredBlock> rowOf = new HashMap<>();
        boolean blankAfterFirstLine = false;
        StoredLine firstLine = null;
        for (StoredBlock row : stored) {
            for (long nid : row.tree()) {
                StoredLine line = line(member, nid);
                if (line == null || line.text().isBlank()) {
                    continue;
                }
                rowOf.put(line.nid(), row);
                Matcher m = CELL.matcher(line.text());
                int count = 0;
                int at = 0;
                Cell open = cells.isEmpty() ? null : cells.getLast();
                while (m.find()) {
                    if (open != null && m.start() > at) {
                        open.lines.add(line);
                        open.offsets.add(at);
                        open.ends.add(m.start());
                    }
                    open = new Cell(m.group(1));
                    cells.add(open);
                    at = m.end();
                    count++;
                }
                if (open != null && at < line.text().length()) {
                    open.lines.add(line);
                    open.offsets.add(at);
                    open.ends.add(line.text().length());
                } else if (open != null && count > 0 && !open.lines.contains(line)) {
                    open.lines.add(line);
                    open.offsets.add(at);
                    open.ends.add(at);
                }
                if (firstLine == null) {
                    firstLine = line;
                    firstLineCells = count;
                    int ordinal = assembly.ordinal(line.nid());
                    blankAfterFirstLine = ordinal >= 0 && ordinal + 1 < assembly.lines().size() && assembly.lines().get(ordinal + 1).text().isBlank();
                }
            }
        }
        int columns = columns(attributes.getOrDefault("cols", ""));
        if (columns <= 0) {
            columns = firstLineCells == null || firstLineCells == 0 ? 1 : firstLineCells;
        }
        boolean header = attributes.getOrDefault("options", "").contains("header")
                || (!attributes.getOrDefault("options", "").contains("noheader") && blankAfterFirstLine && firstLineCells != null && firstLineCells == columns);
        List<Node> rows = new ArrayList<>();
        List<Cell> row = new ArrayList<>();
        int width = 0;
        for (Cell cell : cells) {
            row.add(cell);
            width += cell.width();
            if (width >= columns) {
                rows.add(row(member, row, rows.isEmpty() && header, rowOf));
                row = new ArrayList<>();
                width = 0;
            }
        }
        if (!row.isEmpty()) {
            rows.add(row(member, row, rows.isEmpty() && header, rowOf));
        }
        return rows;
    }

    private Node row(DocumentAssembly.Member member, List<Cell> cells, boolean header, Map<Long, StoredBlock> rowOf) {
        List<Node> children = new ArrayList<>();
        List<StoredLine> lines = new ArrayList<>();
        for (Cell cell : cells) {
            Flow flow = new Flow(member);
            for (int i = 0; i < cell.lines.size(); i++) {
                flow.add(cell.lines.get(i), cell.offsets.get(i), cell.ends.get(i));
            }
            List<Node> content = new ArrayList<>();
            flow.flush(content);
            Map<String, String> attributes = new LinkedHashMap<>();
            if (!cell.spec.isEmpty()) {
                attributes.put("spec", cell.spec);
            }
            List<StoredLine> cellLines = distinct(cell.lines);
            children.add(projected(Kind.ELEMENT, member, "Cell", attributes, InlineMarkup.plain(flow.spans).strip(), cellLines, content));
            for (StoredLine line : cellLines) {
                if (!lines.contains(line)) {
                    lines.add(line);
                }
            }
        }
        Map<String, String> attributes = header ? Map.of("header", "yes") : Map.of();
        String name = header ? "Header row" : "Row";
        StoredBlock exact = lines.isEmpty() ? null : rowOf.get(lines.getFirst().nid());
        if (exact != null && exact.members().size() == lines.size() && lines.stream().allMatch(l -> exact.members().contains(l.nid()))) {
            int[] span = span(lines, children);
            return new Node(Kind.BLOCK, exact.nid(), name, exact.parameters(), member, span[0], span[1], exact.stamp(), exact, null, null, null,
                    null, List.of(), new Markup(name, attributes, "", List.copyOf(lines)), List.copyOf(children));
        }
        return projected(Kind.ELEMENT, member, name, attributes, "", lines, children);
    }

    /** The column count a {@code cols} value gives: a number, or one per spec with {@code n*} repeating; 0 when none. */
    static int columns(String cols) {
        String value = cols.strip();
        if (value.isEmpty()) {
            return 0;
        }
        if (value.matches("\\d+")) {
            return Integer.parseInt(value);
        }
        int count = 0;
        for (String part : value.split("[,;]")) {
            Matcher repeat = Pattern.compile("^\\s*(\\d+)\\*").matcher(part);
            count += repeat.lookingAt() ? Integer.parseInt(repeat.group(1)) : 1;
        }
        return count;
    }

    // ── attributes ────────────────────────────────────────────────────────────

    /**
     * A block attribute line read as DocBook reads its attributes: the first positional is the
     * style, with its shorthand ({@code #id}, {@code .role}, {@code %option}); the next ones are
     * named by the style ({@code source}: language; {@code quote}: attribution, citetitle; a
     * diagram: target, format; an image: alt, width, height); named ones by their names.
     */
    static Map<String, String> attributes(String line, Block.Kind kind) {
        Map<String, String> attributes = new LinkedHashMap<>();
        String body = line.strip();
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        List<String> parts = split(body);
        List<String> positional = new ArrayList<>();
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).strip().matches("[A-Za-z][\\w-]*")) {
                String name = part.substring(0, eq).strip();
                String value = unquote(part.substring(eq + 1).strip());
                attributes.put(name.equals("opts") ? "options" : name, value);
            } else {
                positional.add(unquote(part.strip()));
            }
        }
        if (kind == Block.Kind.IMAGE) {
            String[] names = {"alt", "width", "height"};
            for (int i = 0; i < positional.size() && i < names.length; i++) {
                if (!positional.get(i).isEmpty()) {
                    attributes.put(names[i], positional.get(i));
                }
            }
            return attributes;
        }
        if (positional.isEmpty()) {
            return attributes;
        }
        String first = positional.getFirst();
        int shorthand = indexOfAny(first, "#.%");
        String style = shorthand < 0 ? first : first.substring(0, shorthand);
        Map<String, String> ordered = new LinkedHashMap<>();
        if (!style.isEmpty()) {
            ordered.put("style", style);
        }
        if (shorthand >= 0) {
            ordered.putAll(InlineMarkup.shorthand(first.substring(shorthand)));
        }
        String[] names = switch (style) {
            case "source" -> new String[] {"language", "linenums"};
            case "quote", "verse" -> new String[] {"attribution", "citetitle"};
            case "plantuml", "graphviz", "ditaa", "mermaid", "a2s", "svgbob" -> new String[] {"target", "format"};
            default -> new String[0];
        };
        for (int i = 1; i < positional.size(); i++) {
            if (positional.get(i).isEmpty()) {
                continue;
            }
            ordered.put(i - 1 < names.length ? names[i - 1] : "attribute " + (i + 1), positional.get(i));
        }
        ordered.putAll(attributes);
        return ordered;
    }

    /** Splits an attribute list on commas outside quotes. */
    private static List<String> split(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                part.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                part.append(c);
            } else if (c == ',') {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        if (!part.isEmpty() || !parts.isEmpty()) {
            parts.add(part.toString());
        }
        return parts;
    }

    private static int indexOfAny(String text, String chars) {
        for (int i = 0; i < text.length(); i++) {
            if (chars.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String text) {
        return text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"))
                ? text.substring(1, text.length() - 1) : text;
    }

    private static String joinContinued(String value, String next) {
        String head = value == null ? "" : value;
        String tail = next.strip();
        if (tail.endsWith("\\")) {
            tail = tail.substring(0, tail.length() - 1).strip();
        }
        return head.isEmpty() ? tail : head + " " + tail;
    }

    // ── mixed content ─────────────────────────────────────────────────────────

    /**
     * Content lines gathered for one run of mixed content: each line's part from an offset, joined
     * by {@code \n}, parsed into spans, each span's nodes spanning the lines its characters came from.
     */
    private final class Flow {
        private final DocumentAssembly.Member member;
        private final List<StoredLine> parts = new ArrayList<>();
        private final List<Integer> starts = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        List<Span> spans = List.of();

        Flow(DocumentAssembly.Member member) {
            this.member = member;
        }

        boolean isEmpty() {
            return parts.isEmpty();
        }

        void add(StoredLine line, int from) {
            add(line, from, line.text().length());
        }

        void add(StoredLine line, int from, int to) {
            if (!parts.isEmpty()) {
                text.append('\n');
            }
            parts.add(line);
            starts.add(text.length());
            text.append(line.text(), Math.min(from, to), to);
        }

        void flush(List<Node> into) {
            if (parts.isEmpty()) {
                return;
            }
            spans = InlineMarkup.parse(text.toString());
            for (Span span : spans) {
                Node node = node(span);
                if (node != null) {
                    into.add(node);
                }
            }
            parts.clear();
            starts.clear();
            text.setLength(0);
        }

        /** A span's node: a run as text, an element with its attributes and content; a run of whitespace is no node. */
        private Node node(Span span) {
            List<StoredLine> lines = lines(span.start(), span.end());
            if (span.isText()) {
                String run = span.text().replaceAll("\\s+", " ");
                return run.isBlank() ? null : text(member, lines, run);
            }
            List<Node> children = new ArrayList<>();
            for (Span child : span.children()) {
                Node node = node(child);
                if (node != null) {
                    children.add(node);
                }
            }
            return projected(Kind.INLINE, member, span.element().word(), span.attributes(), span.text().replaceAll("\\s+", " "), lines, children);
        }

        /** The lines characters {@code [start, end)} of the joined text came from. */
        private List<StoredLine> lines(int start, int end) {
            int first = indexAt(start);
            int last = indexAt(Math.max(start, end - 1));
            return List.copyOf(parts.subList(first, last + 1));
        }

        private int indexAt(int offset) {
            int index = 0;
            for (int i = 0; i < starts.size(); i++) {
                if (starts.get(i) <= offset) {
                    index = i;
                }
            }
            return index;
        }
    }

    /** A title or term: its text parsed into mixed content, every node of it on the line it is written on. */
    private Node title(DocumentAssembly.Member member, StoredLine line, String title, String name) {
        Flow flow = new Flow(member);
        int at = Math.max(0, line.text().indexOf(title));
        flow.add(line, at, Math.min(line.text().length(), at + title.length()));
        List<Node> content = new ArrayList<>();
        flow.flush(content);
        return projected(Kind.ELEMENT, member, name, Map.of(), InlineMarkup.plain(flow.spans).replace('\n', ' '), List.of(line), content);
    }

    // ── nodes ─────────────────────────────────────────────────────────────────

    private Node text(DocumentAssembly.Member member, List<StoredLine> lines, String text) {
        return projected(Kind.TEXT, member, "text", Map.of(), text, lines, List.of());
    }

    /** A node the projection makes, with no entity of its own: it spans its lines and its children's, its STAMP the newest of its lines'. */
    private Node projected(Kind kind, DocumentAssembly.Member member, String element, Map<String, String> attributes, String text,
                           List<StoredLine> lines, List<Node> children) {
        int[] span = span(lines, children);
        StampEntity<?> stamp = null;
        for (StoredLine line : lines) {
            if (line.introduced() != null && (stamp == null || line.introduced().time() > stamp.time())) {
                stamp = line.introduced();
            }
        }
        String label = switch (kind) {
            case TEXT -> abbreviate(text.replaceAll("\\s+", " ").strip());
            default -> text.isBlank() ? element + describe(attributes) : element + ": " + abbreviate(text.replaceAll("\\s+", " ").strip());
        };
        return new Node(kind, 0, label, where(member, span), member, span[0], span[1], stamp, null, null, null, null, null, List.of(),
                new Markup(element, frozen(attributes), text, List.copyOf(lines)), List.copyOf(children));
    }

    /** An element without text says its first attribute: a cross reference its target, an anchor its id. */
    private static String describe(Map<String, String> attributes) {
        if (attributes.isEmpty()) {
            return "";
        }
        Map.Entry<String, String> first = attributes.entrySet().iterator().next();
        return " → " + first.getValue();
    }

    private Node withDetail(Node node, String detail) {
        return new Node(node.kind(), node.nid(), node.label(), detail, node.member(), node.first(), node.last(), node.stamp(), node.block(),
                node.line(), node.reference(), node.version(), node.retired(), node.commits(), node.markup(), node.children());
    }

    /** Where a node's lines are, for its tooltip: line n, or lines n–m, of the file. */
    private String where(DocumentAssembly.Member member, int[] span) {
        if (span[0] < 0) {
            return "";
        }
        int first = assembly.indexInMember(span[0]);
        int last = assembly.indexInMember(span[1]);
        if (first < 0) {
            return "";
        }
        String of = member.path().isEmpty() ? "" : " of " + member.fileName();
        return first == last || last < 0 ? "line " + (first + 1) + of : "lines " + (first + 1) + "–" + (last + 1) + of;
    }

    /** The ordinals a node spans: its own lines' and its children's. */
    private int[] span(List<StoredLine> lines, List<Node> children) {
        int first = -1;
        int last = -1;
        for (StoredLine line : lines) {
            int ordinal = assembly.ordinal(line.nid());
            if (ordinal >= 0) {
                first = first < 0 ? ordinal : Math.min(first, ordinal);
                last = Math.max(last, ordinal);
            }
        }
        for (Node child : children) {
            if (child.first() >= 0) {
                first = first < 0 ? child.first() : Math.min(first, child.first());
                last = Math.max(last, child.last());
            }
        }
        return new int[] {first, last};
    }

    /** A block's label: a section's title; else its kind with its title or the start of its text, an attribute entry with its setting. */
    private static String label(StoredBlock block, Element element) {
        String title = element.title == null ? "" : element.title.markup().text();
        if (block.kind() == Block.Kind.SECTION && !title.isEmpty()) {
            return title;
        }
        if (element.name.equals("Attribute entry") && !element.attributes.isEmpty()) {
            Map.Entry<String, String> entry = element.attributes.entrySet().iterator().next();
            return element.name + ": " + entry.getKey() + (entry.getValue().isEmpty() ? "" : " = " + abbreviate(entry.getValue()));
        }
        String text = text(element);
        return text.isEmpty() ? element.name : element.name + ": " + abbreviate(text);
    }

    /** A block's text: its title, else the text of its mixed content, else its first child's. */
    private static String text(Element element) {
        if (element.title != null) {
            return element.title.markup().text();
        }
        String content = content(element.children);
        if (!content.isEmpty()) {
            return content;
        }
        for (Node child : element.children) {
            if (child.markup() != null && !child.markup().text().isBlank()) {
                return child.markup().text().replaceAll("\\s+", " ").strip();
            }
        }
        return "";
    }

    /** The text mixed content reads as: its runs and the text of every inline element that shows any, whitespace collapsed. */
    static String content(List<Node> nodes) {
        StringBuilder text = new StringBuilder();
        for (Node node : nodes) {
            if (node.kind() == Kind.TEXT) {
                text.append(node.markup().text());
            } else if (node.kind() == Kind.INLINE && !node.children().isEmpty()) {
                text.append(content(node.children()));
            }
        }
        return text.toString().replaceAll("\\s+", " ").strip();
    }

    private static String abbreviate(String text) {
        return text.length() <= LABEL_CHARS ? text : text.substring(0, LABEL_CHARS - 1).stripTrailing() + "…";
    }

    /** Attributes in their order, unmodifiable. */
    private static Map<String, String> frozen(Map<String, String> attributes) {
        return attributes.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    private static List<StoredLine> distinct(List<StoredLine> lines) {
        List<StoredLine> out = new ArrayList<>();
        for (StoredLine line : lines) {
            if (!out.contains(line)) {
                out.add(line);
            }
        }
        return out;
    }

    // ── lines and their roles ─────────────────────────────────────────────────

    private StoredLine line(DocumentAssembly.Member member, long nid) {
        return lines.computeIfAbsent(member, m -> {
            Map<Long, StoredLine> byNid = new HashMap<>();
            for (StoredLine line : m.document().lines()) {
                byNid.put(line.nid(), line);
            }
            return byNid;
        }).get(nid);
    }

    /** A line's role, as {@link LineRoles} classifies the member's text: what the materializer stored as the line vertex's meaning. */
    private LineRoles.Role role(DocumentAssembly.Member member, StoredLine line) {
        LineRoles.Role role = roles.computeIfAbsent(member, m -> {
            List<StoredLine> all = m.document().lines();
            List<LineRoles.Role> classified = LineRoles.of(IkeDialectParser.parse(all.stream().map(StoredLine::text).toList()));
            Map<Long, LineRoles.Role> byNid = new HashMap<>();
            for (int i = 0; i < all.size() && i < classified.size(); i++) {
                byNid.put(all.get(i).nid(), classified.get(i));
            }
            return byNid;
        }).get(line.nid());
        if (role != null) {
            return role;
        }
        Map<ConceptFacade, Object> properties = new LinkedHashMap<>();
        properties.put(IkeConcept.LINE_SOURCE, line.text());
        return new LineRoles.Role(line.text().isBlank() ? IkeConcept.BLANK_LINE : IkeConcept.PROSE_LINE, properties);
    }
}
