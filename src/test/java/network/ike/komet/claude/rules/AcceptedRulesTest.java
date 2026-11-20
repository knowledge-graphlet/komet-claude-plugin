/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.rules;

import dev.ikm.komet.framework.rulebase.RuleSource;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.KometPreferencesImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record of the rule set texts a person saved or accepted on this installation
 * (IKE-Network/ike-issues#1391), on a preferences node of the test's own: what it takes for a
 * text to count as accepted, and what the record contains.
 */
class AcceptedRulesTest {

    private static final String JAVA = "public class Rules { }";

    private KometPreferences node;
    private AcceptedRules accepted;

    @BeforeEach
    void freshNode() {
        node = KometPreferencesImpl.getConfigurationRootPreferences().node("accepted-rules-" + UUID.randomUUID());
        accepted = new AcceptedRules(() -> node);
    }

    @AfterEach
    void removeNode() {
        try {
            node.removeNode();
        } catch (Exception neverWritten) {
            // a node never flushed has no folder to delete
        }
    }

    @Test
    void nothingIsAcceptedUntilAPersonAcceptsIt() {
        assertFalse(accepted.accepted(new RuleSource(UUID.randomUUID(), "Rules", JAVA)));
    }

    @Test
    void theTextAcceptedIsAcceptedAndAnyOtherTextOfTheRuleSetIsNot() {
        UUID id = UUID.randomUUID();
        accepted.accept(new RuleSource(id, "Rules", JAVA));

        assertTrue(accepted.accepted(new RuleSource(id, "Rules", JAVA)));
        assertTrue(accepted.accepted(new RuleSource(id, "Rules under another name", JAVA)),
                "the record is of the rule set's identity and its text");
        assertFalse(accepted.accepted(new RuleSource(id, "Rules", JAVA + " ")), "one character more is another text");
        assertFalse(accepted.accepted(new RuleSource(UUID.randomUUID(), "Rules", JAVA)),
                "the same text under another identity is another rule set");
    }

    @Test
    void acceptingAnotherTextOfTheRuleSetTakesThePlaceOfTheFirst() {
        UUID id = UUID.randomUUID();
        accepted.accept(new RuleSource(id, "Rules", JAVA));
        accepted.accept(new RuleSource(id, "Rules", JAVA + "\n// edited"));

        assertTrue(accepted.accepted(new RuleSource(id, "Rules", JAVA + "\n// edited")));
        assertFalse(accepted.accepted(new RuleSource(id, "Rules", JAVA)));
    }

    @Test
    void aRuleSetForgottenIsNoLongerAccepted() {
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        accepted.accept(new RuleSource(id, "Rules", JAVA));
        accepted.accept(new RuleSource(other, "Other rules", JAVA));

        accepted.forget(id);

        assertFalse(accepted.accepted(new RuleSource(id, "Rules", JAVA)));
        assertTrue(accepted.accepted(new RuleSource(other, "Other rules", JAVA)), "the others stay");
    }

    @Test
    void theRecordContainsADigestOfTheTextUnderTheRuleSetsIdentityAndNoText() {
        UUID id = UUID.randomUUID();
        accepted.accept(new RuleSource(id, "Rules", JAVA));

        String kept = node.get(id.toString(), "");
        assertEquals(AcceptedRules.digest(JAVA), kept);
        assertEquals(64, kept.length(), "a SHA-256 in hexadecimal");
        assertFalse(kept.contains("class"));
    }
}
