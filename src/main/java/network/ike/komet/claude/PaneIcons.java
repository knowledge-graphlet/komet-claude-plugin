package network.ike.komet.claude;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/**
 * The line icons of the assistant's sidebar pane: outlines drawn with a round 1.8-unit stroke on a 24-unit
 * grid, as in the pane's design. A shaped region ({@code -fx-shape}) can only fill a path, which draws these
 * outlines as heavy blobs, so each icon is a stroked {@link SVGPath}; {@code assistant-pane.css} sets the
 * stroke through the {@code assistant-pane-line-icon} style class.
 */
final class PaneIcons {

    static final String COLLAPSE = "M7 6l6 6-6 6M13 6l6 6-6 6";
    /** Two arrows out to opposite corners. */
    static final String MAXIMIZE = "M14 4h6v6M20 4l-7 7M10 20H4v-6M4 20l7-7";
    /** The same arrows pointing in. */
    static final String RESTORE = "M20 10h-6V4M14 10l6-6M4 14h6v6M10 14l-6 6";
    static final String PLUS ="M12 5v14M5 12h14";
    static final String HISTORY = "M4 12a8 8 0 1 0 2.5-5.8L4 8.5M4 4v4.5h4.5M12 8v4.5l3 1.8";
    static final String GEAR = "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"
            + "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 "
            + "1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06"
            + "a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09"
            + "A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 "
            + "1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33"
            + "l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 "
            + "0 4h-.09a1.65 1.65 0 0 0-1.51 1z";
    /** Three dots: zero-length segments, which the round cap of a wide stroke turns into discs. */
    static final String MORE = "M6 12h.01M12 12h.01M18 12h.01";
    static final String PENCIL = "M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4";
    static final String BACK = "M14 6l-6 6 6 6";
    static final String CHEVRON = "M10 6l6 6-6 6";
    static final String SEARCH = "M17 11a6 6 0 1 1-12 0 6 6 0 0 1 12 0zM20 20l-4.5-4.5";
    static final String TRASH = "M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13";

    private static final double GRID = 24;

    private PaneIcons() {
    }

    /**
     * Makes a line icon.
     *
     * @param path the icon's outline, on the 24-unit grid
     * @param size the icon's width and height, in pixels
     * @return a node of exactly that size
     */
    static Node line(String path, double size) {
        SVGPath outline = new SVGPath();
        outline.setContent(path);
        outline.getStyleClass().add("assistant-pane-line-icon");
        // The grid's own square, so the icon's bounds are the grid's whatever the outline covers of it.
        Rectangle grid = new Rectangle(GRID, GRID, Color.TRANSPARENT);
        Group scaled = new Group(grid, outline);
        scaled.getTransforms().add(new Scale(size / GRID, size / GRID));
        Group icon = new Group(scaled);
        icon.setMouseTransparent(true);
        return icon;
    }
}
