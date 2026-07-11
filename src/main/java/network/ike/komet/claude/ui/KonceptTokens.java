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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serialization of id-bearing {@code k:} interchange tokens for the compose surface: the
 * edit-projection counterpart of {@link ConceptChipInlineDecorator}'s read projection — prose
 * <em>edits as {@code k:} tokens and reads as chips</em>. Emits exactly the tight inline grammar
 * the decorator parses, so a composed message re-chips verbatim in the echoed turn. This is the
 * send-extraction serializer for the live inline-chip input ({@code IKE-Network/ike-issues#789}):
 * walking the compose model's segments, each chip serializes through {@link #token}.
 *
 * <p>Pure string functions, deliberately: serialization is testable without a store or a toolkit.
 * Interim home — the canonical token (parse/serialize + adoc macro) is
 * {@code IKE-Network/ike-issues#735}'s remit in koncept-core; this class carries only what the
 * compose surface needs and can retire onto it.
 */
public final class KonceptTokens {

    private KonceptTokens() {
    }

    /**
     * One id-bearing token, e.g. {@code k:uuid=e07f…[Chronic disease]}. The label is display text
     * for the reader; identity travels in the id, and the rendered chip always shows the
     * store-resolved name regardless of the label.
     *
     * @param kind  the id space: {@code uuid}, {@code sctid}, or {@code nid}
     * @param id    the identifier in that space
     * @param label the display label; brackets are stripped (the grammar reserves them), and a
     *              blank label omits the bracket segment entirely
     * @return the serialized token
     */
    public static String token(String kind, String id, String label) {
        String clean = label == null ? "" : label.replace("[", "").replace("]", "").trim();
        return clean.isEmpty()
                ? "k:" + kind + "=" + id
                : "k:" + kind + "=" + id + "[" + clean + "]";
    }

    /** The tight inline token grammar, for the display projection (kept in step with the decorator). */
    private static final Pattern TOKEN =
            Pattern.compile("k:(?:sctid|uuid|nid|id)=[0-9a-fA-F-]+(?:\\[([^\\[\\]]*)])?");

    /**
     * The human-display projection of token text: each {@code k:} token replaced by its label (or
     * elided when unlabelled) — for places that show the message as a name, like the conversation
     * rail, where a raw truncated token would read as noise.
     *
     * @param tokenText prose carrying {@code k:} tokens; may be null
     * @return the prose with tokens replaced by their labels, whitespace collapsed
     */
    public static String display(String tokenText) {
        if (tokenText == null || tokenText.isEmpty()) {
            return "";
        }
        Matcher m = TOKEN.matcher(tokenText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String label = m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement(label == null ? "" : label));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
