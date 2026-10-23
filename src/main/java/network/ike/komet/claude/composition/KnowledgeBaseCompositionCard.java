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
package network.ike.komet.claude.composition;

import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.CardBlueprint;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.KlCardProvider;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import network.ike.komet.claude.composition.KnowledgeBaseComposition.Composition;
import network.ike.komet.claude.ui.MarkdownEditPane;
import network.ike.komet.claude.ui.MarkdownRichText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the open knowledge base is made of (IKE-Network/ike-issues#1304):
 * <ul>
 * <li>the releases it holds, in the order they arrived, each with what its import wrote and the
 * importers that have read it;</li>
 * <li>the latest assembly's inputs and their checksums;</li>
 * <li>every assembly build, newest first, with what changed from the one before;</li>
 * <li>the exports taken from it.</li>
 * </ul>
 * Everything shown is read from the registrations the IKE Registrar wrote into the store; see
 * {@link KnowledgeBaseComposition}.
 *
 * <p>The composition is shown as read-only rich text, the {@link MarkdownEditPane} every read-only
 * document uses, so any of it can be selected and copied. The Markdown it renders is
 * {@link KnowledgeBaseComposition#markdown}, the same text Copy summary copies and the assistant's
 * {@code kb_composition} tool answers.
 *
 * <p>The card is about the store, not a concept, so it ignores the journal topic.
 */
public final class KnowledgeBaseCompositionCard extends AbstractHostCard {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseCompositionCard.class);

    static final String CARD_NAME = "Knowledge Base Composition";

    /** Live cards, most recently realized last, so tools and automation can find the one in use. */
    private static final List<WeakReference<KnowledgeBaseCompositionCard>> OPEN = new CopyOnWriteArrayList<>();

    /** @return the most recently realized card still alive, or {@code null} */
    public static KnowledgeBaseCompositionCard mostRecent() {
        KnowledgeBaseCompositionCard found = null;
        for (WeakReference<KnowledgeBaseCompositionCard> ref : OPEN) {
            KnowledgeBaseCompositionCard card = ref.get();
            if (card == null) {
                OPEN.remove(ref);
            } else {
                found = card;
            }
        }
        return found;
    }

    private final BorderPane host = new BorderPane();
    private final Label statusLine = new Label("Reading the knowledge base's composition…");
    private MarkdownEditPane document;
    private Composition shown;
    private boolean built;

    private KnowledgeBaseCompositionCard(KometPreferences preferences) {
        super(preferences);
    }

    private KnowledgeBaseCompositionCard(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
    }

    @Override
    protected String cardTitle() {
        return CARD_NAME;
    }

    /** @return the composition shown, once one is read */
    public Optional<Composition> shown() {
        return Optional.ofNullable(shown);
    }

    /** @return the Markdown the card renders; empty before a composition is read */
    public String shownMarkdown() {
        return document == null ? "" : document.getText();
    }

    @Override
    protected void buildToolbarControls(HBox toolBar) {
        Button refresh = new Button("Refresh");
        refresh.setId("kbCompositionRefresh");
        refresh.setTooltip(new Tooltip("Re-read the knowledge base's composition records"));
        refresh.getStyleClass().add("claude-card-toolbar-button");
        refresh.setOnAction(e -> refresh());
        Button copy = new Button("Copy summary");
        copy.setId("kbCompositionCopy");
        copy.setTooltip(new Tooltip("Copy the whole composition as Markdown, to paste into an issue or a note"));
        copy.getStyleClass().add("claude-card-toolbar-button");
        copy.setOnAction(e -> {
            if (shown != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(shownMarkdown());
                Clipboard.getSystemClipboard().setContent(content);
                statusLine.setText("Copied the composition summary as Markdown.");
            }
        });
        toolBar.getChildren().addAll(refresh, copy);
    }

    @Override
    protected void renderContent() {
        if (built) {
            return;
        }
        built = true;
        OPEN.add(new WeakReference<>(this));
        statusLine.setPadding(new Insets(4, 8, 4, 8));
        statusLine.setWrapText(true);
        // The view can change while the card is open, so each render names components under the current one.
        document = new MarkdownEditPane("", false,
                markdown -> new MarkdownRichText(view(), MarkdownRichText.DEFAULT_BASE).renderMarkdown(markdown));
        document.setId("kbCompositionDocument");
        BorderPane.setMargin(document, new Insets(6, 8, 0, 8));
        host.setCenter(document);
        host.setBottom(statusLine);
        setCardContent(host);
        refresh();
    }

    /** Re-reads the store's composition records off the FX thread and redraws the card. */
    public void refresh() {
        ViewCalculator view = view();
        CompletableFuture.supplyAsync(() -> {
            Composition composition = KnowledgeBaseComposition.read();
            return new Shown(composition, KnowledgeBaseComposition.markdown(view, composition));
        }).whenComplete((found, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText("Could not read the knowledge base's composition: " + failure.getMessage());
                LOG.warn("Reading the knowledge base composition failed", failure);
                return;
            }
            show(found);
        }));
    }

    /** A composition and its Markdown, both read off the FX thread. */
    private record Shown(Composition composition, String markdown) {
    }

    private void show(Shown found) {
        Composition composition = found.composition();
        shown = composition;
        document.setText(found.markdown());
        statusLine.setText(switch (composition.recorded()) {
            case REGISTRY_ABSENT -> "No registry patterns in this knowledge base.";
            case NOT_RECORDED -> "No registrations.";
            case RECORDED -> composition.releases().size() + " release(s), " + composition.assemblies().size()
                    + " assembly build(s), " + composition.exports().size() + " export(s).";
        });
    }

    /** The card's view, or the default one before the card is hosted. */
    private ViewCalculator view() {
        return getCardViewProperties() != null ? getCardViewProperties().calculator() : Calculators.View.Default();
    }

    // ── factories ─────────────────────────────────────────────────────────────

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
            KlPreferencesFactory cardPreferencesFactory = KlPreferencesFactory.create(windowPreferences, KnowledgeBaseCompositionCard.class);
            KnowledgeBaseCompositionCard card = new CardFactory().create(cardPreferencesFactory);
            card.setJournalTopic(journalTopic);
            return card;
        }

        @Override
        public AbstractHostCard restoreCard(KometPreferences windowPreferences) {
            KometPreferences cardNode = windowPreferences.node(KnowledgeBaseCompositionCard.class.getSimpleName());
            KnowledgeBaseCompositionCard card = new CardFactory().restore(cardNode);
            card.revert();
            return card;
        }
    }

    /** Blueprint factory building the card shell (the {@code KlArea.Factory} the base needs). */
    private static final class CardFactory implements CardBlueprint.Factory<KnowledgeBaseCompositionCard> {
        @Override
        public KnowledgeBaseCompositionCard restore(KometPreferences preferences) {
            return new KnowledgeBaseCompositionCard(preferences);
        }

        @Override
        public KnowledgeBaseCompositionCard create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            KnowledgeBaseCompositionCard card = new KnowledgeBaseCompositionCard(preferencesFactory, this);
            card.setAreaLayout(areaGridSettings.with(this.getClass()));
            return card;
        }
    }
}
