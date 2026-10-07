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
package network.ike.komet.claude.koncept;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link NidFree#assertNoNid} is what every store-backed test of
 * {@code IKE-Network/ike-issues#1170} leans on, so it is tested itself: it must find a nid in
 * each form one has been written in, and it must pass the text that replaces them.
 */
class NidFreeTest {

    private static final String UUID_A = "02018e5a-46ba-5297-92f1-6931b9f98a12";

    @Test
    void aNidInAnyOfItsWrittenFormsIsFound() {
        for (String text : new String[]{
                "English Language  [nid=-2147483621]",           // the tools' identifier fallback
                "Language: English Language [nid -2147483621]",  // the typed-identifier fallback
                "Concept under review: English Language  (nid -2147483621).",
                "k:nid=-2147483621[English Language]",           // the compose token fallback
                "-2147483621",                                   // the OrNid name fallback
                "[0] <-2147483621>",                             // the store's default text
                "LongIdList[Part of <-2147483500>, Role type <-2147483499>]",
                "nid: 7",
                "<42>"}) {
            assertThrows(AssertionFailedError.class, () -> NidFree.assertNoNid("the text", text), text);
        }
    }

    @Test
    void aGivenNidIsFoundEvenWhenItIsNotInAKnownForm() {
        assertThrows(AssertionFailedError.class, () -> NidFree.assertNoNid("the text", "upload k-1234567.png", 1234567));
        assertDoesNotThrow(() -> NidFree.assertNoNid("the text", "upload k-" + UUID_A + ".png", 1234567));
    }

    @Test
    void textThatIdentifiesByPublicIdPasses() {
        for (String text : new String[]{
                "English Language  [" + UUID_A + "]",
                "Language: English Language [UUID " + UUID_A + "]",
                "Diabetes mellitus [SCTID 73211009]",
                "k:uuid=" + UUID_A + "[English Language]",
                "[Identifier Source] of <English Language> for [Identifier Source]",
                "   [0]➞[3] Definition root\n     [3]➞[2] Necessary set\n",
                "[9 semantics]",
                "unidentified component",
                UUID_A}) {
            assertDoesNotThrow(() -> NidFree.assertNoNid("the text", text), text);
        }
    }
}
