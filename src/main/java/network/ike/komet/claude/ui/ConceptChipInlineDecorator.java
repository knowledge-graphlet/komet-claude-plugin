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

import dev.ikm.komet.markdown.richtext.InlineDecorator;
import dev.ikm.komet.markdown.richtext.InlinePiece;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The assistant's {@link InlineDecorator}: in every rendered text run it detects component
 * identifiers — an SCTID, a UUID, or a {@code nid=…} — and follows each existence-gated one
 * with a <em>concept chip</em> styled like the AsciiDoc {@code k:} Koncept chip: the
 * component's LifeHash {@link Identicon} on the left and its store-resolved name in small-caps
 * inside a soft rounded pill, with the full identity (name, SCTID, UUID, nid) on hover.
 *
 * <p>Detection is format-agnostic (the model routinely drops brackets and reshapes ids into
 * tables / inline code) and existence-gated against the live store, so only real components
 * get a chip. When the component's latest version is inactive/retired in the view, the chip's
 * name is struck through — the dedicated "inactive" signal (#586). The badge and name are
 * deterministic functions of the {@link PublicId}, so a fabricated id would not match what
 * Komet displays.
 */
final class ConceptChipInlineDecorator implements InlineDecorator {

    /**
     * Matches component identifiers in whatever shape the model reproduces them. Two families:
     *
     * <ul>
     *   <li><b>Id-bearing {@code k:} interchange tokens</b> — {@code k:uuid=<id>[Label]},
     *       {@code k:sctid=…}, {@code k:nid=…}, {@code k:id=…}, label optional — the deliberate
     *       interchange form the compose surface emits (#737) and the koncept-tree block uses per
     *       line. The inline grammar is <em>tight</em> (no whitespace inside the token, and the
     *       label bracket attaches directly to the id) so ordinary prose brackets after a token
     *       are never swallowed as a label; the block renderer keeps its lenient line grammar.</li>
     *   <li><b>Bare identifiers</b> — a UUID, {@code nid=…}, or an SCTID-like number (6-18
     *       digits), brackets optional, however the model reshapes them.</li>
     * </ul>
     *
     * Every quantifier runs over a disjoint character class, so the pattern cannot backtrack
     * catastrophically. Permissive matching is safe because every candidate is existence-gated in
     * {@link #resolve}. Package-visible for the store-free grammar tests.
     */
    static final Pattern TOKEN = Pattern.compile(
            "k:(?<kind>sctid|uuid|nid|id)=(?<kid>[0-9a-fA-F-]+)(?:\\[(?<klabel>[^\\[\\]]*)])?"
                    + "|(?<uuid>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
                    + "|nid=(?<nid>-?\\d+)"
                    + "|\\b(?<sctid>\\d{6,18})\\b");

    /** The view used to resolve concept names for chips; may be null (icon-only fallback). */
    private final ViewCalculator viewCalc;
    /** Base body font size (px); the chip label and identicon scale from it. */
    private final double base;

    ConceptChipInlineDecorator(ViewCalculator viewCalc, double base) {
        this.viewCalc = viewCalc;
        this.base = base;
    }

    /**
     * Decomposes {@code text} into pieces. A <b>bare identifier</b> stays visible (the digits the
     * user asked for remain in the text) and, when resolved, is followed by its concept chip. An
     * <b>id-bearing {@code k:} token</b> is deliberate interchange, not a quoted identifier: when
     * resolved, the whole token renders as just its chip — which carries the store-resolved name,
     * never the token's possibly stale {@code [label]} — and the chip's plain-text projection is
     * the original token, so copying rendered prose round-trips as interchange. An unresolvable
     * token of either family stays literal text. Returning pieces (rather than writing into a
     * paragraph builder) is what lets the chips render inside table cells as well as in flowing
     * text.
     */
    @Override
    public List<InlinePiece> decorate(String text, StyleAttributeMap style) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<InlinePiece> pieces = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        int last = 0;
        while (m.find()) {
            PublicId id = resolve(m);
            if (m.group("kind") != null) {
                // A k: interchange token. Resolved: the chip replaces the token machinery text.
                // Unresolvable: no pieces are emitted and `last` stays put, so the literal token is
                // flushed with the following text — and because the match consumed the whole token,
                // an id embedded in it is never separately chipped.
                if (id != null) {
                    addText(pieces, text.substring(last, m.start()), style);
                    String sctid = "sctid".equals(m.group("kind")) ? m.group("kid") : null;
                    String token = m.group();
                    pieces.add(new InlinePiece.NodeRun(() -> conceptChip(id, sctid), token));
                    last = m.end();
                }
            } else {
                // Text up to and including the identifier (the id stays visible).
                addText(pieces, text.substring(last, m.end()), style);
                if (id != null) {
                    String sctid = m.group("sctid");
                    // The id itself is already in the preceding text piece, so the chip's plain-text
                    // projection is empty to avoid duplicating it on copy.
                    pieces.add(new InlinePiece.NodeRun(() -> conceptChip(id, sctid), ""));
                }
                last = m.end();
            }
        }
        if (last < text.length()) {
            addText(pieces, text.substring(last), style);
        }
        return pieces;
    }

    private static void addText(List<InlinePiece> pieces, String text, StyleAttributeMap style) {
        if (!text.isEmpty()) {
            pieces.add(new InlinePiece.TextRun(text, style));
        }
    }

    /**
     * Resolves a matched token to a {@link PublicId} that actually exists in the store, or
     * null. The {@code hasPublicId} gate is what makes bare-number matching safe.
     */
    private static PublicId resolve(Matcher m) {
        try {
            PublicId pid;
            if (m.group("kind") != null) {
                pid = interchangeId(m.group("kind"), m.group("kid"));
            } else if (m.group("uuid") != null) {
                pid = PublicIds.of(UUID.fromString(m.group("uuid")));
            } else if (m.group("nid") != null) {
                pid = PrimitiveData.publicId(Integer.parseInt(m.group("nid")));
            } else if (m.group("sctid") != null) {
                pid = PublicIds.of(UuidUtil.fromSNOMED(m.group("sctid")));
            } else {
                pid = null;
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
     * The id of a {@code k:} interchange token: the kind names the id space. The generic
     * {@code id} kind (mirroring the koncept-tree line grammar) accepts a UUID first, then an
     * SCTID-shaped number.
     */
    private static PublicId interchangeId(String kind, String value) {
        return switch (kind) {
            case "uuid" -> PublicIds.of(UUID.fromString(value));
            case "sctid" -> PublicIds.of(UuidUtil.fromSNOMED(value));
            case "nid" -> PrimitiveData.publicId(Integer.parseInt(value));
            case "id" -> genericId(value);
            default -> null;
        };
    }

    /** A {@code k:id=…} value: a UUID when it parses as one, otherwise an SCTID-shaped number. */
    private static PublicId genericId(String value) {
        try {
            return PublicIds.of(UUID.fromString(value));
        } catch (IllegalArgumentException notAUuid) {
            return PublicIds.of(UuidUtil.fromSNOMED(value));
        }
    }

    /**
     * Builds the inline concept chip via the shared {@link KonceptChips} factory: identicon (left)
     * + store-resolved name in small-caps, in a soft rounded pill, with the full grounded identity
     * on hover; the name is struck through when the component is inactive.
     *
     * @param pid   the resolved, existing component id
     * @param sctid the matched SCTID for the tooltip, or null if matched by UUID/nid
     */
    private javafx.scene.Node conceptChip(PublicId pid, String sctid) {
        return KonceptChips.chip(pid, sctid, viewCalc, base);
    }
}
