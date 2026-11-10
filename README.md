# glow400-core

The generator behind glow400: it reads the PCML definition of an RPG program, merges a hand-written overlay, validates the result and emits API contracts (OpenAPI, runtime descriptor, mocks, MCP and more).

**Status: early development.** Only the call model and the PCML parser exist so far. Nothing here is released.

Licence: Apache-2.0.

## Modules

| Module | Content |
|---|---|
| `pcml-model` | Immutable call model of a PCML document (JDK only) |
| `pcml-parser` | PCML XML to call model, with source positions and diagnostics |

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
