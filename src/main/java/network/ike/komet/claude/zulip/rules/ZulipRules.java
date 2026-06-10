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
package network.ike.komet.claude.zulip.rules;

import dev.ikm.komet.framework.performance.impl.ObservationRecord;
import dev.ikm.komet.rules.annotated.RulesBase;
import dev.ikm.tinkar.entity.EntityVersion;
import network.ike.komet.claude.html.CopyKonceptHtmlAction;
import org.evrete.dsl.annotation.MethodPredicate;
import org.evrete.dsl.annotation.Rule;
import org.evrete.dsl.annotation.RuleSet;
import org.evrete.dsl.annotation.Where;

/**
 * Plugin-contributed Evrete rules for Zulip notify-out, discovered through the
 * {@code RuleProvider} SPI ({@link ZulipRuleProvider}). The same rule engine and
 * {@code ObservationRecord} facts that drive the built-in component menus drive
 * this — and are the place worthiness and post-shaping rules will later live.
 *
 * <p>Compiled with {@code -parameters}; the package is {@code opens} to the
 * Evrete DSL module so the engine can read the rule reflectively.
 */
@RuleSet("Zulip notify-out rules")
public class ZulipRules extends RulesBase {

    /**
     * Offers "Post state + history to Zulip" whenever a component is focused.
     * A later worthiness rule can gate this; v1 always offers it (the action
     * prompts for the Zulip connection if it has not been configured).
     *
     * @see RulesBase#isComponentFocused(ObservationRecord)
     * @param $observation the focused-component observation
     */
    @Rule("Component focused — offer post to Zulip")
    @Where(methods = {
            @MethodPredicate(method = "isComponentFocused", args = {"$observation"})
    })
    public void componentFocusedPostToZulip(ObservationRecord $observation) {
        if ($observation.subject() instanceof EntityVersion entityVersion) {
            addGeneratedActions(new PostToZulipAction(entityVersion, calculator(), editCoordinate()));
        }
    }

    /**
     * Offers "Configure Zulip connection…" so the stored bot credentials can be
     * edited in place (e.g. to swap an incoming-webhook bot — which cannot upload
     * the identicon — for a generic bot). The dialog pre-fills and overwrites.
     *
     * @see RulesBase#isComponentFocused(ObservationRecord)
     * @param $observation the focused-component observation
     */
    @Rule("Component focused — offer Zulip settings")
    @Where(methods = {
            @MethodPredicate(method = "isComponentFocused", args = {"$observation"})
    })
    public void componentFocusedConfigureZulip(ObservationRecord $observation) {
        addGeneratedActions(new ConfigureZulipAction(calculator(), editCoordinate()));
    }

    /**
     * Offers "Copy as HTML (for email)" whenever a component is focused — renders the
     * concept as rich HTML (the real adoc pill badge + styled definition / history tables)
     * and puts it on the clipboard for pasting into Outlook / Apple Mail.
     *
     * @see RulesBase#isComponentFocused(ObservationRecord)
     * @param $observation the focused-component observation
     */
    @Rule("Component focused — copy Koncept HTML for email")
    @Where(methods = {
            @MethodPredicate(method = "isComponentFocused", args = {"$observation"})
    })
    public void componentFocusedCopyHtml(ObservationRecord $observation) {
        if ($observation.subject() instanceof EntityVersion entityVersion) {
            addGeneratedActions(new CopyKonceptHtmlAction(entityVersion, calculator(), editCoordinate()));
        }
    }
}
