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

import dev.ikm.komet.framework.rulebase.ListedRuleSet;
import dev.ikm.komet.framework.rulebase.RuleService;
import dev.ikm.tinkar.common.service.LifecyclePhase;
import dev.ikm.tinkar.common.service.ServiceLifecycle;
import dev.ikm.tinkar.common.service.ServiceLifecyclePhase;
import dev.ikm.tinkar.coordinate.Calculators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Gives the rule service the rule sets the knowledge base keeps when Komet starts, once the
 * knowledge base is open (IKE-Network/ike-issues#1391), whether or not a Rules card is ever
 * opened.
 *
 * <p>A rule set whose text a person saved or accepted on this installation is put in use
 * ({@link AcceptedRules}). Any other is proposed: it is listed on the Rules card and none of its
 * code runs until a person accepts it there, so a knowledge base that came from elsewhere does
 * not run its rule text by being opened.
 *
 * <p>A knowledge base that lacks the rule source pattern is left alone, and the rule service is
 * asked for only when the knowledge base keeps a rule set, so a knowledge base with none starts as
 * it did before. A rule set that no longer compiles is listed out of use
 * with its findings, where the Rules card shows it, and Komet starts all the same.
 */
@LifecyclePhase(value = ServiceLifecyclePhase.APPLICATION_SERVICES, subPriority = 60)
public class StoredRulesLifecycle implements ServiceLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(StoredRulesLifecycle.class);

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public StoredRulesLifecycle() {
    }

    @Override
    public void startup() {
        // ServiceLifecycle contract: a thrown exception stops Komet from starting. Rules written
        // in Komet are not needed to start, so a failure here is logged and Komet goes on.
        try {
            if (!StoredRules.patternPresent()) {
                return;
            }
            List<ListedRuleSet> loaded = StoredRules.loadInto(RuleService::get, Calculators.View.Default(),
                    AcceptedRules.ofThisInstallation());
            if (!loaded.isEmpty()) {
                long inUse = loaded.stream().filter(ListedRuleSet::inUse).count();
                long waiting = loaded.stream().filter(ruleSet -> !ruleSet.accepted()).count();
                LOG.info("Loaded {} rule set(s) from the knowledge base; {} in use, {} waiting for Accept"
                                + " (not accepted here), {} out of use with findings",
                        loaded.size(), inUse, waiting, loaded.size() - inUse - waiting);
            }
        } catch (Throwable failure) {
            LOG.error("The rule sets the knowledge base keeps could not be loaded; Komet runs without them", failure);
        }
    }

    @Override
    public void shutdown() {
        // Nothing to release: the rule service holds the rules.
    }
}
