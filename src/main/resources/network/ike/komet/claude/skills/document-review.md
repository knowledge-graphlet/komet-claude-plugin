---
name: Document review
description: Use when the person asks to review a STAMPed document or passage, check what it says against the knowledge base, see what remains to review, or prepare an affirmation, refutation or comment on lines.
category: Skill
---
Review a STAMPed document the way a careful reviewer would: read what it actually says, check its claims against the knowledge base, and help the person record their judgement, which is theirs to sign.

## 1. Read it

- Orient with `read_document` and `outline_only`; page with `offset` for a long document.
- Find a passage with `find_in_document`, then read its block with `read_block`: every line with the commit that introduced it and the affirmations, refutations and comments on it.
- `stamp_lines` gives the review queue: lines no one has affirmed yet.
- Name blocks as `§3.2` and lines as `L45`. Quote what the tools returned; never paraphrase a passage from memory.

## 2. Check its claims

- For each statement about a concept (its code, its parents, how it is defined), check it with the graph tools: `search` and `concept` for codes and names, `is_a`, `parents` and `ancestors` for hierarchy, `axioms` for definitions.
- Report, per block: what the document says, what the knowledge base says, and whether they agree, with the concepts as Koncept Badges.
- Keep the document's claims, the graph's facts and your own judgement apart, and label the judgement.

## 3. Record the person's judgement, when they ask

- Only when the person asks to affirm, concur with, sign off, dispute, refute or comment on lines, show them the lines first, then call `propose_annotation` with the `kind` and the line `ranges`.
- A refutation needs `text`: the reason, in their words, or your draft that they have accepted.
- The annotation is their signed git commit: the Stamped Document card asks them to confirm it, and nothing is recorded until they do. Tell them it is waiting there. Never say it is recorded.
- It needs a working tree. Without one, point them at **Open file…** or **Clone…** in the card.

## 4. Fix the text, when they ask

- Read the block with `read_block`, then `propose_block_edit` with its whole new AsciiDoc source, changing only what was asked and keeping the form (a listing keeps its delimiters).
- For one sentence of a document stored as vertices, `propose_sentence_edit` with the sentence's exact current text.
- To add a paragraph or a listing, `propose_block_insert` after the block it follows (a listing with its `----` delimiters); to remove a block, `propose_block_delete`. Both show on the card as pending, like an edit.
- To withdraw an annotation they made, `propose_annotation` with `kind: retract` and the annotation commit's SHA as `stamp_lines` lists it, and their reason as `text`; the card asks them to confirm with a signed commit.
- An edit is pending in the card until they **Commit…** it to git or **Discard edit**. It is refused while another author's edit is pending; tell them theirs must be settled first.
