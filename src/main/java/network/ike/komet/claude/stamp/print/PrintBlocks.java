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
package network.ike.komet.claude.stamp.print;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import network.ike.komet.claude.doc.DocumentBlock;
import network.ike.komet.claude.doc.print.PrintSource;
import network.ike.komet.claude.doc.print.PrintSource.PrintBlock;
import network.ike.komet.claude.doc.print.settings.DocumentTheme;
import network.ike.komet.claude.stamp.print.DocumentModel.Admonition;
import network.ike.komet.claude.stamp.print.DocumentModel.Block;
import network.ike.komet.claude.stamp.print.DocumentModel.Container;
import network.ike.komet.claude.stamp.print.DocumentModel.Heading;
import network.ike.komet.claude.stamp.print.DocumentModel.Image;
import network.ike.komet.claude.stamp.print.DocumentModel.Item;
import network.ike.komet.claude.stamp.print.DocumentModel.ListBlock;
import network.ike.komet.claude.stamp.print.DocumentModel.Paragraph;
import network.ike.komet.claude.stamp.print.DocumentModel.Table;
import network.ike.komet.claude.stamp.print.DocumentModel.Verbatim;

import java.util.ArrayList;
import java.util.List;

/**
 * The paged preview's blocks for a stamped document: the {@link DocumentModel} drawn as the
 * Reading view draws it — headings by level, prose at the theme's size and family, list items
 * with their markers and indentation, admonitions and verbatim blocks on their tints, table
 * rows as rows of cells with the header row bold — without the edit, hover and selection
 * wiring. Every block keeps together; a heading and a table title keep with what follows;
 * each block names the section it falls under so the running head can name it. Must be used
 * on the JavaFX application thread.
 */
public final class PrintBlocks {

    /** The deepest heading level the running head names, as in the Typst writer. */
    private static final int RUNNING_HEAD_DEPTH = 2;
    private static final String TINT_VERBATIM = "#F4F3F1";
    private static final String TINT_ADMONITION = "#FFF8E1";
    private static final String TINT_HEADER_CELL = "#ECEAE6";

    private final double base;
    private final String family;
    private final List<PrintBlock> out = new ArrayList<>();
    private String section = "";

    private PrintBlocks(DocumentTheme theme) {
        this.base = theme.baseFontSize();
        this.family = theme.fontFamily();
    }

    /**
     * A print source over a model.
     *
     * @param model the document
     * @return the source, building fresh nodes for each layout
     */
    public static PrintSource source(DocumentModel model) {
        return (theme, viewCalc) -> of(model, theme);
    }

    /**
     * Builds the blocks once, for a theme.
     *
     * @param model the document
     * @param theme the theme
     * @return the blocks in order
     */
    public static List<PrintBlock> of(DocumentModel model, DocumentTheme theme) {
        PrintBlocks builder = new PrintBlocks(theme);
        if (!model.title().isEmpty()) {
            Node title = builder.text(model.title(), FontWeight.BOLD, FontPosture.REGULAR, 1.8, new Insets(0, 0, builder.base * 0.8, 0));
            builder.add(title, model.title(), true, true);
        }
        for (Block block : model.blocks()) {
            builder.block(block, 0);
        }
        return List.copyOf(builder.out);
    }

    private void add(Node node, String plain, boolean atomic, boolean keepWithNext) {
        out.add(new PrintBlock(DocumentBlock.of(node, plain), section, atomic, keepWithNext));
    }

