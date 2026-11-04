/*
 * Copyright © 2025 Integrated Knowledge Management (support@ikm.dev)
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
package network.ike.komet.claude.doc;

/**
 * The widths the cards of a decision open at, in ems of their base size. A line of prose reads
 * best at about 66 characters, about 33 ems, so that is the document's eight-track {@code body}
 * and its twelve-track grid is 50 ems; the assistant beside it holds a single column of
 * conversation, about 30 ems. Two cards at their measures are the "double wide" pair.
 */
public final class ReadingMeasure {

    /** The document's twelve-track grid, in ems. */
    static final double DOCUMENT_EMS = 50;
    /** The conversation's single column, in ems. */
    static final double ASSISTANT_EMS = 30;
    /** What a window takes from its surface: its border, 8, and the surface's scroll bar, 14. */
    private static final double CHROME = 22;

    private ReadingMeasure() {
    }

    /**
     * The width of a document card's window.
     *
     * @param base the surface's base font size
     * @return the window's width, in points
     */
    public static double documentWindow(double base) {
        return DOCUMENT_EMS * base + CHROME;
    }

    /**
     * The width of an assistant card's window beside a document.
     *
     * @param base the surface's base font size
     * @return the window's width, in points
     */
    public static double assistantWindow(double base) {
        return ASSISTANT_EMS * base + CHROME;
    }
}
