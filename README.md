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

## Design

- **Outbound only.** Komet drives Claude (holds the API key, runs the tool-use
  loop). Claude's tools call back **in-process** over the active window's
  `ViewCalculator`. There is no inbound network surface.
- **Read-only tools:** `concept`, `children`, `parents`, `descendants`,
  `ancestors`, `is_a`, `axioms`, `search`.
- **Hand-rolled Anthropic client** over `java.net.http` (direct API, no SDK).
  Default model `claude-sonnet-4-6`. The API key is stored in
  `PreferencesService.userPreferences()` — per OS user, **never** in the
  knowledge base.
- **UI:** `jfx.incubator.richtext` RichTextArea. Markdown is the working format:
  Claude's markdown is rendered (commonmark) and the raw markdown is available to
  copy into other fields. Chat is ephemeral with save-to-disk.

## Build

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw clean install
```

Inherits `network.ike.platform:ike-parent` (Java 25, `--enable-preview`).
Komet / tinkar versions come from `dev.ikm.komet:komet-bom`.

## Status

Skeleton (compiles). See
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
