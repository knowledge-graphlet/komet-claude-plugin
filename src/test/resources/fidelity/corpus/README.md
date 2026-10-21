# Layout fidelity corpus

AsciiDoc documents the layout fidelity harness (`LayoutFidelityIT`) lays out: the Typst oracle's
layout of each is the golden file beside it (`../golden/<name>.layout`), and the greedy JavaFX
pass is scored against it.

- `dev-*.adoc`: ike-lab-documents dev topics (copied 2026-10-20 from `topics/src/docs/asciidoc/topics/dev`).
- `anf-*.adoc`: Analysis Normal Form topics (copied 2026-10-20 from `~/ike-dev/anf`, `topics/anf`;
  the ANF guide is private until its byline is settled — these copies serve the harness only).
- `fda-*.adoc`: the FDA guidance notes of the Typst pipeline spike (ike-lab-documents `topics/ext/regulatory`).
- `synthetic-*.adoc`: stress documents written for the harness (a sixty-row table, lists four
  levels deep, footnote-heavy prose, one paragraph of 140 sentences).

Regenerate the goldens (needs typst 0.15.1 on the path) with
`./mvnw -pl komet-claude-plugin verify -Dit.test=LayoutFidelityIT -Dfidelity.regenerate=true`.
