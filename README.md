<p align="center">
  <img src="docs/assets/slay400-banner.svg" alt="slay400. RPG slay: turn your IBM i programs into REST, async and AI-ready APIs." width="100%">
</p>

# slay400-core

The project was called glow400 until 2026-11-16.

The generator behind slay400: it reads the PCML definition of an RPG program, merges a hand-written overlay, validates the result and emits API contracts (OpenAPI, runtime descriptor, mocks, MCP and more).

**Status: early development.** The call model, the PCML parser, the overlay loader with the API model, the codec and the static validation exist so far. Nothing here is released.

Licence: Apache-2.0. The slay400 name and logo are not licensed under Apache-2.0; see `docs/assets/README.md`.

## Modules

| Module | Content |
|---|---|
| `pcml-model` | Immutable call model of a PCML document, the API model, and the diagnostic types (JDK only) |
| `pcml-parser` | PCML XML to call model, with source positions and diagnostics |
| `pcml-overlay` | Reads `gateway.yaml` and the overlay files (format v0.6), merges them with the PCML into the API model; publishes the JSON Schemas for both file types |
| `pcml-codec` | Converts between API values and the values of a program call (scalars, structs, arrays); depends on `pcml-model` and the JDK only |
| `pcml-validation` | Static validation of an API model: encodability (`OVL003`) and the advisory warnings `OVL005` and `OVL006` |
| `pcml-contract` | The consumer-visible surface of an API (fields, types, limits, statuses), shared by the OpenAPI emitter and the comparison of releases |
| `pcml-diff` | Breaking-change detection between a released and a current repository (`API002` to `API006`) |
| `pcml-emit-descriptor` | Writes `descriptor.json` (schema version 1), the file the gateway reads, and reads it back into the API model; uses the streaming API of Jackson (`jackson-core`) to read |
| `pcml-report` | Writes diagnostics as text for people, JSON for scripts (`reportSchemaVersion` 1) or SARIF 2.1.0 for pull-request annotations |
| `pcml-mock` | Mock definitions (`apis/<FOLDER>/mock/<operation id>.yaml`): `MockProgram`, the evaluator that stands in for a program call between `encode` and `decode`; the `mock` emitter, which writes `mock/<api>.json` (`mockSchemaVersion` 1); and `MockReader`, which reads it back. No HTTP server and no sleeping: the delay is data |
| `pcml-scaffold` | Writes a first `api.yaml` from PCML and the `gateway.yaml` of `init`, and edits parameter entries of an `api.yaml` in place (`direction`, `type`, `name`) without rewriting the file, checking the result by loading it again (`SCF001` to `SCF003`). Text in, text out: no I/O |
| `pcml-cli` | The command-line tool: `init`, `annotate`, `validate`, `generate`, `bundle` and `diff`, in one runnable jar (`java -jar pcml-cli/target/pcml-cli-<version>.jar`) |
| `pcml-bundle` | Packs the descriptor, the OpenAPI documents and the PCML and overlay files into one deterministic, checksummed `.rpgapi` zip file, and reads it back as untrusted input (`BDL001` to `BDL006`) |
| `pcml-emit-openapi` | Writes one OpenAPI 3.1 document per API, `openapi/<api name>.yaml` |
| `pcml-http` | The HTTP binding, without a web framework: routes (`RouteTable`), reading a request into API values and writing the JSON response (`HttpBinding`), and RFC 9457 problem details (`ProblemDetails`), all agreeing with the OpenAPI document |
| `pcml-tasks` | The four pipeline tasks (`validate`, `generate`, `bundle`, `diff`) that the command-line tool and the Maven plugin both call |
| `pcml-maven-plugin` | The Maven plugin, goal prefix `pcml`: `pcml:validate`, `pcml:generate`, `pcml:bundle`, `pcml:diff` |
| `pcml-bom` | The bill of materials: one import that sets the version of every module above (see "Use the artifacts") |

