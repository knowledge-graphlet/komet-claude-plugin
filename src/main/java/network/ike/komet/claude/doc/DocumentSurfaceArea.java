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
package network.ike.komet.claude.doc;

import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout.expand.KlExpandable;
import dev.ikm.komet.layout.expand.KlExpansionHost;
import dev.ikm.komet.layout.expand.KlPreferenceEditable;
import dev.ikm.komet.layout.settings.ControlSettings;
import dev.ikm.komet.layout_engine.blueprint.SupplementalAreaBlueprint;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import network.ike.komet.claude.doc.print.PagedPreviewView;
import network.ike.komet.claude.doc.print.settings.DocumentSurfaceSettingKeys;
import network.ike.komet.claude.doc.print.settings.PrintSettings;
import network.ike.komet.claude.ui.MarkdownRichText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.prefs.BackingStoreException;
import java.util.function.Supplier;

/**
 * The <b>printable document area</b> — the {@link DocumentSurface} block stack promoted to a
 * first-class {@link dev.ikm.komet.layout.area.KlSupplementalArea}. As an area it carries its own
 * preferences node and lifecycle; as a figure it implements {@link KlExpandable} (its full-surface
 * view is the paged print layout) and {@link KlPreferenceEditable} (its settings are the
 * {@link PrintSettings}, edited through the common generic form). So the "expand to full surface"
 * corner icon and the preferences toggle are the area's, uniform with any other expandable area —
 * not a bespoke card button.
 *
 * <p>The area hosts the surface control and owns the print settings; the enclosing card keeps the
 * conversation (rail, input, dispatch) and drives the surface through {@link #surface()}. It is
 * card-internal (created and lifecycle-driven by {@code ClaudeCard}) rather than a palette-placeable
 * area, since a Claude document with no conversation behind it would be empty. Must be used on the
 * JavaFX application thread.
 */
