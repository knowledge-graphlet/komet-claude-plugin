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

import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.actions.KometActions;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * The assistant's tools over the document graph (KEC 2026-10-20: the assistant reads the
 * STAMPed document in memory and proposes edits to it): a document materialized from git lives
 * in the store as semantics — its anchor, manifest, block tree, lines with the STAMPs that
 * introduced them, annotations and references — and these tools read that graph directly, with
 * no working tree and no git. {@code read_document} gives the outline and lines, a page at a
 * time; {@code read_block} one block with every line's full provenance; {@code find_in_document}
 * searches the lines; {@code propose_block_edit} replaces a block's text as the assistant's own
 * uncommitted edit, through the starter set's {@code Edit document block} action, under the
 * Komet assistant's author, so the card shows it pending and the person commits or discards it.
 *
 * <p>Blocks are named by path ({@code §3.2}) or by a line they hold ({@code L45}); a document by
 * the name the card's chooser shows, or by its recorded path. Without a name the tools read the
 * document the most recent Stamped Document card shows.
 */
public final class DocumentGraphTools {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    /** The outline's page size: blocks, and characters, so a page of the compendium stays a few thousand tokens. */
    static final int PAGE_BLOCKS = 80;
    static final int PAGE_CHARS = 24_000;

    private DocumentGraphTools() { }

    /**
     * @param card locates the Stamped Document card the tools default to
     * @param view the view names are read in; may answer {@code null} (the default view is used)
     * @return the four tools
     */
    public static List<AnthropicTool> tools(StampTools.CardLocator card, Supplier<ViewCalculator> view) {
        return List.of(readDocument(card, view), readBlock(card, view), findInDocument(card, view), proposeBlockEdit(card, view));
    }

    // ── the document addressed ────────────────────────────────────────────────

    /**
     * A document the tools read: its anchor, the name it goes by, the stored document with its
     * pending edit, and the card when the card shows it.
     */
    record Opened(PublicId anchor, String name, StoredDocument document, StampedDocumentCard card) { }

