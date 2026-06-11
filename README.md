# Komet Claude Plugin

<a href="https://ike.network">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://ike.network/brand/powered-by/powered-by-ike-color-on-dark.svg">
    <img alt="Powered by IKE" height="28" src="https://ike.network/brand/powered-by/powered-by-ike-color-on-light.svg">
  </picture>
</a>

In-Komet Claude assistant — a Komet plugin (`KometNodeFactory` panel) that drives
an LLM (Claude) dialog whose read-only tools execute *in-process* over the live
knowledge graph.
In-Komet Claude assistant — a Komet plugin contributed as a knowledge-layout
`KlToolArea` and summoned as a window in the **Journal workspace**, driving an LLM
(Claude) dialog whose read-only tools execute *in-process* over the live knowledge
graph.

## Design

- **Outbound only.** Komet drives Claude (holds the API key, runs the tool-use
  loop). Claude's tools call back **in-process** over the active window's
  `ViewCalculator`. There is no inbound network surface.
- **Contribution:** a `dev.ikm.komet.layout.area.KlToolArea` (over
  `SupplementalAreaBlueprint`), discovered via `ServiceLoader` and offered on the
  Journal workspace "+" menu, hosted in a non-entity chapter window. Replaces the
  legacy `KometNodeFactory`, which is only reachable in the classic tab UI.
- **Read-only tools:** `concept`, `children`, `parents`, `descendants`,
  `ancestors`, `is_a`, `axioms`, `search`. Results render `name [SCTID …]` when a
  concept carries a SNOMED identifier, falling back to its UUID.
- **Hand-rolled Anthropic client** over `java.net.http` (direct API, no SDK).
  Default model `claude-sonnet-4-6`. The API key is stored in
  `PreferencesService.userPreferences()` — per OS user, **never** in the
  knowledge base.
- **UI:** `jfx.incubator.richtext` RichTextArea. Markdown is the working format:
  Claude's markdown is rendered (commonmark, incl. GFM tables) and the raw markdown
  is available to copy into other fields. Chat is ephemeral with save-to-disk.

## Layout check & chat areas

Beyond the Journal "+" assistant, the plugin contributes placeable **supplemental
areas** that appear in the Knowledge Layout Editor's **Controls** palette (drag into
a section of an item's layout) and persist by factory class name:

- **`ClaudeCheckArea`** — a *check area*: asks Claude whether the focused concept
  meets an author-set criterion, grounding via the read-only graph tools, and shows
  a green (PASS) / red (FAIL) status. The verdict is structured through a forced
  `report_result` tool rather than parsed from prose.
- **`ChatArea`** — a slim, embeddable chat (no conversations rail), reusing the same
  `AnthropicClient` / `GraphTools` / Markdown rendering as the full assistant.

Both extend the shared `dev.ikm.komet.layout_engine.blueprint.AbstractCheckArea` /
`SupplementalAreaBlueprint` seams in `knowledge-layout`, reuse the per-OS-user API
key/model, and are discovered cross-layer via `KlSupplementalArea.Factory`
(`PluggableService`). A sibling rules-engine check, `EvreteCheckArea` (backed by the
`RuleService` SPI), ships in `knowledge-layout` itself.

See IKE-Network/ike-issues#588.

## Build

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw clean install
```

Inherits `network.ike.platform:ike-parent` (Java 25, `--enable-preview`).
Komet / tinkar versions come from `dev.ikm.komet:komet-bom`.

## Status

Implemented and live-tested in the Komet Journal workspace against a SNOMED store
(grounded tool-use answers). See
[IKE-Network/ike-issues#562](https://github.com/IKE-Network/ike-issues/issues/562).

## License

Apache License 2.0.
<!-- BEGIN ike-managed: developer-setup -->

## Developer Setup

New to IKE development? The
[Developer Environment guide](https://ike.network/ike-tooling/ike-build-standards/developer-environment.html)
covers IDE configuration, JDK 25 setup, and the tooling conventions
every IKE workspace expects — start there before your first build.
<!-- END ike-managed: developer-setup -->