Emitters are discovered with `java.util.ServiceLoader` (`Emitter` in `pcml-model`). More modules (a Java emitter) will follow, one increment at a time.

## Build

Requires Java 21. The Maven Wrapper is included, so no separate Maven install is needed:

```
./mvnw verify
```

## Use the artifacts

Every module is published to Maven Central (decision 91), and `pcml-bom` sets all their versions at once. Import it, then name
the modules you need without a version:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>ai.generativearchitects.slay400</groupId>
      <artifactId>pcml-bom</artifactId>
      <version>VERSION</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>ai.generativearchitects.slay400</groupId>
    <artifactId>pcml-bundle</artifactId>
  </dependency>
</dependencies>
```

A `-SNAPSHOT` version is published to the Central snapshot repository after every build of `main`; to use one, add the
repository `https://central.sonatype.com/repository/maven-snapshots/` with snapshots enabled.

## Release

A push to `main` runs the `snapshot` workflow; a tag `v<version>` on a commit that is in `main` runs the `release` workflow, which signs and uploads the
release for validation, and the owner publishes it in the Central Portal. Both run in the protected GitHub environment
`release`. Everything that can be checked without the accounts is checked in every build (`build` workflow): the
`release` profile stages all modules without signing or uploading, `scripts/check_staged_artifacts.py` checks the files, the
POM elements and the licence files, a small project in `consumer-check/` is built against the staged files the way the
gateway will, and `scripts/check-reproducible.sh` builds twice and compares the checksums. To run the first part yourself:

```
./mvnw install -DskipTests                  # an ordinary build first: it makes the test jars that some modules share
./mvnw -Prelease -Dgpg.skip=true -DskipUpload=true -DskipTests clean deploy
python3 scripts/check_staged_artifacts.py target/central-deferred
```

To set the version of a release in every POM, including `pcml-bom`: `./mvnw versions:set -DnewVersion=<version>
-DprocessAllModules=true -DgenerateBackupPoms=false`; `scripts/check_release_tag.py v<version>` checks the result.

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
result.diagnostics().forEach(System.out::println);          // OVL001 ... OVL021, with file, line and column
result.model().ifPresent(model -> model.operations().forEach(op -> System.out.println(op.id())));
```

```java
List<Diagnostic> findings = Validator.validate(result.model().orElseThrow());      // OVL003, OVL005, OVL006

OperationCodec codec = OperationCodec.of(operation);                               // refuses an operation with OVL003 errors
EncodeResult call = codec.encode(Map.of("orderNumber", new ApiValue.Text("1042")), Map.of("P0ENV", "PRD"));
DecodeResult response = codec.decode(programOutput);                                // problems VAL001 ... VAL103, never thrown
```

The codec never alters data silently: nothing is truncated, rounded or clamped, and a value that does not fit is a problem
whose message never contains the value. A char field's `length` counts bytes of the host encoding, so the codec supports
single-byte CCSIDs only for now.

From a folder of PCML files to a repository that validates, with the command-line tool:

```
java -jar pcml-cli-<version>.jar init        # gateway.yaml, and an api.yaml for every folder under apis/ that has PCML and no overlay
java -jar pcml-cli-<version>.jar annotate    # asks which way each inputoutput parameter goes, and writes the answers into api.yaml
java -jar pcml-cli-<version>.jar validate
```

`init` never overwrites or deletes a file. `annotate` changes only the parameter entries of an `api.yaml`: comments, blank
lines, key order, quoting, indentation and line endings stay as they are.

Operations with errors are left out of the model and reported. The JSON Schemas for editor completion are `api.schema.json` and `gateway.schema.json`, next to `OverlayLoader` in the `pcml-overlay` jar.

## Quality checks

`./mvnw verify` runs the unit tests. `./mvnw -Pmutation verify` also runs mutation testing (PIT) and fails below the threshold set in the parent `pom.xml`.

## Authors

See [AUTHORS](AUTHORS). The list is generated from the git history after each merge into `main`, so contributors are added automatically.
