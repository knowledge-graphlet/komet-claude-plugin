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

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The Repository panel of the Stamped Document card (ike-lab-documents topic
 * {@code dev-stamped-document-surface}, phase 4; IKE-Network/ike-issues#1307): always visible,
 * collapsible, showing the working tree the shown document's file lives in — its root, each
 * remote's name and URL (a link that opens the browser), the branch, HEAD with its short SHA and
 * subject, the commits ahead of and behind the upstream, and the open file's status: clean,
 * modified in the working tree, or carrying an edit pending in Komet — with the operations
 * beside it: Clone…, Fetch, Pull (fast-forward only), Push…, Commit… and History. The host
 * performs the operations through the person's own git ({@link GitOperations}); the panel only
 * reads and shows.
 */
final class RepositoryPanel extends TitledPane {

    private static final Logger LOG = LoggerFactory.getLogger(RepositoryPanel.class);

    /** What the panel asks of its card. */
    interface Host {
        Path repoRoot();

        Optional<Path> currentFile();

        /** Whether the shown document carries an edit pending in Komet. */
        boolean pendingEdit();

        void cloneRepository();

        void fetch();

        void pull();

        void push();

        void commit();

        void history();
    }

    private final Host host;
    private final Label root = value("stampedDocumentRepoRoot");
    private final VBox remotes = new VBox(2);
    private final Label branch = value("stampedDocumentRepoBranch");
    private final Label head = value("stampedDocumentRepoHead");
    private final Label aheadBehind = value("stampedDocumentRepoAheadBehind");
    private final Label fileStatus = value("stampedDocumentRepoFileStatus");
    private final Button push = new Button("Push…");
    private final Button pull = new Button("Pull");
    private final Button fetch = new Button("Fetch");
    private final Button commit = new Button("Commit…");
    private GitOperations.RepositoryState state;

    RepositoryPanel(Host host) {
        this.host = host;
        setId("stampedDocumentRepositoryPanel");
        setText("Repository");
        setCollapsible(true);
        setExpanded(true);
        setAnimated(false);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(3);
        grid.setPadding(new Insets(6, 10, 6, 10));
        ColumnConstraints keys = new ColumnConstraints();
        keys.setMinWidth(70);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(keys, values);
        int r = 0;
        grid.addRow(r++, key("Root"), root);
        remotes.setId("stampedDocumentRepoRemotes");
        grid.addRow(r++, key("Remotes"), remotes);
        grid.addRow(r++, key("Branch"), branch);
        grid.addRow(r++, key("HEAD"), head);
        grid.addRow(r++, key("Upstream"), aheadBehind);
        grid.addRow(r++, key("File"), fileStatus);
        Button clone = new Button("Clone…");
        clone.setId("stampedDocumentRepoClone");
        clone.setTooltip(new Tooltip("Clone a repository into a new or empty folder, through your own git"));
        clone.setOnAction(e -> host.cloneRepository());
        fetch.setId("stampedDocumentRepoFetch");
        fetch.setTooltip(new Tooltip("git fetch, through your own git and its credentials"));
        fetch.setOnAction(e -> host.fetch());
        pull.setId("stampedDocumentRepoPull");
        pull.setTooltip(new Tooltip("git pull --ff-only: refused and reported when it would merge"));
        pull.setOnAction(e -> host.pull());
        push.setId("stampedDocumentRepoPush");
        push.setTooltip(new Tooltip("git push, after showing what is ahead and asking"));
        push.setOnAction(e -> host.push());
        commit.setId("stampedDocumentRepoCommit");
        commit.setTooltip(new Tooltip("Commit the edit pending in Komet as your own commit"));
        commit.setOnAction(e -> host.commit());
        Button history = new Button("History");
        history.setId("stampedDocumentRepoHistory");
        history.setTooltip(new Tooltip("The commits of the open file and of the repository"));
        history.setOnAction(e -> host.history());
        HBox buttons = new HBox(6, clone, fetch, pull, push, commit, history);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(4, 0, 0, 0));
        grid.add(buttons, 0, r, 2, 1);
        setContent(grid);
        showNone();
    }

    private static Label key(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 11));
        label.setStyle("-fx-text-fill: #5f6b7a;");
        return label;
    }

    private static Label value(String id) {
        Label label = new Label();
        label.setId(id);
        label.setFont(Font.font(12));
        label.setWrapText(true);
        return label;
    }

    /** @return the last state read, if any */
    Optional<GitOperations.RepositoryState> state() {
        return Optional.ofNullable(state);
    }

    private void showNone() {
        state = null;
        root.setText("No working tree holds the document's file. Open file… names one, or Clone… makes one.");
        remotes.getChildren().setAll(new Label("—"));
        branch.setText("—");
        head.setText("—");
        aheadBehind.setText("—");
        fileStatus.setText("—");
        for (Button b : new Button[] {fetch, pull, push, commit}) {
            b.setDisable(true);
        }
    }

    /** Re-reads the repository off the FX thread and shows it. */
    void refresh() {
        refresh(null);
    }

    /**
     * Re-reads the repository off the FX thread, shows it, and hands the state read to the host.
     *
     * @param then run on the FX thread with the state, or not at all when none could be read; may be {@code null}
     */
    void refresh(java.util.function.Consumer<GitOperations.RepositoryState> then) {
        Path repo = host.repoRoot();
        if (repo == null) {
            showNone();
            return;
        }
        Path file = host.currentFile().orElse(null);
        boolean pending = host.pendingEdit();
        CompletableFuture.supplyAsync(() -> {
            try {
                return GitOperations.state(repo, file);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((read, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                showNone();
                root.setText(repo + " — git could not be run: " + (failure.getCause() == null ? failure : failure.getCause()).getMessage());
                LOG.warn("Reading the repository {} failed", repo, failure);
                return;
            }
            show(read, pending);
            if (then != null) {
                then.accept(read);
            }
        }));
    }

    private void show(GitOperations.RepositoryState read, boolean pending) {
        state = read;
        root.setText(read.root().toString());
        remotes.getChildren().clear();
        if (read.remotes().isEmpty()) {
            remotes.getChildren().add(new Label("none"));
        }
        for (GitOperations.Remote remote : read.remotes()) {
            Hyperlink link = new Hyperlink(remote.name() + "  " + remote.url());
            link.setPadding(Insets.EMPTY);
            link.setTooltip(new Tooltip("Open " + remote.url() + " in the browser"));
            link.setOnAction(e -> browse(remote.url()));
            remotes.getChildren().add(link);
        }
        branch.setText(read.branch().isEmpty() ? "—" : read.branch());
        head.setText(read.headSha().isEmpty() ? "no commits" : read.headSha() + "  " + read.headText());
        if (read.upstream().isEmpty()) {
            aheadBehind.setText("no upstream branch");
        } else {
            aheadBehind.setText(read.upstream().get() + " — " + read.ahead() + " ahead, " + read.behind() + " behind");
        }
        String file = host.currentFile().map(Path::toString).orElse("");
        fileStatus.setText(file.isEmpty() ? "no document file" : file + " — " + switch (read.status()) {
            case "clean" -> pending ? "clean in the working tree; an edit is pending in Komet" : "clean";
            case "modified" -> "modified in the working tree" + (pending ? "; an edit is pending in Komet" : "");
            case "staged" -> "staged in the index" + (pending ? "; an edit is pending in Komet" : "");
            case "untracked" -> "untracked";
            default -> pending ? "an edit is pending in Komet" : "";
        });
        fetch.setDisable(read.remotes().isEmpty());
        pull.setDisable(read.upstream().isEmpty());
        push.setDisable(read.upstream().isEmpty() && read.remotes().isEmpty());
        commit.setDisable(!pending);
    }

    /**
     * Opens a remote's URL in the browser. An SSH remote ({@code git@host:owner/repo.git}) is
     * shown as its https form, since a browser has nothing to do with the ssh scheme.
     */
    static void browse(String url) {
        String target = url;
        if (url.startsWith("git@")) {
            target = "https://" + url.substring(4).replaceFirst(":", "/").replaceFirst("\\.git$", "");
        } else if (url.startsWith("ssh://")) {
            target = "https://" + url.substring(6).replaceFirst("^[^@]+@", "").replaceFirst("\\.git$", "");
        }
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            LOG.info("Not a browser URL: {}", url);
            return;
        }
        String open = target;
        CompletableFuture.runAsync(() -> {
            try {
                Desktop.getDesktop().browse(new URI(open));
            } catch (Exception e) {
                LOG.warn("Could not open {} in the browser", open, e);
            }
        });
    }
}
