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
package network.ike.komet.claude.stamp;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredAnnotation;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * The provenance of one line, as the hover popover and the history dialog show it
 * (ike-lab-documents topic {@code dev-stamped-document-surface}): the introducing STAMP's
 * identicon, author, date and state; the commit's short SHA and subject when the repository is
 * known; the latest STAMP when a later commit changed the line; and the annotations in force,
 * each with its kind, author and text. Names come through {@link StampNames}, never a nid.
 */
final class ProvenancePopover {

    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final Font SMALL = Font.font(11);
    private static final Font BODY = Font.font(12);

    private ProvenancePopover() { }

    /**
     * Builds the popover's content for a line.
     *
     * @param line      the line
     * @param view      the view that names authors
     * @param summaries the commit summaries of the document
     * @param identicon renders a STAMP's identicon
     * @param text      the line's text, shown first, or {@code null} to omit it
     * @return the content
     */
    static Node content(StoredLine line, ViewCalculator view, CommitSummaries summaries, Function<StampEntity<?>, Image> identicon, String text) {
        VBox box = new VBox(4);
        box.setPadding(new Insets(8, 10, 8, 10));
        box.setStyle("-fx-background-color: white; -fx-border-color: #c9c5be; -fx-border-radius: 4; -fx-background-radius: 4;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 8, 0, 0, 2);");
        box.setMaxWidth(420);
        if (text != null) {
            Label quoted = new Label(text.isBlank() ? "(blank line)" : text);
            quoted.setFont(Font.font("Menlo", 11));
            quoted.setWrapText(true);
            quoted.setStyle("-fx-text-fill: #5f6b7a;");
            box.getChildren().add(quoted);
        }
        StampEntity<?> introduced = line.introduced();
        if (introduced != null && introduced.time() != Long.MAX_VALUE) {
            box.getChildren().add(stampRow(introduced, "Introduced", view, summaries, identicon));
        } else if (introduced != null) {
            box.getChildren().add(stampRow(introduced, "Edited in Komet, uncommitted", view, summaries, identicon));
        }
        StampEntity<?> latest = line.latest();
        if (latest != null && introduced != null && latest.nid() != introduced.nid()) {
            box.getChildren().add(stampRow(latest, latest.time() == Long.MAX_VALUE ? "Edited in Komet, uncommitted" : "Latest", view, summaries, identicon));
        }
        if (line.annotations().isEmpty()) {
            Label none = new Label(introduced == null || introduced.time() == Long.MAX_VALUE ? "" : "Not yet affirmed.");
            none.setFont(SMALL);
            none.setStyle("-fx-text-fill: #8a8782;");
            if (!none.getText().isEmpty()) {
                box.getChildren().add(none);
            }
        }
        for (StoredAnnotation annotation : line.annotations()) {
            box.getChildren().add(annotationRow(annotation, view));
        }
        return box;
    }

    /** @return "✓ Affirmed by Name on date: text", coloured by kind */
    static Label annotationRow(StoredAnnotation annotation, ViewCalculator view) {
        String who = annotation.stamp() == null ? "someone" : StampNames.name(view, annotation.stamp().authorNid());
        String when = annotation.stamp() == null ? "" : " on " + MOMENT.format(Instant.ofEpochMilli(annotation.stamp().time()));
        String head = switch (annotation.kind()) {
            case AFFIRMATION -> "✓ Affirmed";
            case REFUTATION -> "✗ Refuted";
            case COMMENT -> "… Commented on";
        };
        Label label = new Label(head + " by " + who + when + (annotation.text() == null || annotation.text().isBlank() ? "" : ": " + annotation.text()));
        label.setWrapText(true);
        label.setFont(BODY);
        label.setStyle(switch (annotation.kind()) {
            case AFFIRMATION -> "-fx-text-fill: #2e7d32;";
            case REFUTATION -> "-fx-text-fill: #c62828;";
            case COMMENT -> "-fx-text-fill: #5f6b7a;";
        });
        return label;
    }

    private static Node stampRow(StampEntity<?> stamp, String role, ViewCalculator view, CommitSummaries summaries, Function<StampEntity<?>, Image> identicon) {
        ImageView badge = new ImageView(identicon.apply(stamp));
        badge.setPreserveRatio(true);
        badge.setFitHeight(28);
        Label author = new Label(StampNames.name(view, stamp.authorNid()));
        author.setFont(Font.font("System", FontWeight.BOLD, 12));
        boolean uncommitted = stamp.time() == Long.MAX_VALUE;
        Label when = new Label(role + (uncommitted ? "" : " " + MOMENT.format(Instant.ofEpochMilli(stamp.time())))
                + " · " + stamp.state().name().toLowerCase());
        when.setFont(SMALL);
        when.setStyle("-fx-text-fill: #5f6b7a;");
        VBox text = new VBox(1, author, when);
        summaries.summary(stamp.nid()).ifPresent(summary -> {
            Label commit = new Label(summary);
            commit.setFont(Font.font("Menlo", 11));
            commit.setWrapText(true);
            text.getChildren().add(commit);
        });
        HBox row = new HBox(8, badge, text);
        row.setAlignment(Pos.TOP_LEFT);
        return row;
    }
}