    private void block(Block block, int depth) {
        switch (block) {
            case Heading h -> {
                if (h.level() <= RUNNING_HEAD_DEPTH) {
                    section = h.text();
                }
                double scale = switch (Math.max(1, h.level())) {
                    case 1 -> 1.5;
                    case 2 -> 1.25;
                    case 3 -> 1.1;
                    default -> 1.0;
                };
                Node node = text(h.text(), FontWeight.BOLD, h.level() >= 4 ? FontPosture.ITALIC : FontPosture.REGULAR, scale,
                        new Insets(base * 0.9, 0, base * 0.4, depth * base * 1.5));
                add(node, h.text(), true, true);
            }
            case Paragraph p -> add(paragraph(p.text(), depth), p.text(), true, false);
            case ListBlock l -> {
                if (!l.title().isEmpty()) {
                    add(blockTitle(l.title(), depth), l.title(), true, true);
                }
                int n = 0;
                for (Item item : l.items()) {
                    n++;
                    String marker = switch (l.kind()) {
                        case ORDERED -> n + ".";
                        case DESCRIPTION -> "";
                        case UNORDERED -> "•";
                        case BIBLIOGRAPHY -> "▪";
                    };
                    TextFlow flow = new TextFlow();
                    if (l.kind() == ListBlock.Kind.DESCRIPTION) {
                        Text term = new Text(item.term() + (item.text().isEmpty() ? "" : ": "));
                        term.setFont(font(FontWeight.BOLD, FontPosture.REGULAR, 1));
                        flow.getChildren().add(term);
                    } else {
                        Text mark = new Text(marker + "  ");
                        mark.setFont(font(FontWeight.NORMAL, FontPosture.REGULAR, 1));
                        flow.getChildren().add(mark);
                    }
                    Text body = new Text(item.text());
                    body.setFont(font(FontWeight.NORMAL, FontPosture.REGULAR, 1));
                    flow.getChildren().add(body);
                    flow.setLineSpacing(base * 0.25);
                    flow.setPadding(new Insets(base * 0.15, 0, base * 0.15, (depth + 1) * base * 1.5));
                    add(flow, item.term() + " " + item.text(), true, false);
                    for (Block nested : item.blocks()) {
                        block(nested, depth + 1);
                    }
                }
                add(spacer(base * 0.5), "", true, false);
            }
            case Admonition a -> {
                VBox box = new VBox(base * 0.3);
                box.setPadding(new Insets(base * 0.5, base * 0.7, base * 0.5, base * 0.7));
                box.setStyle("-fx-background-color: " + TINT_ADMONITION + "; -fx-background-radius: 4;");
                Node label = text(a.label(), FontWeight.BOLD, FontPosture.REGULAR, 0.8, Insets.EMPTY);
                box.getChildren().add(label);
                for (Block inner : a.blocks()) {
                    box.getChildren().add(node(inner));
                }
                box.setMaxWidth(Double.MAX_VALUE);
                add(indented(box, depth), a.label(), true, false);
            }
            case Verbatim v -> {
                if (!v.title().isEmpty()) {
                    add(blockTitle(v.title(), depth), v.title(), true, true);
                }
                add(indented(verbatim(v.text()), depth), v.text(), true, false);
            }
            case Table t -> {
                if (!t.title().isEmpty()) {
                    add(blockTitle(t.title(), depth), t.title(), true, true);
                }
                for (int r = 0; r < t.rows().size(); r++) {
                    add(indented(row(t, r), depth), String.join(" | ", t.rows().get(r)), true, r < t.headerRows());
                }
                add(spacer(base * 0.6), "", true, false);
            }
            case Image i -> {
                Node node = text("Image: " + i.target() + (i.title().isEmpty() ? "" : " — " + i.title()),
                        FontWeight.NORMAL, FontPosture.ITALIC, 0.85, new Insets(base * 0.3, 0, base * 0.3, depth * base * 1.5));
                add(node, i.target(), true, false);
            }
            case Container c -> {
                if (!c.title().isEmpty()) {
                    add(blockTitle(c.title(), depth), c.title(), true, true);
                }
                if (c.kind() == Container.Kind.OPEN) {
                    for (Block inner : c.blocks()) {
                        block(inner, depth);
                    }
                    return;
                }
                VBox box = new VBox(base * 0.4);
                box.setPadding(new Insets(base * 0.5, base * 0.7, base * 0.5, base * 0.7));
                box.setStyle(switch (c.kind()) {
                    case EXAMPLE -> "-fx-border-color: #cfcbc4; -fx-border-width: 0.5; -fx-border-radius: 3;";
                    case SIDEBAR -> "-fx-background-color: " + TINT_VERBATIM + "; -fx-background-radius: 3;";
                    default -> "-fx-border-color: transparent transparent transparent #cfcbc4; -fx-border-width: 0 0 0 2;";
                });
                for (Block inner : c.blocks()) {
                    box.getChildren().add(node(inner));
                }
                box.setMaxWidth(Double.MAX_VALUE);
                add(indented(box, depth), c.title(), true, false);
            }
        }
    }

    /** A nested block as one node, for the inside of an admonition or a container. */
    private Node node(Block block) {
        return switch (block) {
            case Heading h -> text(h.text(), FontWeight.BOLD, FontPosture.REGULAR, 1.1, new Insets(base * 0.3, 0, base * 0.2, 0));
            case Paragraph p -> paragraph(p.text(), 0);
            case ListBlock l -> {
                VBox list = new VBox(base * 0.15);
                if (!l.title().isEmpty()) {
                    list.getChildren().add(blockTitle(l.title(), 0));
                }
                int n = 0;
                for (Item item : l.items()) {
                    n++;
                    String lead = switch (l.kind()) {
                        case ORDERED -> n + ".  ";
                        case DESCRIPTION -> item.term() + (item.text().isEmpty() ? "" : ": ");
                        case UNORDERED -> "•  ";
                        case BIBLIOGRAPHY -> "▪  ";
                    };
                    Text mark = new Text(lead);
                    mark.setFont(font(l.kind() == ListBlock.Kind.DESCRIPTION ? FontWeight.BOLD : FontWeight.NORMAL, FontPosture.REGULAR, 1));
                    Text body = new Text(item.text());
                    body.setFont(font(FontWeight.NORMAL, FontPosture.REGULAR, 1));
                    TextFlow flow = new TextFlow(mark, body);
                    flow.setPadding(new Insets(0, 0, 0, base * 1.5));
                    list.getChildren().add(flow);
                    for (Block nested : item.blocks()) {
                        Node inner = node(nested);
                        VBox.setMargin(inner, new Insets(0, 0, 0, base * 3));
                        list.getChildren().add(inner);
                    }
                }
                yield list;
            }
            case Admonition a -> {
                VBox box = new VBox(base * 0.3);
                box.setPadding(new Insets(base * 0.4, base * 0.6, base * 0.4, base * 0.6));
                box.setStyle("-fx-background-color: " + TINT_ADMONITION + "; -fx-background-radius: 4;");
                box.getChildren().add(text(a.label(), FontWeight.BOLD, FontPosture.REGULAR, 0.8, Insets.EMPTY));
                for (Block inner : a.blocks()) {
                    box.getChildren().add(node(inner));
                }
                yield box;
            }
            case Verbatim v -> {
                VBox box = new VBox(base * 0.2);
                if (!v.title().isEmpty()) {
                    box.getChildren().add(blockTitle(v.title(), 0));
                }
                box.getChildren().add(verbatim(v.text()));
                yield box;
            }
            case Table t -> {
                VBox rows = new VBox();
                if (!t.title().isEmpty()) {
                    rows.getChildren().add(blockTitle(t.title(), 0));
                }
                for (int r = 0; r < t.rows().size(); r++) {
                    rows.getChildren().add(row(t, r));
                }
                yield rows;
            }
            case Image i -> text("Image: " + i.target(), FontWeight.NORMAL, FontPosture.ITALIC, 0.85, Insets.EMPTY);
            case Container c -> {
                VBox box = new VBox(base * 0.3);
                if (!c.title().isEmpty()) {
                    box.getChildren().add(blockTitle(c.title(), 0));
                }
                for (Block inner : c.blocks()) {
                    box.getChildren().add(node(inner));
                }
                yield box;
            }
        };
    }

