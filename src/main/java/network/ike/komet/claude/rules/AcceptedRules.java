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
import dev.ikm.komet.preferences.PreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.prefs.BackingStoreException;

/**
 * The rule set texts a person saved or accepted on this installation
 * (IKE-Network/ike-issues#1391).
 *
 * <p>Rule text is Java that runs inside Komet, so a rule set the knowledge base keeps is put in
 * use when Komet starts only if a person saved or accepted that very text here. The record of
 * that is kept outside the knowledge base, in the preferences of the person's account on this
 * computer, so a knowledge base that was copied or received cannot vouch for the rule text it
 * contains. Any other rule text is listed as a proposal and waits for the person's Accept.
 *
 * <p>The record has one entry for each rule set: a digest of the text accepted, under the rule
 * set's identity. A text that has changed since, however it changed, is not the text accepted.
 * When the preferences cannot be read or written, nothing counts as accepted, which errs toward
 * asking.
 */
public final class AcceptedRules {

    private static final Logger LOG = LoggerFactory.getLogger(AcceptedRules.class);

    /** The preferences node the record is kept in, under the person's own preferences. */
    static final String NODE = "rule-sets-accepted-here";

    private final Supplier<KometPreferences> record;

    /**
     * @param record the preferences node the record is kept in, asked for at each use
     */
    public AcceptedRules(Supplier<KometPreferences> record) {
        this.record = record;
    }

    /**
     * @return the record of this installation: the preferences of the person's account on this
     *         computer, which apply to every knowledge base they open here
     */
    public static AcceptedRules ofThisInstallation() {
        return new AcceptedRules(() -> PreferencesService.userPreferences().node(NODE));
    }

    /**
     * @param source a rule set: its identity and its text
     * @return whether a person saved or accepted this very text of the rule set here
     */
    public boolean accepted(RuleSource source) {
        try {
            return digest(source.java()).equals(record.get().get(source.id().toString(), ""));
        } catch (RuntimeException failure) {
            LOG.warn("The record of rule sets accepted here could not be read, so \"{}\" waits for Accept",
                    source.name(), failure);
            return false;
        }
    }

    /**
     * Records that a person saved or accepted this text of the rule set here, in place of the
     * text recorded for it before.
     *
     * @param source the rule set: its identity and its text
     */
    public void accept(RuleSource source) {
        try {
            KometPreferences node = record.get();
            node.put(source.id().toString(), digest(source.java()));
            node.flush();
        } catch (RuntimeException | BackingStoreException failure) {
            LOG.warn("The record of rule sets accepted here could not be written, so \"{}\" will wait for"
                    + " Accept when Komet next starts", source.name(), failure);
        }
    }

    /**
     * Forgets a rule set: what a person removed is no longer accepted here.
     *
     * @param id the rule set's identity
     */
    public void forget(UUID id) {
        try {
            KometPreferences node = record.get();
            node.remove(id.toString());
            node.flush();
        } catch (RuntimeException | BackingStoreException failure) {
            LOG.warn("The record of rule sets accepted here could not be written; rule set {} stays in it", id,
                    failure);
        }
    }

    /** The SHA-256 of a text, in hexadecimal: short enough for a preference, and the text's alone. */
    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }
}
