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

import dev.ikm.komet.framework.rulebase.RuleService;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.help.KlHelpTopic;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.CardBlueprint;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.KlCardProvider;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import network.ike.komet.claude.help.PluginHelp;
import network.ike.komet.document.surface.MarkdownRichText;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Rules card (IKE-Network/ike-issues#1391): the rule sets Komet has loaded, the place a
 * person accepts or discards a rule the assistant proposed, and where a person writes and edits
 * rules. Its content is a {@link RulesPane}.
 *
 * <p>The card is about Komet's rules, not a concept, so it ignores the journal topic.
 */
public final class RulesCard extends AbstractHostCard {

    static final String CARD_NAME = "Rules";

    /** Live cards, so a proposal made by the assistant shows in every one that is open. */
    private static final List<WeakReference<RulesCard>> OPEN = new CopyOnWriteArrayList<>();

    private RulesPane pane;

    private RulesCard(KometPreferences preferences) {
        super(preferences);
    }

    private RulesCard(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
    }

    /**
     * Has every open Rules card read the rule sets again. Safe from any thread; it does nothing
     * when no card is open.
     */
    public static void refreshOpenCards() {
        for (WeakReference<RulesCard> reference : OPEN) {
            RulesCard card = reference.get();
            if (card == null) {
                OPEN.remove(reference);
            } else {
                Platform.runLater(() -> {
                    if (card.pane != null) {
                        card.pane.reload();
                    }
                });
            }
        }
    }

    @Override
    protected String cardTitle() {
        return CARD_NAME;
    }

    @Override
    protected Optional<KlHelpTopic> helpTopic() {
        return Optional.of(PluginHelp.RULES);
    }

    @Override
    protected void buildToolbarControls(HBox toolBar) {
        Button refresh = new Button("Refresh");
        refresh.setId("rulesRefresh");
        refresh.setTooltip(new Tooltip("Read Komet's rule sets again"));
        refresh.getStyleClass().add("claude-card-toolbar-button");
        refresh.setOnAction(event -> {
            if (pane != null) {
                pane.reload();
            }
        });
        Button newRules = new Button("New");
        newRules.setId("rulesNew");
        newRules.setTooltip(new Tooltip("Write a new rule source"));
        newRules.getStyleClass().add("claude-card-toolbar-button");
        newRules.setOnAction(event -> {
            if (pane != null) {
                pane.newRuleSource();
            }
        });
        toolBar.getChildren().addAll(refresh, newRules);
    }

    @Override
    protected void renderContent() {
        if (pane != null) {
            return;
        }
        OPEN.add(new WeakReference<>(this));
        // The view can change while the card is open, so each render names components under the current one.
        pane = new RulesPane(RuleService::get,
                markdown -> new MarkdownRichText(view(), MarkdownRichText.DEFAULT_BASE).renderMarkdown(markdown));
        setCardContent(pane);
        pane.reload();
    }

    /** The card's view, or the default one before the card is hosted. */
    private ViewCalculator view() {
        return getCardViewProperties() != null ? getCardViewProperties().calculator() : Calculators.View.Default();
    }

    // ── factories ────────────────────────────────────────────────

    /** Journal "+" menu entry; {@link java.util.ServiceLoader} instantiates it via the no-arg constructor. */
    public static final class Factory implements KlCardProvider {

        /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
        public Factory() {
        }

        @Override
        public String cardName() {
            return CARD_NAME;
        }

        @Override
        public AbstractHostCard createCard(KometPreferences windowPreferences, UUID journalTopic) {
            KlPreferencesFactory cardPreferencesFactory = KlPreferencesFactory.create(windowPreferences, RulesCard.class);
            RulesCard card = new CardFactory().create(cardPreferencesFactory);
            card.setJournalTopic(journalTopic);
            return card;
        }

        @Override
        public AbstractHostCard restoreCard(KometPreferences windowPreferences) {
            KometPreferences cardNode = windowPreferences.node(RulesCard.class.getSimpleName());
            RulesCard card = new CardFactory().restore(cardNode);
            card.revert();
            return card;
        }
    }

    /** Blueprint factory building the card shell (the {@code KlArea.Factory} the base needs). */
    private static final class CardFactory implements CardBlueprint.Factory<RulesCard> {
        @Override
        public RulesCard restore(KometPreferences preferences) {
            return new RulesCard(preferences);
        }

        @Override
        public RulesCard create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            RulesCard card = new RulesCard(preferencesFactory, this);
            card.setAreaLayout(areaGridSettings.with(this.getClass()));
            return card;
        }
    }
}
