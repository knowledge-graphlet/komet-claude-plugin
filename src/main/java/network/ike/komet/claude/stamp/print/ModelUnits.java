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
import network.ike.komet.claude.stamp.print.LayoutOracle.Kind;
import network.ike.komet.claude.stamp.print.LayoutOracle.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * The layout oracles' units straight from a print model, without the Komet store or the
 * surface: what the layout fidelity harness lays out. The units mirror the ones the
 * stamped document surface builds from its print areas — one per paragraph-like piece, the
 * title and every top-level block numbered as the Typst writer labels them, the same kinds
 * and the same spacing rules — with the model's own text, which is what the Typst writer
 * writes, so the oracle aligns Typst's words to it directly.
 */
public final class ModelUnits {

    private ModelUnits() { }

    /**
     * The units of a model.
     *
     * @param model the print model
     * @param base  the body size in points
     * @return the units in document order
     */
    public static List<Unit> of(DocumentModel model, double base) {
        List<Unit> out = new ArrayList<>();
        int block = 0;
        Kind previous = null;
        if (!model.title().isEmpty()) {
            previous = add(out, block++, Kind.TITLE, model.title(), base * 1.8, true, 0, previous, base);
        }
        for (Block top : model.blocks()) {
            previous = block(out, block++, top, base, previous, 0);
        }
        return out;
    }

    /** Typst's list markers by nesting depth, cycling: {@code list.marker} defaults to ([•], [‣], [–]). */
    public static String bullet(int depth) {
        return switch (depth % 3) {
            case 0 -> "•";
            case 1 -> "‣";
            default -> "–";
        };
    }

    private static Kind block(List<Unit> out, int block, Block b, double base, Kind previous, int depth) {
        switch (b) {
            case Heading h -> {
                double scale = switch (Math.max(1, h.level())) {
                    case 1 -> 1.5;
                    case 2 -> 1.25;
                    case 3 -> 1.1;
                    default -> 1.0;
                };
                return add(out, block, Kind.HEADING, h.text(), base * scale, true, 0, previous, base);
            }
            case Paragraph p -> {
                return add(out, block, Kind.PARAGRAPH, p.text(), base, false, 0, previous, base);
            }
            case Admonition a -> {
                Kind last = previous;
                boolean first = true;
                for (Block inner : a.blocks()) {
                    if (first && inner instanceof Paragraph p) {
                        last = add(out, block, Kind.PARAGRAPH, a.label() + " — " + p.text(), base, false, 0, last, base);
                    } else {
                        last = block(out, block, inner, base, last, depth);
                    }
                    first = false;
                }
                return last;
            }
            case Verbatim v -> {
                Kind last = previous;
                if (!v.title().isEmpty()) {
                    last = add(out, block, Kind.OTHER, v.title(), base * 0.95, true, 0, last, base);
                }
                return add(out, block, Kind.VERBATIM, v.text(), base * 0.85, false, 0, last, base);
            }
            case ListBlock l -> {
                Kind last = previous;
                if (!l.title().isEmpty()) {
                    last = add(out, block, Kind.OTHER, l.title(), base * 0.95, true, 0, last, base);
                }
                int n = 0;
                for (Item item : l.items()) {
                    n++;
                    String marker = switch (l.kind()) {
                        case ORDERED -> n + ".";
                        case DESCRIPTION -> item.term() + ":";
                        case UNORDERED, BIBLIOGRAPHY -> bullet(depth);
                    };
                    last = add(out, block, Kind.LIST_ITEM, marker + " " + item.text(), base, false, base * (1 + depth), last, base);
                    for (Block inner : item.blocks()) {
                        last = block(out, block, inner, base, last, depth + 1);
                    }
                }
                return last;
            }
            case Table t -> {
                Kind last = previous;
                if (!t.title().isEmpty()) {
                    last = add(out, block, Kind.OTHER, t.title(), base, true, 0, last, base, true);
                }
                int r = 0;
                for (List<String> row : t.rows()) {
                    boolean header = r++ < t.headerRows();
                    last = add(out, block, Kind.TABLE_ROW, String.join("  │  ", row), base, header, 0, last, base);
                }
                return last;
            }
            case Image i -> {
                return add(out, block, Kind.OTHER, "Image: " + i.target() + (i.title().isEmpty() ? "" : " — " + i.title()), base * 0.85, false, 0, previous, base);
            }
            case Container c -> {
                Kind last = previous;
                if (!c.title().isEmpty()) {
                    last = add(out, block, Kind.OTHER, c.title(), base * 0.9, true, 0, last, base);
                }
                for (Block inner : c.blocks()) {
                    last = block(out, block, inner, base, last, depth);
                }
                return last;
            }
        }
    }

    private static Kind add(List<Unit> out, int block, Kind kind, String text, double size, boolean bold, double indent, Kind previous, double base) {
        return add(out, block, kind, text, size, bold, indent, previous, base, false);
    }

    private static Kind add(List<Unit> out, int block, Kind kind, String text, double size, boolean bold, double indent, Kind previous, double base,
                            boolean caption) {
        double[] spacing = UnitSpacing.of(kind, previous, size, base, caption);
        out.add(new Unit(out.size(), block, kind, text, size, bold, indent, spacing[0], spacing[1], kind == Kind.HEADING));
        return kind;
    }
}
