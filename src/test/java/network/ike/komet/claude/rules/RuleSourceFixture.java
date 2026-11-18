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

/**
 * Rule source for the rule tests, as the assistant would write it. Its rule is one no rule of
 * Komet's has: it offers to activate a component that is active, so its offer is told apart from
 * Komet's own, which offers to inactivate that component.
 */
final class RuleSourceFixture {

    static final String RULE_NAME = "Offer to activate an active component";
    static final String RULE_METHOD = "authored.rules.OfferActivation.offerActivation";
    static final String KOMETS_OFFER = "Inactivate component";
    static final String THE_SOURCES_OFFER = "Activate component";

    private static final String THE_OFFER = "offer(KometConcept.ACTIVATE_COMPONENT, version.entity());";

    private RuleSourceFixture() {
    }

    /** @return source that compiles and offers activation of an active component */
    static String source() {
        return sourceWith(THE_OFFER);
    }

    /**
     * @param statement the one statement in the rule's body
     * @return the source with that statement in place of the offer
     */
    static String sourceWith(String statement) {
        return """
                package authored.rules;

                import dev.ikm.komet.framework.performance.impl.ObservationRecord;
                import dev.ikm.komet.rules.annotated.RulesBase;
                import dev.ikm.komet.terms.KometConcept;
                import dev.ikm.tinkar.entity.EntityVersion;
                import org.evrete.dsl.annotation.MethodPredicate;
                import org.evrete.dsl.annotation.Rule;
                import org.evrete.dsl.annotation.Where;

                public class OfferActivation extends RulesBase {

                    @Rule("%s")
                    @Where(methods = {
                            @MethodPredicate(method = "isComponentFocused", args = {"$observation"}),
                            @MethodPredicate(method = "isComponentActive", args = {"$observation"})})
                    public void offerActivation(ObservationRecord $observation) {
                        if ($observation.subject() instanceof EntityVersion version) {
                            %s
                        }
                    }
                }
                """.formatted(RULE_NAME, statement);
    }

    /**
     * @param ruleName the name of the source's one rule, for a test whose rule sets share a rule
     *                 service or a store with other tests': a rule's name is one of a kind
     * @return source that compiles, whose rule has that name
     */
    static String sourceNamed(String ruleName) {
        return source().replace(RULE_NAME, ruleName);
    }

    /**
     * @param statement a statement of the source
     * @param source    the source
     * @return the line the statement is on, counted from 1
     */
    static int lineOf(String statement, String source) {
        return (int) source.substring(0, source.indexOf(statement)).lines().count();
    }
}
