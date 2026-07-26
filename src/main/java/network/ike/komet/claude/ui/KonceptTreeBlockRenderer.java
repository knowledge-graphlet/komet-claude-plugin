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
package network.ike.komet.claude.ui;

import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The first live-knowledge {@link BlockRenderer}: a {@code koncept-tree} fenced block becomes a
 * real, interactive tree of the shared {@link KonceptChips Koncept chips} — instead of the
 * assistant hand-drawing a taxonomy as ASCII box art ({@code IKE-Network/ike-issues#805},
 * {@code #801}).
 *
 * <p>The source is a set of <em>indented id-bearing {@code k:} tokens</em> — one per node, the
 * indentation carrying parent/child edges — reusing vocabulary already settled for the inline
 * chip ({@code #735}) rather than inventing a node syntax:
 *
 * <pre>{@code
 * ```koncept-tree
 * k:sctid=772222008[Medical devices]
 *   k:sctid=118956008[Microbiology device]
 *     k:uuid=e07f8c60-…[Multi-target respiratory NAA test]
 * ```
 * }</pre>
 *
 * <p>Each token carries identity and a bracketed authoring label. The renderer resolves the
 * identity against the live store: a token that resolves renders as its chip (identicon +
 * store-resolved, always-current name, inactive-aware), so the tree is identity-native and never a
 * re-derived picture; a token that does not resolve falls back to its authoring label as plain
 * text, so one bad id never breaks the tree. It is deliberately a <em>tree</em> (one parent per
 * node); a multi-parent DAG is a separate {@code koncept-graph} that renders through GraphViz, not
 * here.
 *
 * <p>The control is a plain {@link VBox} of indented rows — deliberately <em>not</em> a
 * {@code TreeView}: a virtualized cell steals the first mouse press for row selection (so a chip
 * needs two clicks to drag) and stretches its graphic (so the pill fills the row). With each chip a
 * direct scene-graph child, the drag gesture, drag image, and content-hugging width are identical
 * to an inline chip in flowing text. A parent row carries a disclosure triangle that collapses its
 * subtree.
 *
 * <p>Parsing is pure string work and done eagerly (to decide whether the block is a well-formed
 * tree); id resolution and the row views are built lazily by the {@link BlockPiece} supplier on the
 * JavaFX application thread. A block whose tag is not {@code koncept-tree}, or whose body has no
 * well-formed token line, is declined (empty result) so the caller renders it as preformatted text.
 */
final class KonceptTreeBlockRenderer implements BlockRenderer {

    /** The fenced-block language tag this renderer answers to. */
    static final String TAG = "koncept-tree";

    /**
     * One node line: {@code k:<kind>=<value>[label]}. Group 1 = kind (sctid/uuid/nid/id),
     * group 2 = the id value (an SCTID, a UUID, or a comma-joined UUID array), group 3 = the
     * optional bracketed authoring label. Applied to the already-stripped line body.
     */
    private static final Pattern TOKEN = Pattern.compile(
            "^k:\\s*(sctid|uuid|nid|id)\\s*=\\s*([^\\[\\]]+?)\\s*(?:\\[(.*)])?$");

    /** Left indentation added per nesting level, in px. */
    private static final double INDENT_PER_LEVEL = 16;
    /** Fixed width reserved for the disclosure triangle, so chips align across sibling rows. */
    private static final double DISCLOSURE_WIDTH = 14;

    /** The view used to resolve names and active state for chips; may be null (icon-only chips). */
    private final ViewCalculator viewCalc;
    /** The interactive surface's view — chips get status + popout (ike-issues#941); null for print/degraded. */
    private final ViewProperties viewProperties;
    /** Base body font size (px); chips scale from it. */
    private final double base;

    KonceptTreeBlockRenderer(ViewCalculator viewCalc, double base) {
        this(viewCalc, null, base);
    }

    /** Interactive-surface form: chips compute their status cluster and carry the definition popout (#941). */
    KonceptTreeBlockRenderer(ViewProperties viewProperties, double base) {
        this(viewProperties == null ? null : viewProperties.calculator(), viewProperties, base);
    }

    private KonceptTreeBlockRenderer(ViewCalculator viewCalc, ViewProperties viewProperties, double base) {
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (!TAG.equals(firstWord(info))) {
            return List.of();
        }
        List<ParsedNode> nodes = parse(literal == null ? "" : literal);
        if (nodes.isEmpty()) {
            // Tagged koncept-tree but no well-formed token line — fall through to preformatted.
            return List.of();
        }
        String projection = (literal == null ? "" : literal).stripTrailing();
        return List.of(new BlockPiece(() -> buildTree(nodes), projection));
    }

    /** The first whitespace-delimited word of the info string (the language tag), or null. */
    static String firstWord(String info) {
        if (info == null) {
            return null;
        }
        String trimmed = info.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        int sp = trimmed.indexOf(' ');
        return sp < 0 ? trimmed : trimmed.substring(0, sp);
    }

    // ── Parsing (pure; no store access) ─────────────────────────────────

    /**
     * One parsed node: its indentation (leading-space count, carrying nesting depth), the token
     * kind (sctid/uuid/nid/id), the raw id value, and the optional bracketed authoring label.
     * Deliberately store-free — resolution happens when the tree is built.
     */
    record ParsedNode(int indent, String kind, String value, String label) {
    }

    /**
     * Parses the block body into node lines. Blank lines are skipped; a non-blank line that is not
     * a well-formed {@code k:} token aborts the parse (returns empty), so a garbled tree renders as
     * code rather than a partial tree.
     */
    static List<ParsedNode> parse(String literal) {
        List<ParsedNode> nodes = new ArrayList<>();
        for (String line : literal.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            Matcher m = TOKEN.matcher(line.strip());
            if (!m.matches()) {
                return List.of();
            }
            String label = m.group(3);
            nodes.add(new ParsedNode(indent, m.group(1), m.group(2).trim(), label));
        }
        return nodes;
    }

    /**
     * Resolves a token to a {@link PublicId} that actually exists in the store, or null. The
     * existence gate is what makes the chip identity-native: a fabricated id never draws a chip
     * (it falls back to the authoring label instead).
     */
    private static PublicId resolvePid(String kind, String value) {
        try {
            PublicId pid = switch (kind) {
                case "sctid" -> PublicIds.of(UuidUtil.fromSNOMED(value));
                case "nid" -> PrimitiveData.publicId(Integer.parseInt(value));
                default -> uuidPublicId(value); // uuid | id
            };
            if (pid != null && PrimitiveData.get().hasPublicId(pid)) {
                return pid;
            }
        } catch (RuntimeException ignored) {
            // Unresolvable / non-existent id — the node falls back to its authoring label.
        }
        return null;
    }

    /** Builds a {@link PublicId} from a single UUID or a comma-joined UUID array. */
    private static PublicId uuidPublicId(String value) {
        String[] parts = value.split(",");
        UUID[] uuids = new UUID[parts.length];
        for (int i = 0; i < parts.length; i++) {
            uuids[i] = UUID.fromString(parts[i].trim());
        }
        return PublicIds.of(uuids);
    }

    // ── View building (JavaFX application thread) ───────────────────────

    /** A resolved cell: its chip id (or null when unresolved), SCTID for the tooltip, and label. */
    private record Cell(PublicId pid, String sctid, String label) {
    }

    /** One tree row: its data, nesting depth, parent, children, expansion state, and built view. */
    private static final class Row {
        final Cell cell;
        int depth;
        Row parent;
        final List<Row> children = new ArrayList<>();
        boolean expanded = true;
        HBox view;

        Row(Cell cell) {
            this.cell = cell;
        }
    }

    /**
     * Builds the interactive tree (a {@link VBox} of indented rows) from the parsed nodes, resolving
     * each id against the store. Must run on the JavaFX thread.
     */
    private Node buildTree(List<ParsedNode> nodes) {
        if (nodes.isEmpty()) {
            return new Region();
        }
        List<Row> rows = buildRows(nodes);
        VBox column = new VBox(3);
        column.getStyleClass().add("koncept-tree");
        // The tree sits inside a RichTextArea, which shows a text (I-beam) cursor; override it so the
        // widget reads as a widget. Chips/disclosures set their own cursor where a hand is warranted.
        column.setCursor(Cursor.DEFAULT);
        // Rows are not stretched to the column width: each hugs its content, so the column is only as
        // wide as its widest row — never padded out to the whole transcript. The RTA bounds the
        // column to the host width, so an over-long row is clipped there rather than scrolling.
        column.setFillWidth(false);
        for (Row row : rows) {
            column.getChildren().add(row.view);
        }
        column.setMaxWidth(Double.MAX_VALUE);
        column.parentProperty().addListener((obs, oldParent, newParent) -> {
            column.maxWidthProperty().unbind();
            if (newParent instanceof Region host) {
                column.maxWidthProperty().bind(host.widthProperty());
            }
        });
        return column;
    }

    /**
     * Builds the {@link Row} tree from the parsed nodes (indentation → parent/child) and each row's
     * view, returning the rows in display (depth-first) order.
     */
    private List<Row> buildRows(List<ParsedNode> nodes) {
        List<Row> rows = new ArrayList<>();
        // Indent stack: each frame is the last row seen at a given indentation. A node attaches to
        // the nearest ancestor with a strictly smaller indent, so any consistent indent unit works.
        Deque<Row> stack = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        for (ParsedNode node : nodes) {
            String sctid = "sctid".equals(node.kind()) ? node.value() : null;
            Row row = new Row(new Cell(resolvePid(node.kind(), node.value()), sctid, node.label()));
            while (!indents.isEmpty() && indents.peek() >= node.indent()) {
                indents.pop();
                stack.pop();
            }
            row.depth = stack.size();
            if (!stack.isEmpty()) {
                row.parent = stack.peek();
                stack.peek().children.add(row);
            }
            rows.add(row);
            stack.push(row);
            indents.push(node.indent());
        }
        for (Row row : rows) {
            row.view = buildRowView(row, rows);
        }
        return rows;
    }

    /**
     * Builds one row: indentation, a disclosure triangle (for a parent) or an aligning spacer, then
     * the resolved node's shared Koncept chip (a direct scene-graph child, so it drags exactly like
     * an inline chip) or its authoring label when the id does not resolve.
     */
    private HBox buildRowView(Row row, List<Row> allRows) {
        HBox rowBox = new HBox(3);
        rowBox.setAlignment(Pos.CENTER_LEFT);
        rowBox.setPadding(new Insets(0, 0, 0, row.depth * INDENT_PER_LEVEL));

        Label disclosure = new Label();
        disclosure.setMinWidth(DISCLOSURE_WIDTH);
        disclosure.setPrefWidth(DISCLOSURE_WIDTH);
        disclosure.setFont(Font.font(base * 0.85));
        if (!row.children.isEmpty()) {
            disclosure.setText("▾"); // ▾
            disclosure.setStyle("-fx-text-fill: #888; -fx-cursor: hand;");
            disclosure.setOnMouseClicked(event -> {
                row.expanded = !row.expanded;
                disclosure.setText(row.expanded ? "▾" : "▸"); // ▾ / ▸
                recomputeVisibility(allRows);
                event.consume();
            });
        }

        Node content;
        if (row.cell.pid() != null) {
            // The chip factory consumes the press itself now (one behavior per atom), so the
            // RichTextArea never begins a text selection over a chip anywhere it is embedded.
            content = viewProperties != null
                    ? KonceptChips.chip(row.cell.pid(), row.cell.sctid(), viewProperties, base)
                    : KonceptChips.chip(row.cell.pid(), row.cell.sctid(), viewCalc, base);
        } else {
            Label label = new Label((row.cell.label() == null || row.cell.label().isBlank())
                    ? "(unresolved)" : row.cell.label());
            label.setFont(Font.font(base));
            content = label;
        }
        rowBox.getChildren().addAll(disclosure, content);
        return rowBox;
    }

    /**
     * Recomputes each row's visibility from the expansion state: a row shows only when every
     * ancestor is expanded. Hidden rows are also unmanaged, so a collapsed subtree takes no vertical
     * space and the block shrinks.
     */
    private static void recomputeVisibility(List<Row> rows) {
        for (Row row : rows) {
            boolean visible = true;
            for (Row ancestor = row.parent; ancestor != null; ancestor = ancestor.parent) {
                if (!ancestor.expanded) {
                    visible = false;
                    break;
                }
            }
            row.view.setVisible(visible);
            row.view.setManaged(visible);
        }
    }
}
