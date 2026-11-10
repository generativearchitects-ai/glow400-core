# glow400-core

Generator for glow400: PCML model, parser, overlay, validation, codec, emitters, CLI and Maven plugin. Licence: Apache-2.0.

## Context

Shared rules, decisions, specs and kickoff briefs live in the `glow400-docs` repository. Clone it next to this repository (`../glow400-docs`) and read, in this order:

1. `../glow400-docs/CLAUDE.md`
2. `../glow400-docs/decisions/decision-log.md`
3. `../glow400-docs/specs/core-design.md`
4. The current brief in `../glow400-docs/kickoff/`

If `../glow400-docs` is not available, stop and ask for it.

## Modules (target)

| Module | Content |
|---|---|
| `pcml-model` | Immutable call model and API model |
| `pcml-parser` | PCML XML to call model, with source positions |
| `pcml-overlay` | Overlay schema, loading, defaults, merge |
| `pcml-validation` | Rules and diagnostics |
| `pcml-codec` | Wire encoding and decoding, shared with the gateway |
| `pcml-emit-openapi` | OpenAPI 3.1 |
| `pcml-emit-descriptor` | Runtime descriptor (JSON) |
| `pcml-emit-mock` | Mock definitions |
| `pcml-emit-java` | DTOs with `jakarta.validation` |
| `pcml-emit-mcp` | MCP server definition on top of the OpenAPI schema |
| `pcml-bundle` | Bundle packaging |
| `pcml-cli` | Command-line front end |
| `pcml-maven-plugin` | Maven wrapper |

Create a module only when the increment in hand needs it.

## Repository-specific rules

- No dependency on JT400, Spring or any web framework anywhere in this repository.
- `pcml-model` depends on the JDK only.
- The parser is lossless: PCML attributes that are not modelled explicitly are preserved in an attribute map.
- Test PCML files are synthetic and live under `src/test/resources/pcml/`.
- Build and test: `./mvnw verify` from the repository root.
