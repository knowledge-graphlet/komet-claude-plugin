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

import dev.ikm.komet.framework.Identicon;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.StyleResolver;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;
import jfx.incubator.scene.control.richtext.model.StyledTextModelViewOnlyBase;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a view-only {@link StyledTextModel} for the assistant transcript: each
 * {@link Entry} contributes a coloured role label plus its content, with
 * Markdown rendered to styled {@link RichParagraph} runs.
 *
 * <p>The distinctive behaviour: any concept identifier the assistant reports — an
 * SCTID, a UUID, or a {@code nid=…} — is followed by a <em>concept chip</em>
 * styled like the AsciiDoc {@code k:} Koncept chip: the component's LifeHash
 * {@link Identicon} on the left and its store-resolved name in small-caps inside
 * a soft rounded pill, with the full identity (name, SCTID, UUID, nid) on hover.
 * Detection is format-agnostic (the model routinely drops brackets and reshapes
 * ids into tables / inline code) and existence-gated against the live store, so
 * only real components get a chip. This grounds the answer visually: the badge
 * and name are deterministic functions of the component's {@code PublicId}, so a
 * fabricated id would not match what Komet displays.
 */
public final class MarkdownRichText {

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    private static final double BASE = 13;
    private static final String MONO = "monospace";
    /** Inline identicon edge length, in pixels. */
    private static final int ICON_PX = 15;

    /** Adoc Koncept chip palette (koncept.css): soft pill + IKE-blue small-caps label. */
    private static final String CHIP_STYLE =
            "-fx-background-color: #e9eff6; -fx-background-radius: 6; -fx-padding: 1 5 1 4;";
    private static final String CHIP_LABEL_STYLE =
            "-fx-text-fill: #2a5a8a; -fx-font-size: 10.5px; -fx-padding: 0;";

