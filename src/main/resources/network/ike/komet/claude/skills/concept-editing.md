---
name: Concept editing
description: Use when the person asks you to make or apply a change to an existing concept: its parents, attributes, role groups, whether it is defined, or its names and definitions.
category: Skill
---
Change an existing concept the way a careful terminologist would: read what is there, change only what was asked, and hand the change to the person as uncommitted work they review and publish. You never commit a change yourself.

## 1. Read what is there

- Resolve the concept with `concept`, and its stated definition with `axioms`. Every identifier you use comes from a tool in this conversation.
- For a change to names, read its descriptions with `concept_semantics` (pattern `Description`): each comes with its own UUID, which is how you name it. Read a description's US English acceptability with `concept_semantics` on that UUID.
- Call `pending_changes` for the concept. A part with a change not yours waiting is the person's work in progress: say so and ask, rather than writing around it.

## 2. Show the change first

- Draw the definition you will leave as a `koncept-definition` block, beside what is stored, and say what changes and why: a stated parent that is redundant, an attribute its peers use, a role group to match its peers.
- For names, list each description you will retire, reword or promote, and each one you will add.
- Make the change only when the person asks you to.

## 3. Write it

- `propose_definition_change`: prefer `operations` for a few changes, each naming what it changes by its concepts. Give a whole `definition` block when you restate most of it. A block is refused for a definition holding what the grammar cannot express; use operations then.
- `propose_description_change`: one call can retire, reword and add. A concept keeps one preferred synonym: to change which, demote the old and promote the new in the same call. Retiring a description retires its acceptability with it.
- Pass a short `rationale`: it is kept with the change.
- A refusal writes nothing. Read what it says, fix the call or ask the person, and try again.

## 4. Hand it over

- Show the before and after the tool returns.
- Tell the person: the change is uncommitted, in the Unpublished Changes tray of the journal under the concept; the Change Review window shows each field before and after; they **Publish** or **Discard** it there. Nothing has changed until they publish.
- Later edits to the same concept join the same change. If they ask you to withdraw it, `discard_change`.
- After they edit it themselves, `pending_changes` shows what is theirs; build on it, do not overwrite it.
