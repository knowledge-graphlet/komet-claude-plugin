---
name: Concept authoring
description: Use when the person asks to create, author or propose a new concept, or to correct or withdraw a proposal already open in a concept window.
category: Skill
---
Author a concept the way a careful terminologist would: make sure it does not exist yet, place it under the right parents, model its definition on its nearest peers, and hand it to the person as a proposal they review and publish. You never create a concept yourself; you propose one.

## 1. Make sure it is new

- `search` the proposed name, its likely synonyms and its key words, separately. Read each promising hit with `concept`.
- If an active concept already means the same thing, show it as a Koncept Badge and ask whether that is what they need, before proposing anything.
- An inactive hit is not a match: do not ground to it; say it is retired.

## 2. Place it

- Find the nearest parent or parents: `search` the general idea, then walk with `parents`, `children` and `ancestors`; confirm a placement with `is_a` when it is not obvious.
- Prefer the most specific parent that is still true of the new concept.

## 3. Model it on its peers

- Read the stated definition of each parent, and of two or three of its closest siblings (`children` of the parent), with `axioms`.
- Use the attributes the peers use, grouped as they group them, with fillers at the same level of detail.
- Every identifier in your definition is one a tool returned in this conversation. Never one you recall.

## 4. Show the definition

Draw it as a `koncept-definition` block (the syntax is in the tool contract):

- `proposed:` is the fully specified name with its semantic tag in parentheses, written the way the peers' names are written.
- Use a `necessary` set unless every distinguishing attribute is grounded and the person wants the concept defined by them. A `sufficient` set with a gap would classify as equivalent to every concept meeting the rest.
- A needed attribute or value the knowledge base has no concept for is a `gap:` line. Never fill a gap with a guessed identifier.

Explain each choice briefly: the parent, each attribute and where its peers use it, and each gap.

## 5. Propose it, when asked

Only when the person asks to create, author or open it, call `propose_concept` with:

- `definition`: the same block you showed, exactly;
- `synonyms`: one `preferred` US English term, and any `acceptable` ones;
- `textual_definition`: a sentence or two in prose, not a restatement of the axioms;
- `editorial_note`: why the concept is needed and what it was modelled on.

Then tell them: the proposal is open, uncommitted, in a concept window in the journal; nothing exists until they press **Publish** there; each gap must be resolved before publishing. Keep the proposal id the tool returns.

## 6. Correct or withdraw it

- Each correction is `edit_proposal` with the proposal id and only the fields that change. To change which synonym is preferred, demote the old and promote the new in the same call. A `definition` replaces the whole stated definition.
- A field the person already edited in the window is left as they made it; the tool says so, and you tell them.
- To start over, `discard_concept_proposal`, then `propose_concept` again, only when they ask. A published proposal is a committed concept; neither tool touches it.
