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
package network.ike.komet.claude.html;

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.time.DateTimeUtil;
import dev.ikm.tinkar.coordinate.logic.PremiseType;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import network.ike.komet.claude.koncept.ConceptDefinition;
import network.ike.komet.claude.koncept.KompendiumUrls;
import network.ike.komet.claude.koncept.KonceptIdenticon;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Renders a concept as a self-contained, inline-styled HTML fragment for pasting into
 * Outlook / Apple Mail. The HTML/email medium has none of Zulip's constraints, so this
 * is where the <em>real</em> adoc Koncept badge appears — a rounded pill with the
 * LifeHash identicon and a small-caps IKE-blue label, with proper styled tables for the
 * stated/inferred definition and the STAMP history.
 *
 * <p>Identicons are embedded as {@code data:} URIs (no hosting needed). Apple Mail renders
 * these cleanly; Outlook's Word engine may strip data-URI images — a known caveat. All CSS
 * is inline (email clients drop {@code <style>} blocks). Reuses the medium-agnostic
 * {@link ConceptDefinition} model the Zulip adapter also uses.
 */
public final class HtmlKonceptRenderer {

    private static final int ICON_PX = 16;
    private static final String PILL =
            "display:inline-block;background:#e9eff6;border:1px solid #c8d6e6;border-radius:11px;"
            + "padding:1px 9px 1px 4px;white-space:nowrap;line-height:1;";
    private static final String LABEL = "color:#2a5a8a;font-weight:600;font-variant:small-caps;vertical-align:middle;";
    private static final String TH = "border:1px solid #ccc;padding:3px 8px;background:#f0f0f0;text-align:left;font-weight:600;";
    private static final String TD = "border:1px solid #ccc;padding:3px 8px;vertical-align:middle;";
    /** Tree connector line colour + width-fixed gutter cell. */
    private static final String LINE = "#c2c2c2";
    /** Kompendium URL scheme — every badge links to the concept's entry. */
    private static final KompendiumUrls KOMPENDIUM = KompendiumUrls.defaults();

    private final ViewCalculator view;
    /** Per-render data-URI cache: a concept that recurs encodes its identicon once. */
    private final Map<Integer, String> iconCache = new HashMap<>();

    public HtmlKonceptRenderer(ViewCalculator view) {
        this.view = view;
    }

    /** Renders the full concept fragment (badge, parents, definition, history). */
    public String render(int conceptNid) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:-apple-system,'Segoe UI',Helvetica,Arial,sans-serif;font-size:13px;color:#222;\">");
        sb.append("<div style=\"font-size:15px;margin-bottom:6px;\">").append(badge(conceptNid)).append("</div>");

