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
import dev.ikm.komet.framework.rulebase.RuleFinding;
import dev.ikm.komet.framework.rulebase.RuleService;
import dev.ikm.komet.framework.rulebase.RuleSource;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;
import network.ike.komet.document.MarkdownEditPane;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The rules Komet has loaded, where a person decides on a proposed one and writes rules of their own: every
 * rule set in a list, proposals first, and the selected one in full beside it, with its findings
 * and its Java source.
 *
 * <p>A proposed rule source is accepted or discarded here, and a rule source in use can be
 * removed. Edit opens the source of a rule set written in Komet, and New opens a new one from a
 * starting shape. Check compiles what is in the editor and lists its findings, changing nothing.
 * Save compiles it too, and puts its rules in use at once when there are no findings; with
 * findings nothing is saved, so the rules in use stay as they are. A rule the person wrote or
 * edited needs no second acceptance: saving it is the person's own act.
 *
 * <p>What a person saves or accepts is kept in the knowledge base ({@link StoredRules}) before
 * its rules come into use, and what they remove is taken out of it, so the rules in use and the
 * rule sets Komet starts with next time are the same. When the knowledge base refuses the write,
 * nothing changes, and the status line says why. A rule set's name becomes the name of its
 * concept, from which its identity derives, so it is given once, when the rule set is new.
 *
 * <p>Saving or accepting a rule set is also what lets it come into use by itself when Komet next
 * starts ({@link AcceptedRules}). A rule set the knowledge base keeps whose text nobody saved or
 * accepted on this installation is listed as not accepted here: it is not in use, and it stays
 * listed until a person accepts it or removes it.
 *
 * <p>The pane shows what the rule service lists when it is asked to: after its own buttons, and
 * when {@link #reload()} is called. The text beside the list is {@link RuleSetText#detail}, the
 * words the assistant's {@code read_rule} tool answers.
 */
final class RulesPane extends BorderPane {

    /** The face code is set in across Komet's document surfaces. */
    private static final Font CODE = Font.font("Menlo", 12);

    /** Where a new rule source starts: a rule that compiles and offers nothing yet. */
    static final String NEW_SOURCE = """
            package authored.rules;

            import dev.ikm.komet.framework.performance.impl.ObservationRecord;
            import dev.ikm.komet.rules.annotated.RulesBase;
            import dev.ikm.komet.terms.KometConcept;
            import dev.ikm.tinkar.entity.EntityVersion;
            import org.evrete.dsl.annotation.MethodPredicate;
            import org.evrete.dsl.annotation.Rule;
            import org.evrete.dsl.annotation.Where;

            public class NewRules extends RulesBase {

                // Give the rule a name no other rule has, one that says what it offers.
                @Rule("New rule")
                @Where(methods = {
                        @MethodPredicate(method = "isComponentFocused", args = {"$observation"}),
                        @MethodPredicate(method = "isConceptVersion", args = {"$observation"})})
                public void newRule(ObservationRecord $observation) {
                    if ($observation.subject() instanceof EntityVersion version) {
                        // Offer an action on the focused component, for example:
                        // offer(KometConcept.INACTIVATE_COMPONENT, version.entity());
                    }
                }
            }
            """;

    static final String NEW_NAME = "New rules";

    private final Supplier<RuleService> rules;
    private final StoredRules store;
    private final AcceptedRules acceptedHere;
    /**
     * The proposals that are the text the knowledge base keeps: rule sets nobody saved or accepted
     * on this installation. Read at each {@link #reload()}.
     */
    private Set<UUID> keptNotAccepted = Set.of();
    private final ListView<ListedRuleSet> list = new ListView<>();
    private final MarkdownEditPane detail;
    private final Button accept = new Button("Accept");
    private final Button discard = new Button("Discard");
    private final Button edit = new Button("Edit");
    private final Button remove = new Button("Remove");
    private final Label statusLine = new Label();

    private final BorderPane selected = new BorderPane();
    private final BorderPane reading = new BorderPane();
    private final VBox editing = new VBox(6);
    private final TextField editorName = new TextField();
    private final TextArea editorJava = new TextArea();
    private final Label editorPlace = new Label();
    private final ListView<RuleFinding> editorFindings = new ListView<>();
    private final Button check = new Button("Check");
    private final Button save = new Button("Save");
    private final Button cancel = new Button("Cancel");

    /** Whether the editor is open. */
    private boolean editorOpen;
    /**
     * The identity of the rule source in the editor; null for a new one, whose identity its name
     * gives when it is checked or saved.
     */
    private UUID editingId;
    /** Whether the source in the editor came from a proposal, which saving takes the place of. */
    private boolean editingProposal;

    /**
     * @param rules    the rule service whose rule sets are shown
     * @param store    where a rule set a person saves or accepts is kept
     * @param accepted the record of the texts a person saved or accepted on this installation
     * @param renderer turns the selected rule set's Markdown into what the pane shows
     */
    RulesPane(Supplier<RuleService> rules, StoredRules store, AcceptedRules accepted,
              Function<String, StyledTextModel> renderer) {
        this.rules = rules;
        this.store = store;
        this.acceptedHere = accepted;
        this.detail = new MarkdownEditPane("", false, renderer);

        list.setId("rulesList");
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ListedRuleSet ruleSet, boolean empty) {
                super.updateItem(ruleSet, empty);
                setText(empty || ruleSet == null ? null : line(ruleSet));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((observable, before, chosen) -> show(chosen));

        buildReading();
        buildEditing();
        selected.setCenter(reading);

        SplitPane split = new SplitPane(list, selected);
        split.setDividerPositions(0.32);
        setCenter(split);
        statusLine.setId("rulesStatus");
        statusLine.setPadding(new Insets(4, 8, 4, 8));
        statusLine.setWrapText(true);
        setBottom(statusLine);
        show(null);
    }

    private void buildReading() {
        detail.setId("rulesDetail");
        accept.setId("rulesAccept");
        accept.setTooltip(new Tooltip("Put this proposed rule source in use and keep it in the knowledge base"));
        accept.setOnAction(event -> selectedSourceId().ifPresent(this::acceptProposal));
        discard.setId("rulesDiscard");
        discard.setTooltip(new Tooltip("Discard this proposed rule source"));
        discard.setOnAction(event -> selectedSourceId().ifPresent(this::discardProposal));
        edit.setId("rulesEdit");
        edit.setTooltip(new Tooltip("Edit this rule source"));
        edit.setOnAction(event -> selected().ifPresent(this::editRuleSet));
        remove.setId("rulesRemove");
        remove.setTooltip(new Tooltip("Take this rule source out of use, here and in the knowledge base"));
        remove.setOnAction(event -> selectedSourceId().ifPresent(this::removeSource));

        HBox decisions = new HBox(8, accept, discard, edit, remove);
        decisions.setPadding(new Insets(6, 8, 6, 8));
        reading.setCenter(detail);
        reading.setBottom(decisions);
        BorderPane.setMargin(detail, new Insets(6, 8, 0, 8));
    }

    private void buildEditing() {
        editorName.setId("rulesEditorName");
        editorName.setPromptText("Name");
        editorName.setTooltip(new Tooltip("The rule set's name, given when it is new"));
        editorJava.setId("rulesEditorJava");
        editorJava.setFont(CODE);
        editorJava.caretPositionProperty().addListener((observable, before, caret) -> showPlace());
        editorPlace.setId("rulesEditorPlace");

        editorFindings.setId("rulesEditorFindings");
        editorFindings.setPrefHeight(110);
        editorFindings.setPlaceholder(new Label("No findings"));
        editorFindings.setCellFactory(view -> {
            ListCell<RuleFinding> cell = new ListCell<>() {
                @Override
                protected void updateItem(RuleFinding finding, boolean empty) {
                    super.updateItem(finding, empty);
                    setText(empty || finding == null ? null : RuleSetText.finding(finding));
                }
            };
            // A compiler message runs over several lines and points at a column, so it wraps and is set as code.
            cell.setFont(CODE);
            cell.setWrapText(true);
            cell.setMaxWidth(Control.USE_PREF_SIZE);
            cell.prefWidthProperty().bind(view.widthProperty().subtract(24));
            return cell;
        });
        editorFindings.getSelectionModel().selectedItemProperty()
                .addListener((observable, before, finding) -> goTo(finding));

        check.setId("rulesCheck");
        check.setTooltip(new Tooltip("Compile this source and list its findings, changing nothing"));
        check.setOnAction(event -> checkEdited());
        save.setId("rulesSave");
        save.setTooltip(new Tooltip("Compile this source, keep it in the knowledge base and put its rules in use"));
        save.setOnAction(event -> saveEdited());
        cancel.setId("rulesCancel");
        cancel.setTooltip(new Tooltip("Leave the editor without saving"));
        cancel.setOnAction(event -> cancelEditing());

        HBox buttons = new HBox(8, check, save, cancel, editorPlace);
        VBox.setVgrow(editorJava, Priority.ALWAYS);
        editing.setPadding(new Insets(6, 8, 6, 8));
        editing.getChildren().addAll(editorName, editorJava, editorFindings, buttons);
    }

    /**
     * Reads the rule sets again and shows them, keeping the selection where it still exists.
     * What is in the editor is left as it is.
     */
    void reload() {
        ListedRuleSet before = list.getSelectionModel().getSelectedItem();
        List<ListedRuleSet> listed = proposalsFirst(rules.get().ruleSets().castToList());
        Set<UUID> kept = new HashSet<>();
        for (ListedRuleSet ruleSet : listed) {
            if (!ruleSet.accepted() && ruleSet.source().isPresent()) {
                RuleSource source = ruleSet.source().get();
                if (store.keptText(source.id()).filter(source.java()::equals).isPresent()) {
                    kept.add(source.id());
                }
            }
        }
        keptNotAccepted = kept;
        list.getItems().setAll(listed);
        Optional<ListedRuleSet> again = before == null ? Optional.empty()
                : listed.stream().filter(ruleSet -> same(ruleSet, before)).findFirst();
        if (again.isPresent()) {
            list.getSelectionModel().select(again.get());
        } else if (!listed.isEmpty()) {
            list.getSelectionModel().selectFirst();
        }
        show(list.getSelectionModel().getSelectedItem());
        if (!editing()) {
            showCounts(listed);
        }
    }

    /** Opens the editor on a new rule source, started from {@link #NEW_SOURCE}. */
    void newRuleSource() {
        if (!editing()) {
            beginEditing(null, NEW_NAME, NEW_SOURCE, false);
        }
    }

    /** @return whether the editor is open */
    boolean editing() {
        return editorOpen;
    }

    /** @return the rule set shown beside the list, if one is selected */
    Optional<ListedRuleSet> selected() {
        return Optional.ofNullable(list.getSelectionModel().getSelectedItem());
    }

    /** @return the Markdown shown for the selected rule set; empty when none is selected */
    String shownMarkdown() {
        return detail.getText();
    }

    /**
     * Selects the rule source with an identity, the proposal when it is both proposed and in use.
     *
     * @param id the rule source's identity
     * @return whether a rule source with that identity is listed
     */
    boolean select(UUID id) {
        Optional<ListedRuleSet> found = list.getItems().stream()
                .filter(ruleSet -> ruleSet.source().map(RuleSource::id).filter(id::equals).isPresent())
                .findFirst();
        found.ifPresent(ruleSet -> list.getSelectionModel().select(ruleSet));
        return found.isPresent();
    }

    private void acceptProposal(UUID id) {
        Optional<ListedRuleSet> proposal = rules.get().ruleSets().detectOptional(ruleSet -> !ruleSet.accepted()
                && ruleSet.source().map(RuleSource::id).filter(id::equals).isPresent());
        StoredRules.Outcome kept = null;
        if (proposal.isPresent()) {
            // Kept first: a rule set the knowledge base refuses does not come into use.
            RuleSource source = proposal.get().source().orElseThrow();
            kept = store.keep(source, proposal.get().ruleNames().castToList());
            if (kept.refused()) {
                statusLine.setText("Not accepted. " + kept.why() + " The rules in use have not changed.");
                return;
            }
            recordAccepted(source, kept);
        }
        Optional<ListedRuleSet> accepted = rules.get().acceptRuleSource(id);
        reload();
        select(id);
        String keptWords = kept == null ? "" : kept(kept);
        statusLine.setText(accepted
                .map(ruleSet -> ruleSet.inUse()
                        ? "Accepted \"" + ruleSet.name() + "\". Its rules are in use." + keptWords
                        : "Accepted \"" + ruleSet.name() + "\", but it is not in use: it has findings." + keptWords)
                .orElse("That proposal is no longer there."));
    }

    private void discardProposal(UUID id) {
        String name = selected().map(ListedRuleSet::name).orElse("");
        boolean discarded = rules.get().discardProposedRuleSource(id);
        reload();
        statusLine.setText(discarded ? "Discarded the proposed rule source \"" + name + "\"."
                : "That proposal is no longer there.");
    }

    private void removeSource(UUID id) {
        String name = selected().map(ListedRuleSet::name).orElse("");
        // One the knowledge base keeps that was not accepted here is listed as a proposal.
        boolean notInUse = selected().filter(ruleSet -> !ruleSet.accepted()).isPresent();
        // Taken out of the knowledge base first, so a rule set it still keeps is still in use.
        StoredRules.Outcome removed = store.remove(id);
        if (removed.refused()) {
            statusLine.setText("Not removed. " + removed.why() + " The rules in use have not changed.");
            return;
        }
        acceptedHere.forget(id);
        boolean wasThere = notInUse ? rules.get().discardProposedRuleSource(id) : rules.get().removeRuleSource(id);
        reload();
        statusLine.setText(wasThere
                ? "Removed \"" + name + "\"." + (notInUse ? " It was not in use." : " Its rules are no longer in use.")
                        + removed(removed)
                : "That rule source is no longer there.");
    }

    /** Records that a person saved or accepted a text here, when the knowledge base keeps it. */
    private void recordAccepted(RuleSource source, StoredRules.Outcome kept) {
        if (kept.result() == StoredRules.Result.WRITTEN || kept.result() == StoredRules.Result.AS_IT_WAS) {
            acceptedHere.accept(source);
        }
    }

    private void editRuleSet(ListedRuleSet ruleSet) {
        ruleSet.source().ifPresent(source ->
                beginEditing(source.id(), source.name(), source.java(), !ruleSet.accepted()));
    }

    private void beginEditing(UUID id, String name, String java, boolean proposal) {
        editorOpen = true;
        editingId = id;
        editingProposal = proposal;
        editorName.setText(name);
        // A rule set keeps the name it was first given: its concept's name, and so its identity.
        editorName.setDisable(id != null);
        editorJava.setText(java);
        editorJava.positionCaret(0);
        editorFindings.getItems().clear();
        selected.setCenter(editing);
        // One rule source is edited at a time: choosing another waits for Save or Cancel.
        list.setDisable(true);
        showPlace();
        statusLine.setText("Editing \"" + name + "\". Nothing changes until you save.");
    }

    private void endEditing() {
        editorOpen = false;
        editingId = null;
        editingProposal = false;
        selected.setCenter(reading);
        list.setDisable(false);
    }

    private RuleSource edited() {
        String name = editorName.getText() == null || editorName.getText().isBlank()
                ? NEW_NAME : editorName.getText().strip();
        // A new rule set's identity is the one its name gives, as a concept's is.
        UUID id = editingId == null ? store.identity(name) : editingId;
        return new RuleSource(id, name, editorJava.getText() == null ? "" : editorJava.getText());
    }

    /** What stands in the way of a new rule set's name: a rule set that already has it. */
    private Optional<String> nameTaken(RuleSource source) {
        if (editingId != null) {
            return Optional.empty();
        }
        boolean listed = rules.get().ruleSets().anySatisfy(ruleSet ->
                ruleSet.source().map(RuleSource::id).filter(source.id()::equals).isPresent());
        return listed || store.kept(source.id())
                ? Optional.of("A rule set named \"" + source.name() + "\" already exists. Give this one a name of its"
                        + " own, or edit that one.")
                : Optional.empty();
    }

    private void checkEdited() {
        RuleSource source = edited();
        ListedRuleSet checked = rules.get().checkRuleSource(source);
        editorFindings.getItems().setAll(checked.findings().castToList());
        Optional<String> taken = nameTaken(source);
        if (taken.isPresent()) {
            statusLine.setText(taken.get() + " Nothing has changed.");
            return;
        }
        statusLine.setText(checked.findings().isEmpty()
                ? "No findings. Rules: " + checked.ruleNames().makeString("; ") + ". Nothing has changed yet."
                : found(checked) + " Nothing has changed.");
    }

    private void saveEdited() {
        RuleSource source = edited();
        Optional<String> taken = nameTaken(source);
        if (taken.isPresent()) {
            statusLine.setText("Not saved. " + taken.get() + " The rules in use have not changed.");
            return;
        }
        // Checked first: a source with findings is not saved, so the rules in use stay as they are.
        ListedRuleSet checked = rules.get().checkRuleSource(source);
        editorFindings.getItems().setAll(checked.findings().castToList());
        if (checked.findings().notEmpty()) {
            statusLine.setText("Not saved. " + found(checked) + " The rules in use have not changed.");
            return;
        }
        // Kept next: a rule set the knowledge base refuses does not come into use.
        StoredRules.Outcome kept = store.keep(source, checked.ruleNames().castToList());
        if (kept.refused()) {
            statusLine.setText("Not saved. " + kept.why() + " The rules in use have not changed.");
            return;
        }
        recordAccepted(source, kept);
        ListedRuleSet saved = rules.get().putRuleSource(source);
        if (editingProposal) {
            // The person's own version takes the proposal's place.
            rules.get().discardProposedRuleSource(source.id());
        }
        endEditing();
        reload();
        select(source.id());
        statusLine.setText((saved.inUse() ? "Saved \"" + saved.name() + "\". Its rules are in use."
                : "Saved \"" + saved.name() + "\", but it is not in use: it has findings.") + kept(kept));
    }

    /** What keeping a rule set came to, as a sentence after the one that says its rules are in use. */
    private static String kept(StoredRules.Outcome outcome) {
        return switch (outcome.result()) {
            case WRITTEN -> " It is kept in the knowledge base, as an unpublished change.";
            case AS_IT_WAS -> " The knowledge base already keeps this text.";
            case NO_RULE_MODEL -> " This knowledge base cannot keep rule sets yet, so this one lasts until Komet quits.";
            case TAKEN_BACK, TEXT_TAKEN_BACK, REFUSED -> "";
        };
    }

    /** What taking a rule set out of the knowledge base came to, as a sentence of its own. */
    private static String removed(StoredRules.Outcome outcome) {
        return switch (outcome.result()) {
            case WRITTEN -> " Its removal from the knowledge base is an unpublished change.";
            case TAKEN_BACK -> " It was never published, so all of it is taken back out of the knowledge base: its"
                    + " text, and the concepts of the rule set and of its rules.";
            case TEXT_TAKEN_BACK -> " Its text, which was never published, is taken back out of the knowledge base."
                    + (outcome.why().isEmpty() ? "" : " " + outcome.why());
            case AS_IT_WAS, NO_RULE_MODEL, REFUSED -> "";
        };
    }

    private void cancelEditing() {
        endEditing();
        reload();
    }

    private static String found(ListedRuleSet checked) {
        int count = checked.findings().size();
        return count + (count == 1 ? " finding." : " findings.");
    }

    /** Selects the line a finding is about, so it shows in the source. */
    private void goTo(RuleFinding finding) {
        if (finding == null || finding.line() < 1) {
            return;
        }
        String java = editorJava.getText();
        int start = 0;
        for (int line = 1; line < finding.line(); line++) {
            int end = java.indexOf('\n', start);
            if (end < 0) {
                return;
            }
            start = end + 1;
        }
        int end = java.indexOf('\n', start);
        editorJava.selectRange(start, end < 0 ? java.length() : end);
    }

    private void showPlace() {
        String java = editorJava.getText() == null ? "" : editorJava.getText();
        int caret = Math.min(editorJava.getCaretPosition(), java.length());
        int line = 1;
        int lineStart = 0;
        for (int at = java.indexOf('\n'); at >= 0 && at < caret; at = java.indexOf('\n', at + 1)) {
            line++;
            lineStart = at + 1;
        }
        editorPlace.setText("Line " + line + ", column " + (caret - lineStart + 1));
    }

    private void show(ListedRuleSet ruleSet) {
        // Another rule set is another document, shown from its start.
        boolean kept = ruleSet != null && keptNotAccepted(ruleSet);
        detail.showText(ruleSet == null ? "" : RuleSetText.detail(ruleSet, kept));
        boolean proposal = ruleSet != null && !ruleSet.accepted();
        boolean written = ruleSet != null && ruleSet.source().isPresent();
        // A proposal with findings cannot be accepted as it is; it can still be edited or discarded.
        accept.setDisable(!proposal || ruleSet.findings().notEmpty());
        // What the knowledge base keeps is not discarded, which would only hide it until Komet
        // next starts: it is accepted, or removed from the knowledge base.
        discard.setDisable(!proposal || kept);
        edit.setDisable(!written);
        remove.setDisable(!written || (proposal && !kept));
    }

    /** Whether a listed rule set is one the knowledge base keeps that nobody accepted here. */
    private boolean keptNotAccepted(ListedRuleSet ruleSet) {
        return !ruleSet.accepted()
                && ruleSet.source().map(RuleSource::id).filter(keptNotAccepted::contains).isPresent();
    }

    private void showCounts(List<ListedRuleSet> listed) {
        long kept = listed.stream().filter(this::keptNotAccepted).count();
        long proposed = listed.stream().filter(ruleSet -> !ruleSet.accepted()).count() - kept;
        long inUse = listed.stream().filter(ListedRuleSet::inUse).count();
        statusLine.setText(inUse + " rule set" + (inUse == 1 ? "" : "s") + " in use"
                + (proposed == 0 ? "" : ", " + proposed + " proposed and waiting for you")
                + (kept == 0 ? "" : ", " + kept + " kept in the knowledge base and not accepted here")
                + ".");
    }

    private Optional<UUID> selectedSourceId() {
        return selected().flatMap(ListedRuleSet::source).map(RuleSource::id);
    }

    private String line(ListedRuleSet ruleSet) {
        // The state comes first, so it still shows when a long name is cut short.
        if (!ruleSet.accepted()) {
            return (keptNotAccepted(ruleSet) ? "Not accepted here · " : "Proposed · ") + ruleSet.name();
        }
        return ruleSet.inUse() ? ruleSet.name() : "Not in use · " + ruleSet.name();
    }

    /** The proposals first, since they are what waits on the person; the rest in the service's order. */
    private static List<ListedRuleSet> proposalsFirst(List<ListedRuleSet> listed) {
        List<ListedRuleSet> ordered = new ArrayList<>();
        listed.stream().filter(ruleSet -> !ruleSet.accepted()).forEach(ordered::add);
        listed.stream().filter(ListedRuleSet::accepted).forEach(ordered::add);
        return ordered;
    }

    private static boolean same(ListedRuleSet one, ListedRuleSet other) {
        return one.accepted() == other.accepted() && one.origin() == other.origin()
                && one.source().map(RuleSource::id).equals(other.source().map(RuleSource::id))
                && (one.source().isPresent() || one.name().equals(other.name()));
    }
}