    /**
     * Matches component identifiers in whatever shape the model reproduces them —
     * brackets are optional and the LLM frequently drops them or splits ids into
     * table cells / inline code. Group 1 = UUID, group 2 = nid, group 3 = a bare
     * SCTID-like number (6-18 digits). Permissive matching is safe because every
     * candidate is existence-gated in {@link #resolve}: a number that is not a
     * real component fails the gate and gets no chip.
     */
    private static final Pattern TOKEN = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
                    + "|nid=(-?\\d+)"
                    + "|\\b(\\d{6,18})\\b");

    /** A transcript role, with its label and accent colour. */
    public enum Role {
        USER("You", Color.web("#1a56db")),
        ASSISTANT("Komet Assistant", Color.web("#b15c00")),
        ERROR("Error", Color.web("#b00020"));

        final String label;
        final Color color;

        Role(String label, Color color) {
            this.label = label;
            this.color = color;
        }
    }

    /**
     * One transcript message.
     *
     * @param role     who is speaking
     * @param content  the message text (Markdown when {@code markdown} is true)
     * @param markdown whether to render {@code content} as Markdown
     */
    public record Entry(Role role, String content, boolean markdown) {
    }

    /** The view used to resolve concept names for chips; may be null (icon-only fallback). */
    private final ViewCalculator viewCalc;

    /**
     * @param viewCalc the live view for resolving concept names; if null, chips
     *                 fall back to a bare identicon
     */
    public MarkdownRichText(ViewCalculator viewCalc) {
        this.viewCalc = viewCalc;
    }

    /**
     * Builds the view-only model for the whole transcript.
     *
     * @param entries the conversation, in order
     * @return a model suitable for {@code RichTextArea.setModel(...)}
     */
    public StyledTextModel toModel(List<Entry> entries) {
        List<RichParagraph> paragraphs = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        for (Entry e : entries) {
            // Coloured, bold role label on its own line.
            RichParagraph.Builder label = RichParagraph.builder();
            label.addSegment(e.role().label,
                    StyleAttributeMap.builder().setBold(true).setFontSize(BASE).setTextColor(e.role().color).build());
            paragraphs.add(label.build());
            plain.add(e.role().label);

            if (e.markdown()) {
                Node document = PARSER.parse(e.content() == null ? "" : e.content());
                for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
                    renderBlock(block, paragraphs, plain, 0, baseStyle(e.role()));
                }
            } else {
                StyleAttributeMap style = baseStyle(e.role());
                for (String line : (e.content() == null ? "" : e.content()).split("\n", -1)) {
                    RichParagraph.Builder b = RichParagraph.builder();
                    addText(b, line, style);
                    paragraphs.add(b.build());
                    plain.add(line);
                }
            }
            // Blank spacer between messages.
            paragraphs.add(RichParagraph.builder().build());
            plain.add("");
        }
        if (paragraphs.isEmpty()) {
            paragraphs.add(RichParagraph.builder().build());
            plain.add("");
        }
        return new Model(List.copyOf(paragraphs), List.copyOf(plain));
    }

    private static StyleAttributeMap baseStyle(Role role) {
        StyleAttributeMap.Builder b = StyleAttributeMap.builder().setFontSize(BASE);
        if (role == Role.ERROR) {
            b.setItalic(true).setTextColor(Role.ERROR.color);
        }
        return b.build();
    }

    // ---- Block rendering ---------------------------------------------------

    private void renderBlock(Node block, List<RichParagraph> out, List<String> plain,
                             int listDepth, StyleAttributeMap base) {
        switch (block) {
            case Heading h -> {
                RichParagraph.Builder b = RichParagraph.builder();
                StyleAttributeMap hs = base.combine(
                        StyleAttributeMap.builder().setBold(true).setFontSize(headingSize(h.getLevel())).build());
                appendInlines(b, h.getFirstChild(), hs);
                emit(b, plainOf(h), out, plain);
            }
            case Paragraph p -> {
                RichParagraph.Builder b = RichParagraph.builder();
                appendInlines(b, p.getFirstChild(), base);
                emit(b, plainOf(p), out, plain);
            }
            case BlockQuote q -> {
                StyleAttributeMap qs = base.combine(StyleAttributeMap.builder().setItalic(true).build());
                for (Node c = q.getFirstChild(); c != null; c = c.getNext()) {
                    renderBlock(c, out, plain, listDepth, qs);
                }
            }
            case FencedCodeBlock fc -> emitCodeBlock(fc.getLiteral(), out, plain);
            case IndentedCodeBlock ic -> emitCodeBlock(ic.getLiteral(), out, plain);
            case BulletList bl -> {
                for (Node item = bl.getFirstChild(); item != null; item = item.getNext()) {
                    renderListItem(item, "•  ", out, plain, listDepth, base);
                }
            }
            case OrderedList ol -> {
                int n = 1;
                for (Node item = ol.getFirstChild(); item != null; item = item.getNext()) {
                    renderListItem(item, (n++) + ".  ", out, plain, listDepth, base);
                }
            }
            case ThematicBreak ignored -> emit(RichParagraph.builder().addSegment("──────────", base),
                    "──────────", out, plain);
            case TableBlock tb -> renderTable(tb, out, plain, base);
            default -> {
                String text = plainOf(block);
                if (!text.isBlank()) {
                    RichParagraph.Builder b = RichParagraph.builder();
                    addText(b, text, base);
                    emit(b, text, out, plain);
                }
            }
        }
    }

    private void renderListItem(Node item, String prefix, List<RichParagraph> out, List<String> plain,
                                int listDepth, StyleAttributeMap base) {
        if (!(item instanceof ListItem)) {
            renderBlock(item, out, plain, listDepth, base);
            return;
        }
        String indent = "  ".repeat(Math.max(0, listDepth));
        boolean first = true;
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Paragraph p) {
                RichParagraph.Builder b = RichParagraph.builder();
                addText(b, indent + (first ? prefix : "   "), base);
                appendInlines(b, p.getFirstChild(), base);
                emit(b, indent + (first ? prefix : "   ") + plainOf(p), out, plain);
                first = false;
            } else {
                renderBlock(child, out, plain, listDepth + 1, base);
            }
        }
    }

    private void renderTable(TableBlock tb, List<RichParagraph> out, List<String> plain,
                             StyleAttributeMap base) {
        for (Node section = tb.getFirstChild(); section != null; section = section.getNext()) {
            boolean header = section instanceof org.commonmark.ext.gfm.tables.TableHead;
            StyleAttributeMap rowStyle = header
                    ? base.combine(StyleAttributeMap.builder().setBold(true).build())
                    : base;
            for (Node rowNode = section.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
                if (!(rowNode instanceof TableRow row)) {
                    continue;
                }
                RichParagraph.Builder b = RichParagraph.builder();
                StringBuilder p = new StringBuilder();
                boolean firstCell = true;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (!(cell instanceof TableCell)) {
                        continue;
                    }
                    if (!firstCell) {
                        b.addSegment("  |  ", base);
                        p.append("  |  ");
                    }
                    firstCell = false;
                    appendInlines(b, cell.getFirstChild(), rowStyle);
                    p.append(plainOf(cell));
                }
                emit(b, p.toString(), out, plain);
                if (header) {
                    emit(RichParagraph.builder().addSegment("──────────", base), "──────────", out, plain);
                }
            }
        }
    }

    private static void emitCodeBlock(String literal, List<RichParagraph> out, List<String> plain) {
        String code = literal == null ? "" : literal;
        while (code.endsWith("\n")) {
            code = code.substring(0, code.length() - 1);
        }
        StyleAttributeMap mono = StyleAttributeMap.builder().setFontFamily(MONO).setFontSize(BASE).build();
        for (String line : code.split("\n", -1)) {
            out.add(RichParagraph.builder().addSegment(line.isEmpty() ? " " : line, mono).build());
            plain.add(line);
        }
    }

    private static double headingSize(int level) {
        return switch (level) {
            case 1 -> 19;
            case 2 -> 17;
            case 3 -> 15;
            default -> 14;
        };
    }

    // ---- Inline rendering --------------------------------------------------

    private void appendInlines(RichParagraph.Builder b, Node first, StyleAttributeMap base) {
        for (Node n = first; n != null; n = n.getNext()) {
            switch (n) {
                case Text t -> addText(b, t.getLiteral(), base);
                case StrongEmphasis s -> appendInlines(b, s.getFirstChild(),
                        base.combine(StyleAttributeMap.builder().setBold(true).build()));
                case Emphasis e -> appendInlines(b, e.getFirstChild(),
                        base.combine(StyleAttributeMap.builder().setItalic(true).build()));
                case Code c -> addText(b, c.getLiteral(),
                        base.combine(StyleAttributeMap.builder().setFontFamily(MONO).build()));
                case SoftLineBreak ignored -> addText(b, " ", base);
                case HardLineBreak ignored -> addText(b, " ", base);
                case Link l -> {
                    appendInlines(b, l.getFirstChild(), base);
                    if (l.getDestination() != null && !l.getDestination().isBlank()) {
                        addText(b, " (" + l.getDestination() + ")", base);
                    }
                }
                case Image img -> addText(b, "[image"
                        + (img.getDestination() == null || img.getDestination().isBlank()
                        ? "" : ": " + img.getDestination()) + "]", base);
                default -> addText(b, plainOf(n), base);
            }
        }
    }

    /**
     * Appends {@code text}, keeping each matched identifier inline (so the digits
     * the user asked for stay visible) and following each resolved identifier with
     * its concept chip.
     */
    private void addText(RichParagraph.Builder b, String text, StyleAttributeMap style) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher m = TOKEN.matcher(text);
        int last = 0;
        while (m.find()) {
            // Text up to and including the identifier (the id stays visible).
            addSegment(b, text.substring(last, m.end()), style);
            PublicId id = resolve(m);
            if (id != null) {
                String sctid = m.group(3);
                b.addInlineNode(() -> conceptChip(id, sctid));
            }
            last = m.end();
        }
        if (last < text.length()) {
            addSegment(b, text.substring(last), style);
        }
    }

    private static void addSegment(RichParagraph.Builder b, String text, StyleAttributeMap style) {
        if (text.isEmpty()) {
            return;
        }
        if (style == null || style.isEmpty()) {
            b.addSegment(text);
        } else {
            b.addSegment(text, style);
        }
    }

    /**
     * Resolves a matched token to a {@link PublicId} that actually exists in the
     * store, or null. The {@code hasPublicId} gate is what makes bare-number
     * matching safe: a 6-18 digit number that is not a real SCTID still maps to a
     * PublicId deterministically, but fails the gate, so it gets no chip.
     */
    private static PublicId resolve(Matcher m) {
        try {
            PublicId pid = null;
            if (m.group(1) != null) {
                pid = PublicIds.of(UUID.fromString(m.group(1)));
            } else if (m.group(2) != null) {
                pid = PrimitiveData.publicId(Integer.parseInt(m.group(2)));
            } else if (m.group(3) != null) {
                pid = PublicIds.of(UuidUtil.fromSNOMED(m.group(3)));
            }
            if (pid != null && PrimitiveData.get().hasPublicId(pid)) {
                return pid;
            }
        } catch (RuntimeException ignored) {
            // Unresolvable / non-existent id — render text without a chip.
        }
        return null;
    }

    /**
     * Builds the inline concept chip: identicon (left) + store-resolved name in
     * small-caps, in a soft rounded pill, with the full grounded identity on hover.
     * Falls back to a bare identicon when the name cannot be resolved.
     *
     * @param pid   the resolved, existing component id
     * @param sctid the matched SCTID for the tooltip, or null if matched by UUID/nid
     */
    private javafx.scene.Node conceptChip(PublicId pid, String sctid) {
        try {
            int nid = PrimitiveData.nid(pid);
            ImageView icon = Identicon.generateIdenticon(pid, ICON_PX, ICON_PX);
            icon.setSmooth(false);
            String name = (viewCalc != null)
                    ? viewCalc.getFullyQualifiedNameText(nid)
                            .orElseGet(() -> viewCalc.getPreferredDescriptionTextWithFallbackOrNid(nid))
                    : null;
            if (name == null || name.isBlank()) {
                return icon;
            }
            Label label = new Label(name.toUpperCase(Locale.ROOT));
            label.setStyle(CHIP_LABEL_STYLE);
            HBox chip = new HBox(icon, label);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.setSpacing(3);
            chip.setStyle(CHIP_STYLE);

            StringBuilder tip = new StringBuilder(name);
            if (sctid != null) {
                tip.append("\nSCTID: ").append(sctid);
            }
            tip.append("\nUUID: ").append(pid.idString());
            tip.append("\nnid: ").append(nid);
            Tooltip.install(chip, new Tooltip(tip.toString()));
            return chip;
        } catch (RuntimeException e) {
            // Never let a chip failure break the transcript.
            return new javafx.scene.layout.Region();
        }
    }

    // ---- Plain-text projection (for copy / accessibility) ------------------

    private static String plainOf(Node node) {
        StringBuilder sb = new StringBuilder();
        appendPlain(node, sb);
        return sb.toString();
    }

    private static void appendPlain(Node node, StringBuilder sb) {
        for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
            switch (c) {
                case Text t -> sb.append(t.getLiteral());
                case Code code -> sb.append(code.getLiteral());
                case SoftLineBreak ignored -> sb.append(' ');
                case HardLineBreak ignored -> sb.append(' ');
                default -> appendPlain(c, sb);
            }
        }
    }

    private static void emit(RichParagraph.Builder b, String plain,
                             List<RichParagraph> out, List<String> plains) {
        out.add(b.build());
        plains.add(plain);
    }

    /** View-only model serving the pre-rendered paragraphs. */
    private static final class Model extends StyledTextModelViewOnlyBase {
        private final List<RichParagraph> paragraphs;
        private final List<String> plain;

        Model(List<RichParagraph> paragraphs, List<String> plain) {
            this.paragraphs = paragraphs;
            this.plain = plain;
        }

        @Override
        public int size() {
            return paragraphs.size();
        }

        @Override
        public String getPlainText(int index) {
            return plain.get(index);
        }

        @Override
        public RichParagraph getParagraph(int index) {
            return paragraphs.get(index);
        }

        @Override
        public StyleAttributeMap getStyleAttributeMap(StyleResolver resolver, TextPos pos) {
            return StyleAttributeMap.EMPTY;
        }
    }
}