        int[] parents = view.parentsOf(conceptNid).intStream().toArray();
        if (parents.length > 0) {
            sb.append("<div style=\"margin:6px 0;\"><b>Parents</b>");
            for (int parent : parents) {
                sb.append("<div style=\"margin:2px 0;\">").append(badge(parent)).append("</div>");
            }
            sb.append("</div>");
        }
        sb.append(definition(conceptNid));
        sb.append(definitionTrees(conceptNid));
        sb.append(history(conceptNid));
        sb.append("</div>");
        return sb.toString();
    }

    /** The adoc Koncept pill (inline identicon + small-caps IKE-blue label), linked to the
     *  concept's Kompendium entry so every concept reference is clickable in the email. */
    private String badge(int nid) {
        String pill = "<span style=\"" + PILL + "\">" + identicon(nid)
                + " <span style=\"" + LABEL + "\">" + escape(name(nid)) + "</span></span>";
        UUID uuid = firstUuid(nid);
        return uuid == null ? pill
                : "<a href=\"" + KOMPENDIUM.conceptUrl(uuid) + "\" style=\"text-decoration:none;\">" + pill + "</a>";
    }

    private static UUID firstUuid(int nid) {
        try {
            UUID[] uuids = PrimitiveData.publicId(nid).asUuidArray();
            return uuids.length > 0 ? uuids[0] : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String identicon(int nid) {
        return iconCache.computeIfAbsent(nid, n -> {
            try {
                byte[] png = KonceptIdenticon.pngAt(PrimitiveData.publicId(n).idString(), ICON_PX * 2);
                return "<img src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(png)
                        + "\" width=\"" + ICON_PX + "\" height=\"" + ICON_PX
                        + "\" style=\"vertical-align:middle;border-radius:3px;\" alt=\"\">";
            } catch (RuntimeException e) {
                return "";
            }
        });
    }

    private String definition(int conceptNid) {
        String stated = oneDefinition("Stated", conceptNid, PremiseType.STATED);
        String inferred = oneDefinition("Inferred", conceptNid, PremiseType.INFERRED);
        if (stated.isEmpty() && inferred.isEmpty()) {
            return "";
        }
        return "<div style=\"margin:6px 0;\"><b>Definition</b>" + stated + inferred + "</div>";
    }

    private String oneDefinition(String label, int conceptNid, PremiseType premise) {
        Optional<ConceptDefinition> opt;
        try {
            opt = ConceptDefinition.extract(conceptNid, view, premise);
        } catch (RuntimeException e) {
            return "";
        }
        if (opt.isEmpty()) {
            return "";
        }
        ConceptDefinition def = opt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin-top:4px;font-style:italic;color:#555;\">")
                .append(label).append(" — ").append(def.defined() ? "Defined (≡)" : "Primitive (⊑)").append("</div>");
        sb.append(tableOpen("Group", "Attribute", "Value"));
        for (int superNid : def.supertypes()) {
            sb.append(row("Is-a", "", badge(superNid)));
        }
        for (ConceptDefinition.Role role : def.ungroupedRoles()) {
            sb.append(row("·", badge(role.attributeNid()), badge(role.valueNid())));
        }
        int group = 0;
        for (List<ConceptDefinition.Role> roleGroup : def.roleGroups()) {
            group++;
            for (ConceptDefinition.Role role : roleGroup) {
                sb.append(row(String.valueOf(group), badge(role.attributeNid()), badge(role.valueNid())));
            }
        }
        return sb.append("</table>").toString();
    }

    // ---- Definition as a connector tree (├─ └─ │) — JProfiler-style, no JavaScript ----

    /** A node in the definition tree: pre-rendered HTML content + children. */
    private record Node(String html, List<Node> children) {
    }

    private String definitionTrees(int conceptNid) {
        String stated = oneTree("Stated", conceptNid, PremiseType.STATED);
        String inferred = oneTree("Inferred", conceptNid, PremiseType.INFERRED);
        if (stated.isEmpty() && inferred.isEmpty()) {
            return "";
        }
        return "<div style=\"margin:6px 0;\"><b>Definition (tree)</b>" + stated + inferred + "</div>";
    }

    private String oneTree(String label, int conceptNid, PremiseType premise) {
        Optional<Node> root = buildDefinitionTree(conceptNid, premise);
        if (root.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin-top:4px;font-style:italic;color:#555;\">").append(label).append("</div>");
        // One outer row per node; each node's gutter + content is a nested table. Connector
        // lines are CSS borders (continuous, full-cell-height — no glyph gaps), and the
        // fixed-width gutter cells align column-to-column across rows. Email editors re-flow
        // inline content but hold tables, so the layout and the lines both survive a paste.
        sb.append("<table style=\"border-collapse:collapse;\">");
        sb.append("<tr><td style=\"padding:0 0 0 1px;vertical-align:middle;\">")
                .append(root.get().html()).append("</td></tr>");
        appendTreeRows(sb, root.get().children(), new ArrayList<>());
        return sb.append("</table>").toString();
    }

    private Optional<Node> buildDefinitionTree(int conceptNid, PremiseType premise) {
        Optional<ConceptDefinition> opt;
        try {
            opt = ConceptDefinition.extract(conceptNid, view, premise);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        ConceptDefinition def = opt.get();
        List<Node> children = new ArrayList<>();
        for (int superNid : def.supertypes()) {
            children.add(new Node(inlineRow("<span style=\"color:#555;\">Is-a</span>", badge(superNid)), List.of()));
        }
        int group = 0;
        for (List<ConceptDefinition.Role> roleGroup : def.roleGroups()) {
            group++;
            List<Node> roles = new ArrayList<>();
            for (ConceptDefinition.Role role : roleGroup) {
                roles.add(new Node(inlineRow(badge(role.attributeNid()),
                        "<span style=\"color:#999;\">→</span>", badge(role.valueNid())), List.of()));
            }
            children.add(new Node("<b>Role group " + group + "</b>", roles));
        }
        for (ConceptDefinition.Role role : def.ungroupedRoles()) {
            children.add(new Node(inlineRow(badge(role.attributeNid()),
                    "<span style=\"color:#999;\">→</span>", badge(role.valueNid())), List.of()));
        }
        String root = inlineRow(badge(conceptNid), "<span style=\"color:#555;font-style:italic;\">"
                + (def.defined() ? "Defined ≡" : "Primitive ⊑") + "</span>");
        return Optional.of(new Node(root, children));
    }

    private void appendTreeRows(StringBuilder sb, List<Node> children, List<Boolean> ancestorsContinue) {
        for (int i = 0; i < children.size(); i++) {
            Node child = children.get(i);
            boolean last = i == children.size() - 1;
            sb.append("<tr><td style=\"padding:0;\"><table style=\"border-collapse:collapse;\"><tr>");
            // Ancestor levels: a full-height left border draws a continuous vertical line
            // wherever that ancestor still has siblings below; otherwise a blank gap cell.
            for (boolean cont : ancestorsContinue) {
                sb.append("<td style=\"width:14px;padding:0;")
                        .append(cont ? "border-left:1px solid " + LINE + ";" : "").append("\">&nbsp;</td>");
            }
            // Connector: ├ keeps the vertical going (full-height border) + a half-height
            // horizontal; └ is an L (half-height vertical + horizontal) and the line stops.
            if (last) {
                sb.append("<td style=\"width:14px;padding:0;vertical-align:top;\">")
                        .append("<div style=\"height:11px;border-left:1px solid ").append(LINE)
                        .append(";border-bottom:1px solid ").append(LINE).append(";\">&nbsp;</div></td>");
            } else {
                sb.append("<td style=\"width:14px;padding:0;vertical-align:top;border-left:1px solid ").append(LINE)
                        .append(";\">").append("<div style=\"height:11px;border-bottom:1px solid ").append(LINE)
                        .append(";\">&nbsp;</div></td>");
            }
            sb.append("<td style=\"vertical-align:middle;padding:0 0 0 4px;\">").append(child.html()).append("</td>");
            sb.append("</tr></table></td></tr>");
            if (!child.children().isEmpty()) {
                List<Boolean> next = new ArrayList<>(ancestorsContinue);
                next.add(!last);
                appendTreeRows(sb, child.children(), next);
            }
        }
    }

    /** A one-row nested table so a node's parts (badge, →, badge) stay on one line in email. */
    private static String inlineRow(String... cells) {
        StringBuilder sb = new StringBuilder("<table style=\"border-collapse:collapse;\"><tr>");
        for (String cell : cells) {
            sb.append("<td style=\"padding:1px 3px 1px 0;vertical-align:middle;\">").append(cell).append("</td>");
        }
        return sb.append("</tr></table>").toString();
    }

    private String history(int conceptNid) {
        try {
            var optEntity = EntityHandle.get(conceptNid).entity();
            if (optEntity.isEmpty()) {
                return "";
            }
            List<EntityVersion> versions = new ArrayList<>();
            for (EntityVersion version : optEntity.get().versions()) {
                versions.add(version);
            }
            if (versions.isEmpty()) {
                return "";
            }
            versions.sort((a, b) -> Long.compare(b.stamp().time(), a.stamp().time()));
            StringBuilder sb = new StringBuilder("<div style=\"margin:6px 0;\"><b>History</b>");
            sb.append(tableOpen("Status · Time", "Author", "Module", "Path"));
            for (EntityVersion version : versions) {
                StampEntity<?> stamp = version.stamp();
                sb.append(row(escape(stamp.state() + " · " + DateTimeUtil.format(stamp.time())),
                        badge(stamp.authorNid()), badge(stamp.moduleNid()), badge(stamp.pathNid())));
            }
            return sb.append("</table></div>").toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String tableOpen(String... headers) {
        StringBuilder sb = new StringBuilder("<table style=\"border-collapse:collapse;margin:4px 0;\"><tr>");
        for (String header : headers) {
            sb.append("<th style=\"").append(TH).append("\">").append(escape(header)).append("</th>");
        }
        return sb.append("</tr>").toString();
    }

    private String row(String... cells) {
        StringBuilder sb = new StringBuilder("<tr>");
        for (String cell : cells) {
            sb.append("<td style=\"").append(TD).append("\">").append(cell).append("</td>");
        }
        return sb.append("</tr>").toString();
    }

    private String name(int nid) {
        return view.getPreferredDescriptionTextWithFallbackOrNid(nid);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