public final class DocumentSurfaceArea extends SupplementalAreaBlueprint
        implements KlExpandable, KlPreferenceEditable<PrintSettings> {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentSurfaceArea.class);

    /** Persists the whole {@link PrintSettings} on THIS area's preferences node (#838/#839). */
    private static final ControlSettings<DocumentSurfaceSettingKeys, PrintSettings> PRINT_CONTROL =
            ControlSettings.of(DocumentSurfaceSettingKeys.PRINT_SETTINGS, PrintSettings.DEFAULT);

    private DocumentSurface surface;
    private PrintSettings printSettings = PrintSettings.DEFAULT;
    private Supplier<String> runningHead = () -> "";

    /**
     * Restore constructor.
     *
     * @param preferences the preferences node backing this area
     */
    public DocumentSurfaceArea(KometPreferences preferences) {
        super(preferences);
        buildUi();
    }

    /**
     * Create constructor.
     *
     * @param preferencesFactory factory for this area's preferences node
     * @param areaFactory        the factory creating this area
     */
    public DocumentSurfaceArea(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
        buildUi();
    }

    private void buildUi() {
        BorderPane pane = fxObject();
        surface = new DocumentSurface(new BlockFactory(context().viewProperties(),
                MarkdownRichText.DEFAULT_BASE));
        // The surface is the figure; wrap it with the hover-reveal expand affordance (KlExpandable).
        pane.setCenter(withExpandAffordance());
        // The area is the first rung for the figures INSIDE the document — a koncept-tree or table
        // expands to fill the transcript before it fills the card. The DocumentSurface itself is a
        // ScrollPane, so it can never host an overlay; this pane, sized to the visible transcript, can.
        // The surface's own figure skips this rung: it already fills the pane (see KlExpansionHost).
        KlExpansionHost.mark(pane, () -> "Document", false);
    }

    private ViewCalculator viewCalculator() {
        return calculatorForContext();
    }

    // ── Host-facing API (the card drives the surface through these) ────────────────────────────

    /**
     * The block-stack surface this area hosts (the card appends/sets turns, searches, scrolls it).
     *
     * @return the document surface control
     */
    public DocumentSurface surface() {
        return surface;
    }

    /**
     * Sets the supplier of the running-head text (the conversation name) used by the paged view.
     *
     * @param supplier the running-head supplier; {@code null} yields a blank head
     */
    public void setRunningHeadSupplier(Supplier<String> supplier) {
        this.runningHead = supplier == null ? () -> "" : supplier;
    }

    /** Persists the current print settings on this area's preferences node. */
    public void saveSettings() {
        PRINT_CONTROL.save(preferences(), printSettings);
        try {
            preferences().flush();
        } catch (BackingStoreException e) {
            LOG.warn("Could not flush document-area print settings", e);
        }
    }

    /** Restores the print settings from this area's preferences node (or the default). */
    public void restoreSettings() {
        PrintSettings restored = PRINT_CONTROL.load(preferences());
        this.printSettings = restored == null ? PrintSettings.DEFAULT : restored;
    }

    // ── KlExpandable ───────────────────────────────────────────────────────────────────────────

    @Override
    public Region figureNode() {
        return surface;
    }

    @Override
    public Region expandedView() {
        return new PagedPreviewView(surface.turns(), viewCalculator(), runningHead.get(), printSettings);
    }

    /** A paged document is worth reading full screen, so this figure may step beyond the window rung. */
    @Override
    public boolean osFullScreenCapable() {
        return true;
    }

    // ── KlPreferenceEditable ───────────────────────────────────────────────────────────────────

    @Override
    public Supplier<PrintSettings> settings() {
        return () -> printSettings;
    }

    @Override
    public void applySettings(PrintSettings updated) {
        this.printSettings = updated == null ? PrintSettings.DEFAULT : updated;
        saveSettings();
        refreshExpandedView(); // if promoted, re-render the paged view with the new settings
    }

    // ── Area lifecycle ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void subAreaRestoreFromPreferencesOrDefault() {
        restoreSettings();
    }

    @Override
    protected void subAreaSave() {
        saveSettings();
    }

    @Override
    protected void subAreaRevert() {
        restoreSettings();
    }

    @Override
    public void knowledgeLayoutBind() {
        Platform.runLater(() -> this.lifecycleState.set(LifecycleState.BOUND));
    }

    @Override
    public void knowledgeLayoutUnbind() {
        collapse();
    }

    // ── Factory ────────────────────────────────────────────────────────────────────────────────

    /**
     * Creates a new area with the given grid settings (the card supplies a child preferences node).
     *
     * @param preferencesFactory factory for the area's preferences node
     * @param areaGridSettings   the grid placement for the new area
     * @return the new area
     */
    public static DocumentSurfaceArea create(KlPreferencesFactory preferencesFactory,
                                             AreaGridSettings areaGridSettings) {
        return new Factory().create(preferencesFactory, areaGridSettings);
    }

    /**
     * Restores an area from preferences.
     *
     * @param preferences the preferences node
     * @return the restored area
     */
    public static DocumentSurfaceArea restore(KometPreferences preferences) {
        return new Factory().restore(preferences);
    }

    /**
     * Factory for {@link DocumentSurfaceArea}. Not a {@code ServiceLoader} provider — the area is
     * card-internal, created directly by {@code ClaudeCard} rather than discovered in the palette.
     */
    public static final class Factory implements SupplementalAreaBlueprint.Factory<DocumentSurfaceArea> {

        @Override
        public DocumentSurfaceArea restore(KometPreferences preferences) {
            return new DocumentSurfaceArea(preferences);
        }

        @Override
        public DocumentSurfaceArea create(KlPreferencesFactory preferencesFactory,
                                          AreaGridSettings areaGridSettings) {
            DocumentSurfaceArea area = new DocumentSurfaceArea(preferencesFactory, this);
            area.setAreaLayout(areaGridSettings.with(this.getClass()));
            return area;
        }
    }
}
