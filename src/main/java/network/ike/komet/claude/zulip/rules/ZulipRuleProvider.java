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

import dev.ikm.komet.framework.rulebase.RuleProvider;

import java.util.List;

/**
 * Contributes the Zulip rule classes to the rule engine, discovered via
 * {@code PluggableService.load(RuleProvider.class)} (registered with
 * {@code provides dev.ikm.komet.framework.rulebase.RuleProvider with ...} in this
 * module's {@code module-info}). Because the rule and action live in the plugin,
 * they can be updated without a new komet release.
 */
public final class ZulipRuleProvider implements RuleProvider {

    /**
     * Public no-arg constructor required for {@link java.util.ServiceLoader}.
     */
    public ZulipRuleProvider() {
        // no-op
    }

    @Override
    public List<Class<?>> ruleClasses() {
        return List.of(ZulipRules.class);
    }
}
