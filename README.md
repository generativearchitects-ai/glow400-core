# glow400-core

The generator behind glow400: it reads the PCML definition of an RPG program, merges a hand-written overlay, validates the result and emits API contracts (OpenAPI, runtime descriptor, mocks, MCP and more).

**Status: early development.** The call model, the PCML parser, and the overlay loader with the API model exist so far. Nothing here is released.

Licence: Apache-2.0.

## Modules

| Module | Content |
|---|---|
| `pcml-model` | Immutable call model of a PCML document, the API model, and the diagnostic types (JDK only) |
| `pcml-parser` | PCML XML to call model, with source positions and diagnostics |
| `pcml-overlay` | Reads `gateway.yaml` and the overlay files, merges them with the PCML into the API model; publishes the JSON Schemas for both file types |

More modules (overlay, validation, codec, emitters, CLI, Maven plugin) will follow, one increment at a time.

## Build

Requires Java 21. The Maven Wrapper is included, so no separate Maven install is needed:

```
./mvnw verify
```

## Use

```java
ParseResult result = PcmlParser.parse(Path.of("MYPROGRAM.pcml"));
result.diagnostics().forEach(System.out::println);
result.document().ifPresent(doc -> doc.programs().forEach(p -> System.out.println(p.name())));
```

The parser never throws for malformed input; problems are returned as diagnostics with stable codes (`PCML001` to `PCML007`).

A customer repository holds an optional `gateway.yaml` and one folder per API under `apis/`, each with its PCML file(s) and one overlay file, `api.yaml`:

```java
RepositoryResult result = OverlayLoader.loadRepository(Path.of("my-repository"));
result.diagnostics().forEach(System.out::println);          // OVL001 ... OVL020, with file, line and column
result.model().ifPresent(model -> model.operations().forEach(op -> System.out.println(op.id())));
```

Operations with errors are left out of the model and reported. The JSON Schemas for editor completion are `api.schema.json` and `gateway.schema.json`, next to `OverlayLoader` in the `pcml-overlay` jar.

## Quality checks

`./mvnw verify` runs the unit tests. `./mvnw -Pmutation verify` also runs mutation testing (PIT) and fails below the threshold set in the parent `pom.xml`.

## Authors

See [AUTHORS](AUTHORS). The list is generated from the git history after each merge into `main`, so contributors are added automatically.
