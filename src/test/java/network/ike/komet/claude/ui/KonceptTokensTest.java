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

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compose-side {@code k:} token serialization — pure string tests, plus the round-trip
 * guarantee: what {@link KonceptTokens} emits, {@link ConceptChipInlineDecorator}'s grammar parses.
 */
class KonceptTokensTest {

    private static final String UUID_A = "e07f8c60-1234-1234-1234-1234567890ab";

    @Test
    void tokenSerializesTheTightGrammar() {
        assertEquals("k:uuid=" + UUID_A + "[Chronic disease]",
                KonceptTokens.token("uuid", UUID_A, "Chronic disease"));
        assertEquals("k:nid=-42", KonceptTokens.token("nid", "-42", null));
        assertEquals("k:sctid=73211009", KonceptTokens.token("sctid", "73211009", "   "));
    }

    @Test
    void labelsNeverSmuggleBrackets() {
        assertEquals("k:uuid=" + UUID_A + "[Body mass index observable entity]",
                KonceptTokens.token("uuid", UUID_A, "Body mass [index] observable entity"));
    }

    @Test
    void displayProjectsTokensAsTheirLabels() {
        String text = "Compare k:uuid=" + UUID_A + "[Chronic disease] with k:sctid=73211009[Diabetes mellitus].";
        assertEquals("Compare Chronic disease with Diabetes mellitus.", KonceptTokens.display(text));
        assertEquals("evaluate for tests", KonceptTokens.display("evaluate k:nid=-42 for tests"),
                "an unlabelled token elides rather than reading as noise");
        assertEquals("", KonceptTokens.display(null));
    }

    @Test
    void emittedTokensRoundTripThroughTheInlineGrammar() {
        String token = KonceptTokens.token("uuid", UUID_A, "Chronic disease (disorder)");
        Matcher m = ConceptChipInlineDecorator.TOKEN.matcher(token);
        assertTrue(m.find() && m.start() == 0 && m.end() == token.length(),
                "the compose form parses whole as the read form");
        assertEquals("uuid", m.group("kind"));
        assertEquals(UUID_A, m.group("kid"));
        assertEquals("Chronic disease (disorder)", m.group("klabel"));
    }

}
