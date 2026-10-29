---
name: Pattern authoring
description: Use when the person asks to create, author or propose a new pattern (a record type with a meaning, a purpose and fields), or to withdraw a pattern proposal already open.
category: Skill
---
Author a pattern the way a careful terminologist would: make sure every concept it names exists and is published, model it on the knowledge base's existing patterns, and hand it to the person as a proposal they review and publish. You never create a pattern yourself; you propose one. A pattern's fields are fixed once it is published, so the proposal must be complete.

## 1. Understand what the pattern is made of

A pattern is:

- a **meaning**: the concept that says what each instance of the pattern is;
- a **purpose**: the concept that says what the pattern is for;
- an ordered list of **fields**, each with its own meaning, purpose and data type.

The person usually names these in prose ("the meaning is Regulatory Classification, the purpose and fields use Device Extension Record"). Restate what you understood as a short table of meaning, purpose and fields before anything else, and ask about anything the request leaves open: a field's purpose or data type, or the order of the fields.

## 2. Model it on its peers

- `search` for patterns of the same kind and read two or three with `pattern`: how their names are written, which concepts serve as purpose, and which data types their fields use.
- `field_data_types` lists the data types a field may have. Pick the one the peers use for the same kind of field; when the person says a field holds a concept, that is the component data type.
- Every identifier you use is one a tool returned in this conversation. Never one you recall.

## 3. Make sure every concept exists

For the meaning, the purpose, and each field's meaning and purpose:

- `search` the name and its likely synonyms; read each promising hit with `concept`.
- An active concept that means the same thing is reused: show it as a Koncept Badge and confirm it is what they mean.
- A concept that does not exist is proposed first, with the **Concept authoring** skill: place it under the nearest parent (say which, and ask when the person has not said), and propose it with `propose_concept`. Propose every missing concept before proposing the pattern.

## 4. Have the concepts published first

Once the missing concepts are proposed, stop and tell the person: the concepts wait in the journal's **Unpublished Changes** panel, where they review and **Publish** them; the pattern comes once they are published, and you will write it when they say to proceed. Do not propose the pattern in the same turn.

If they ask you to write the pattern while the concepts are still unpublished, say that a pattern published before its concepts would point at nothing, and only then pass `allow_unpublished: true`.

## 5. Show the pattern, then propose it when asked

Show the full proposal: the fully qualified name (written like the peers' names), the meaning, the purpose, and each field in order with its meaning, purpose and data type, every concept as a Koncept Badge. Only when the person asks to create, author or proceed, call `propose_pattern` with:

- `name`: the fully qualified name;
- `meaning` and `purpose`: the concepts' identifiers;
- `fields`: each field's `meaning`, `purpose` and `data_type` identifiers, in order;
- `synonyms`: one `preferred` US English term when the name without its tag is not the one they use, and any `acceptable` ones;
- `editorial_note`: what the pattern is for and what it was modelled on.

Then tell them: the proposal is open, uncommitted, in the journal's Unpublished Changes panel; nothing exists until they press **Publish** there. Keep the proposal id the tool returns.

## 6. Withdraw it

To change a proposal, `discard_pattern_proposal` with its id, then `propose_pattern` again with the corrected fields, only when the person asks. A published proposal is a committed pattern; the tool leaves it alone.
