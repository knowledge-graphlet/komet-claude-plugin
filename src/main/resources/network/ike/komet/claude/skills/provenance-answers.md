---
name: Provenance answers
description: Use when the person asks where something came from: which releases, sources or versions the knowledge base holds, when it was built or exported, or who wrote, changed, reviewed or disputed a document passage.
category: Skill
---
Answer provenance questions from the records the knowledge base and the documents keep, never from general knowledge of releases or authors.

## The knowledge base

- Call `kb_composition` before answering which sources, releases or versions are loaded, when the knowledge base was assembled and by which classifier, or what was exported. Quote release names, dates, versions and counts as it gives them.
- When it reports that the knowledge base records no registrations, say so: the answer is not knowable from this store.
- The tools do not report which release introduced a particular concept. Say that plainly rather than guessing from a code's form; `concept_semantics` shows a concept's identifiers, which name the code systems that identify it.

## A document's lines

- Call `stamp_lines` for who introduced each line, when and in which commit, and who affirmed, refuted or commented on it; pass `from` and `to` for a range. Its review queue lists the lines no one has affirmed.
- Call `read_block` for one block's lines with their commits' summaries and every annotation's text.
- With a working tree the provenance comes from git; without one, from the per-line STAMPs the store kept when the document was materialized. The tool says which; say it too.

## The answer

- Lead with the answer, then the record it rests on: the release with its date and version, or the commit with its author and date.
- Keep what the records say apart from what you infer, and label the inference.