    /** One table row as a grid of cells, the columns at their relative widths, a header row bold on its tint. */
    private GridPane row(Table t, int r) {
        boolean header = r < t.headerRows();
        int columns = Math.max(1, t.columns());
        int total = 0;
        for (int c = 0; c < columns; c++) {
            total += c < t.widths().size() ? t.widths().get(c) : 1;
        }
        GridPane row = new GridPane();
        row.setMaxWidth(Double.MAX_VALUE);
        row.setStyle("-fx-background-color: " + (header ? TINT_HEADER_CELL : "transparent")
                + "; -fx-border-color: #cfcbc4; -fx-border-width: 0 0 0.5 0;");
        List<String> cells = t.rows().get(r);
        for (int c = 0; c < columns; c++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100.0 * (c < t.widths().size() ? t.widths().get(c) : 1) / total);
            constraints.setHgrow(Priority.ALWAYS);
            constraints.setFillWidth(true);
            row.getColumnConstraints().add(constraints);
            Text text = new Text(c < cells.size() ? cells.get(c) : "");
            text.setFont(font(header ? FontWeight.BOLD : FontWeight.NORMAL, FontPosture.REGULAR, 0.9));
            TextFlow cell = new TextFlow(text);
            cell.setPadding(new Insets(base * 0.3, base * 0.5, base * 0.3, base * 0.5));
            cell.setMaxWidth(Double.MAX_VALUE);
            row.add(cell, c, 0);
        }
        return row;
    }

    private Node paragraph(String text, int depth) {
        Text body = new Text(text);
        body.setFont(font(FontWeight.NORMAL, FontPosture.REGULAR, 1));
        TextFlow flow = new TextFlow(body);
        flow.setLineSpacing(base * 0.25);
        flow.setPadding(new Insets(base * 0.2, 0, base * 0.5, depth * base * 1.5));
        flow.setMaxWidth(Double.MAX_VALUE);
        return flow;
    }

    private Node blockTitle(String title, int depth) {
        return text(title, FontWeight.BOLD, FontPosture.REGULAR, 0.95, new Insets(base * 0.6, 0, base * 0.25, depth * base * 1.5));
    }

    private Node verbatim(String content) {
        Text body = new Text(content);
        body.setFont(Font.font("Menlo", base * 0.85));
        TextFlow flow = new TextFlow(body);
        flow.setPadding(new Insets(base * 0.6));
        flow.setStyle("-fx-background-color: " + TINT_VERBATIM + "; -fx-background-radius: 4;");
        flow.setMaxWidth(Double.MAX_VALUE);
        return flow;
    }

    private Node text(String text, FontWeight weight, FontPosture posture, double scale, Insets padding) {
        Text t = new Text(text);
        t.setFont(font(weight, posture, scale));
        TextFlow flow = new TextFlow(t);
        flow.setPadding(padding);
        flow.setMaxWidth(Double.MAX_VALUE);
        return flow;
    }

    private static Node indented(Node node, int depth) {
        if (depth == 0) {
            return node;
        }
        VBox holder = new VBox(node);
        holder.setPadding(new Insets(0, 0, 0, depth * 18));
        holder.setFillWidth(true);
        return holder;
    }

    private static Node spacer(double height) {
        Region gap = new Region();
        gap.setMinHeight(height);
        gap.setPrefHeight(height);
        gap.setMaxHeight(height);
        return gap;
    }

    private Font font(FontWeight weight, FontPosture posture, double scale) {
        return Font.font(family, weight, posture, base * scale);
    }
}