    /** Finds the document a call names: the card's when the name is blank, else by chooser name or recorded path. */
    static Opened open(StampedDocumentCard card, String name) {
        if (name == null || name.isBlank()) {
            if (card == null || card.shown().isEmpty()) {
                throw new IllegalArgumentException("no Stamped Document card shows a document; pass document, a name the card's "
                        + "chooser shows: the store holds " + examples());
            }
            PublicId anchor = card.shown().get().anchor();
            return new Opened(anchor, card.currentDocument().orElse(""), load(anchor), card);
        }
        String wanted = name.strip();
        Map<String, PublicId> byName = GitDocumentMaterializer.storedDocuments();
        PublicId anchor = byName.get(wanted);
        if (anchor == null) {
            List<String> close = byName.keySet().stream().filter(n -> n.equalsIgnoreCase(wanted)).toList();
            if (close.isEmpty()) {
                close = byName.keySet().stream().filter(n -> n.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT))).toList();
            }
            if (close.size() == 1) {
                anchor = byName.get(close.getFirst());
            } else if (close.size() > 1) {
                throw new IllegalArgumentException("'" + wanted + "' names " + close.size() + " documents: " + String.join(", ", close.subList(0, Math.min(12, close.size()))));
            }
        }
        if (anchor == null) {
            List<PublicId> byPath = GitDocumentMaterializer.storedDocumentsByPath().get(wanted);
            if (byPath != null && !byPath.isEmpty()) {
                anchor = byPath.getFirst();
            }
        }
        if (anchor == null) {
            throw new IllegalArgumentException("the store holds no document named '" + wanted + "'; it holds " + examples());
        }
        PublicId found = anchor;
        StampedDocumentCard showing = card != null && card.shown().map(s -> s.anchor().equals(found)).orElse(false) ? card : null;
        String shownName = byName.entrySet().stream().filter(e -> e.getValue().equals(found)).map(Map.Entry::getKey).findFirst().orElse(wanted);
        return new Opened(anchor, shownName, load(anchor), showing);
    }

    private static StoredDocument load(PublicId anchor) {
        return GitDocumentMaterializer.load(anchor, true)
                .orElseThrow(() -> new IllegalArgumentException("the store holds no manifest for that document"));
    }

    private static String examples() {
        List<String> names = new ArrayList<>(GitDocumentMaterializer.storedDocuments().keySet());
        if (names.isEmpty()) {
            return "no documents yet (Open file… in a Stamped Document card materializes one)";
        }
        return names.size() + " document(s): " + String.join(", ", names.subList(0, Math.min(12, names.size()))) + (names.size() > 12 ? ", …" : "");
    }

    /** How STAMPs read for the assistant: short commit, day, author; an uncommitted one by its author. */
    static DocumentOutline.Describe describe(ViewCalculator view, CommitSummaries summaries) {
        return new DocumentOutline.Describe() {
            @Override
            public String stamp(StampEntity<?> stamp) {
                if (stamp == null) {
                    return "—";
                }
                String author = StampNames.name(view, stamp.authorNid());
                if (stamp.time() == Long.MAX_VALUE) {
                    return "uncommitted, " + author;
                }
                String sha = summaries.sha(stamp.nid()).map(s -> s.substring(0, Math.min(7, s.length()))).orElse("·······");
                return sha + " " + DAY.format(Instant.ofEpochMilli(stamp.time())) + " " + author;
            }

            @Override
            public Optional<String> summary(StampEntity<?> stamp) {
                return stamp == null ? Optional.empty() : summaries.summary(stamp.nid());
            }
        };
    }

    private static ViewCalculator viewOf(Supplier<ViewCalculator> view, StampedDocumentCard card) {
        ViewCalculator v = null;
        try {
            v = view == null ? null : view.get();
        } catch (RuntimeException ignored) {
            // the assistant card is not bound yet
        }
        if (v == null && card != null) {
            v = card.view();
        }
        return v != null ? v : Calculators.View.Default();
    }

    private static CommitSummaries summaries(Opened opened) {
        if (opened.card() != null) {
            return opened.card().summaries();
        }
        return CommitSummaries.read(opened.anchor(), null, null);
    }

    /** The document's header: name, title, commit, size, pending edit, and the documents it includes. */
    static String header(Opened opened, DocumentOutline graph, ViewCalculator view) {
        StoredDocument d = opened.document();
        StringBuilder out = new StringBuilder();
        out.append("Document ").append(opened.name().isBlank() ? graph.title() : opened.name());
        if (!graph.title().isBlank() && !graph.title().equals(opened.name())) {
            out.append(" — \"").append(graph.title()).append('"');
        }
        out.append('\n');
        GitDocumentMaterializer.recordedPath(opened.anchor()).ifPresent(p -> out.append("Recorded path: ").append(p).append('\n'));
        out.append("Commit: ").append(d.commit().isEmpty() ? "?" : d.commit().substring(0, Math.min(7, d.commit().length())))
                .append("; ").append(d.lines().size()).append(" lines, ").append(graph.nodes().size()).append(" blocks, ")
                .append(d.manifestVersions()).append(" manifest version(s)\n");
        OptionalLong pending = DocumentEditor.pendingAuthor(opened.anchor());
        if (pending.isPresent()) {
            out.append("Pending edit (uncommitted, shown with the edited badge): by ").append(StampNames.name(view, pending.getAsLong()))
                    .append("; lines marked ✎\n");
        }
        if (opened.card() != null) {
            opened.card().assembly().ifPresent(assembly -> {
                List<String> included = new ArrayList<>();
                for (DocumentAssembly.Member m : assembly.members()) {
                    if (!m.host()) {
                        included.add(StampNames.name(view, PrimitiveData.nid(m.document().anchor())) + " (" + m.path() + ")");
                    }
                }
                if (!included.isEmpty()) {
                    out.append("Includes ").append(included.size()).append(" document(s), each read on its own with document=<name>: ")
                            .append(String.join(", ", included.subList(0, Math.min(40, included.size())))).append(included.size() > 40 ? ", …" : "")
                            .append('\n');
                }
            });
            out.append(StampTools.hasWorkingTree(opened.card())
                    ? "Working tree: " + opened.card().repoRoot() + " (annotations can be proposed)\n"
                    : "Working tree: none set in the card (reading and edit proposals need none; annotations need one: Open file… or Clone…)\n");
        }
        return out.toString();
    }

    // ── read_document ─────────────────────────────────────────────────────────

    static AnthropicTool readDocument(StampTools.CardLocator card, Supplier<ViewCalculator> view) {
        return new Tool("read_document",
                "Read a STAMPed document from the knowledge base's document graph — no git, no working tree needed: its "
                        + "title and commit, then its block tree in document order, each block named by its path (§3.2) with its "
                        + "kind, level, title and parameters and the line range it holds (L45-L52), and, unless outline_only, every "
                        + "line: its ordinal, the STAMP that introduced it (short commit, date, author; 'uncommitted, <author>' for a "
                        + "pending edit), review marks (✓ affirmed, ✗ refuted, … commented, ✎ uncommitted) and its AsciiDoc text. "
                        + "Defaults to the document shown in the Stamped Document card; pass document to read another by the name the "
                        + "card's chooser shows or its recorded path. Long documents come a page at a time: pass offset (the next "
                        + "block index the result names) to continue. Call this before saying what a document says, who wrote a part "
                        + "of it, or proposing an edit.",
                obj("type", "object", "properties", obj(
                        "document", strProp("The document's name as the card's chooser shows it, or its recorded path. Omit for the card's document."),
                        "offset", intProp("First block to show, 0-based, as a previous page named it. Default 0."),
                        "max_blocks", intProp("At most this many blocks on the page. Default " + PAGE_BLOCKS + "."),
                        "outline_only", boolProp("List the blocks without their lines, for a quick map of a long document.")),
                        "required", List.of()),
                input -> {
                    StampedDocumentCard c = card.get();
                    Opened opened = open(c, str(input, "document"));
                    ViewCalculator v = viewOf(view, c);
                    DocumentOutline graph = new DocumentOutline(opened.document());
                    int offset = Math.max(0, integer(input, "offset", 0));
                    int max = Math.clamp(integer(input, "max_blocks", PAGE_BLOCKS), 1, 400);
                    boolean outline = Boolean.TRUE.equals(input.get("outline_only"));
                    DocumentOutline.Page page = graph.outline(offset, max, outline ? PAGE_CHARS * 2 : PAGE_CHARS, !outline, describe(v, summaries(opened)));
                    StringBuilder out = new StringBuilder(header(opened, graph, v));
                    out.append("Blocks ").append(page.from()).append('-').append(page.next() - 1).append(" of ").append(page.total())
                            .append(outline ? " (outline)" : "").append(":\n").append(page.text());
                    if (page.more()) {
                        out.append("… ").append(page.total() - page.next()).append(" more block(s): call read_document with offset=")
                                .append(page.next()).append(" to continue.\n");
                    }
                    return out.toString();
                });
    }

    // ── read_block ────────────────────────────────────────────────────────────

    static AnthropicTool readBlock(StampTools.CardLocator card, Supplier<ViewCalculator> view) {
        return new Tool("read_block",
                "Read one block of a STAMPed document from the document graph, with every line's full provenance: the STAMP "
                        + "that introduced it and its commit summary, the latest version when it differs (an uncommitted edit), each "
                        + "affirmation, refutation and comment with its author, date and text, and the references on the line. Name "
                        + "the block by its path (§3.2) or by a line it holds (L45). The lines are the block's AsciiDoc source as "
                        + "propose_block_edit replaces it.",
                obj("type", "object", "properties", obj(
                        "block", strProp("The block's path (§3.2 or 3.2) or a line it holds (L45)."),
                        "document", strProp("The document's name or recorded path. Omit for the card's document.")),
                        "required", List.of("block")),
                input -> {
                    StampedDocumentCard c = card.get();
                    Opened opened = open(c, str(input, "document"));
                    ViewCalculator v = viewOf(view, c);
                    DocumentOutline graph = new DocumentOutline(opened.document());
                    DocumentOutline.Node node = graph.find(str(input, "block"))
                            .orElseThrow(() -> new IllegalArgumentException("no block " + str(input, "block") + " in " + opened.name()
                                    + "; name it by its path (§3.2) or by a line it holds (L45), as read_document shows them"));
                    StringBuilder out = new StringBuilder();
                    out.append(opened.name()).append(": ");
                    graph.block(node, true, true, describe(v, summaries(opened)), out);
                    if (!node.editable()) {
                        out.append(node.blocks() > 0
                                ? "This block holds " + node.blocks() + " block(s); propose edits to them one at a time.\n"
                                : "This block holds no lines of its own.\n");
                    }
                    return out.toString();
                });
    }

    // ── find_in_document ──────────────────────────────────────────────────────

    static AnthropicTool findInDocument(StampTools.CardLocator card, Supplier<ViewCalculator> view) {
        return new Tool("find_in_document",
                "Search the lines and block titles of a STAMPed document in the document graph for a text, case-insensitively; "
                        + "each match names the block (§3.2), its kind and the line (L45) with its introducing STAMP and text. Use it "
                        + "to find where a document says something before reading the block around it.",
                obj("type", "object", "properties", obj(
                        "query", strProp("The text sought."),
                        "document", strProp("The document's name or recorded path. Omit for the card's document."),
                        "max_results", intProp("At most this many matches. Default 40.")),
                        "required", List.of("query")),
                input -> {
                    String query = str(input, "query");
                    if (query == null || query.isBlank()) {
                        throw new IllegalArgumentException("query is empty");
                    }
                    StampedDocumentCard c = card.get();
                    Opened opened = open(c, str(input, "document"));
                    ViewCalculator v = viewOf(view, c);
                    DocumentOutline graph = new DocumentOutline(opened.document());
                    int max = Math.clamp(integer(input, "max_results", 40), 1, 400);
                    DocumentOutline.Matches matches = graph.search(query, max, describe(v, summaries(opened)));
                    if (matches.count() == 0) {
                        return "No line of " + opened.name() + " contains \"" + query + "\".";
                    }
                    return matches.count() + " match(es) for \"" + query + "\" in " + opened.name()
                            + (matches.count() > max ? " (first " + max + " shown)" : "") + ":\n" + matches.text();
                });
    }

    // ── propose_block_edit ────────────────────────────────────────────────────

    static AnthropicTool proposeBlockEdit(StampTools.CardLocator card, Supplier<ViewCalculator> view) {
        return new Tool("propose_block_edit",
                "Propose new text for one block of a STAMPed document: the block's lines are replaced with the text, written "
                        + "as an uncommitted edit under the Komet assistant's name — the same edit the card's Edit block makes for a "
                        + "person — so the card shows it pending with the edited badge and the person commits it in git (Commit…) or "
                        + "discards it (Discard edit). Nothing reaches git until they commit; say so. Read the block first with "
                        + "read_block: the text replaces its AsciiDoc source lines exactly, one line per line (a listing keeps its "
                        + "---- delimiters). Only blocks that hold lines and no blocks can be edited (paragraphs, list items, listings, "
                        + "table rows). Refused when another author's edit of the document is pending: theirs is settled first. "
                        + "Inserting or deleting whole blocks is not supported; propose the neighbouring block's new text instead.",
                obj("type", "object", "properties", obj(
                        "block", strProp("The block's path (§3.2) or a line it holds (L45), as read_document or read_block shows it."),
                        "text", strProp("The block's new AsciiDoc source, one line per line."),
                        "document", strProp("The document's name or recorded path. Omit for the card's document.")),
                        "required", List.of("block", "text")),
                input -> {
                    String text = str(input, "text");
                    if (text == null || text.isBlank()) {
                        throw new IllegalArgumentException("text is empty; a block keeps at least one line");
                    }
                    StampedDocumentCard c = card.get();
                    Opened opened = open(c, str(input, "document"));
                    ViewCalculator v = viewOf(view, c);
                    DocumentOutline graph = new DocumentOutline(opened.document());
                    DocumentOutline.Node node = graph.find(str(input, "block"))
                            .orElseThrow(() -> new IllegalArgumentException("no block " + str(input, "block") + " in " + opened.name()));
                    if (!node.editable()) {
                        throw new IllegalArgumentException("§" + node.path() + " is a " + node.block().kind()
                                + (node.blocks() > 0 ? " holding " + node.blocks() + " block(s); propose an edit to one of them" : " with no lines of its own"));
                    }
                    EditCoordinateRecord edit = assistantCoordinate(c);
                    OptionalLong pending = DocumentEditor.pendingAuthor(opened.anchor());
                    if (pending.isPresent() && pending.getAsLong() != edit.getAuthorNidForChanges()) {
                        return "Not proposed: an edit of " + opened.name() + " by " + StampNames.name(v, pending.getAsLong())
                                + " is pending in the card. A change is never made on top of a change nobody has settled: ask them to "
                                + "commit it in git (Commit…) or discard it (Discard edit), then propose again.";
                    }
                    KometActionResult result = KometActions.expect(KometConcept.EDIT_DOCUMENT_BLOCK)
                            .perform(EditDocumentBlock.input(node.block().nid(), text.endsWith("\n") ? text : text + "\n"), v, edit);
                    if (!result.completed()) {
                        return "Not proposed: " + result.message();
                    }
                    if (c != null && opened.card() != null) {
                        reopen(c, opened.anchor());
                    }
                    return "Proposed for §" + node.path() + " of " + opened.name() + ": " + result.message()
                            + ". It is pending under " + StampNames.name(v, edit.getAuthorNidForChanges())
                            + (opened.card() != null ? "; the card shows it with the edited badge" : "; open the document in a Stamped Document card to see it")
                            + ". Nothing is in git until the person commits it (Commit…); Discard edit withdraws it.";
                });
    }

    /** The assistant's edit coordinate: its own author, the card's module and path. */
    static EditCoordinateRecord assistantCoordinate(StampedDocumentCard card) {
        EditCoordinateRecord base = card != null ? card.editCoordinateForTools() : Coordinates.Edit.Default();
        return EditCoordinateRecord.make(IkeConcept.KOMET_ASSISTANT.nid(), base.getDefaultModuleNid(), base.getDefaultPathNid());
    }

    private static void reopen(StampedDocumentCard card, PublicId anchor) {
        try {
            javafx.application.Platform.runLater(() -> card.open(anchor));
        } catch (IllegalStateException toolkitNotRunning) {
            // headless: nothing to redraw
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────

    @FunctionalInterface
    interface Body {
        String run(Map<String, Object> input);
    }

    /** A tool whose body's refusals come back to the model as one line it can act on. */
    record Tool(String name, String description, Map<String, Object> inputSchema, Body body) implements AnthropicTool {

        @Override
        public String execute(Map<String, Object> input) {
            try {
                return body.run(input == null ? Map.of() : input);
            } catch (IllegalArgumentException e) {
                return "Error: " + e.getMessage();
            } catch (RuntimeException e) {
                return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
    }

    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static Map<String, Object> strProp(String description) {
        return obj("type", "string", "description", description);
    }

    static Map<String, Object> intProp(String description) {
        return obj("type", "integer", "description", description);
    }

    static Map<String, Object> boolProp(String description) {
        return obj("type", "boolean", "description", description);
    }

    static String str(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v == null ? null : String.valueOf(v);
    }

    static int integer(Map<String, Object> input, String key, int fallback) {
        Object v = input.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be a number, not " + s);
            }
        }
        return fallback;
    }
}
