# ANTLR 4 (Optimized Fork)

[![Java 8+](https://img.shields.io/badge/Java-8%2B-4c7e9f.svg)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/maven-central/v/me.sunlan/antlr4.svg)](https://central.sonatype.com/artifact/me.sunlan/antlr4)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE.txt)
[![Build & Test](https://github.com/daniellansun/antlr4/actions/workflows/build-test.yml/badge.svg?branch=opt)](https://github.com/daniellansun/antlr4/actions/workflows/build-test.yml?query=branch%3Aopt)
[![Codecov](https://codecov.io/gh/daniellansun/antlr4/branch/opt/graph/badge.svg)](https://codecov.io/gh/daniellansun/antlr4)

**ANTLR** (ANother Tool for Language Recognition) is a powerful parser generator for reading, processing, executing, or translating structured text or binary files. From a grammar, ANTLR generates a lexer and parser that build parse trees along with listener and visitor interfaces for walking them.

## About This Fork

This repository (`daniellansun/antlr4`, default branch `opt`, Maven group `me.sunlan`) is an actively maintained continuation and extension of the **optimized fork of ANTLR 4** originally created by Sam Harwell at Tunnel Vision Laboratories, LLC. Targeting Java 8+, it is engineered for high-throughput, low-allocation, production-scale JVM recognizers—most notably Apache Groovy's Parrot parser.

Key additions beyond the [reference ANTLR 4 release](https://github.com/antlr/antlr4) include:

* **Grammar and runtime features:** shared rule `baseContext`, parse-tree-preserving `@leftfactor` transformation, indirect left-recursion elimination, rule versioning (`@version` / `@RuleVersion`), compile-time `@NotNull`/`@Nullable` validation (`NullUsageProcessor`), bare carriage-return (`\r`) line-ending support, and strong backwards compatibility across runtime upgrades.
* **Runtime performance optimizations:** optional full-context DFA caching, tail-call elimination, adaptive DFA edge maps with lock-free ASCII/wide lexer tables, dense LL(1) and precedence LL(1) tables, cursorless warm SLL prediction, unified `EpsilonClosure` / `ReachComputation` / O(n) zero-sort `ConflictDetector` engines, `CodePointLexerRunner` with in-place `-> skip` fusion, predicate-guarded ASCII lexer edge caching (`LexerPredEdgeTable`), 256-slot chunked `TokenList`, monomorphic `PredictionContextCache`, and internally shaded HPPC primitive collections (zero HPPC types exposed on any `public` or `protected` API).
* **Java static analysis and code generation:** compact `Parser` hooks tuned to HotSpot C2 inlining thresholds, ALL(\*)-sound EBNF exit LL(1) recovery, compact 1–3 alternative LL(1) bitmask tests, deferred labeled-alternative context allocation, flat `tableswitch` `action()`/`sempred()` dispatch, and single-buffer ATN deserialization.

For the complete technical reference, see [doc/optimized-fork.md](doc/optimized-fork.md). Detailed release notes and empirical benchmark reports are available under [doc/release-notes/](doc/release-notes/) and [doc/perf-reports/](doc/perf-reports/).

## Maven Artifacts and Quick Start

Published releases are available on Maven Central under group ID `me.sunlan` (Java 8 bytecode):

| Artifact | Purpose |
| --- | --- |
| `me.sunlan:antlr4-runtime` | Self-contained runtime library (HPPC is minimized and relocated internally; zero transitive HPPC dependency) |
| `me.sunlan:antlr4` | ANTLR 4 tool (grammar compiler and Java code generator) |
| `me.sunlan:antlr4-maven-plugin` | Maven plugin for compiling `.g4` grammars during the build |
| `me.sunlan:antlr4-annotations` | `@NotNull` / `@Nullable` annotations and `NullUsageProcessor` compile-time validator |

Check the Maven Central badge above or [GitHub Releases](https://github.com/daniellansun/antlr4/releases) for the latest version.

### Maven

```xml
<dependencies>
    <dependency>
        <groupId>me.sunlan</groupId>
        <artifactId>antlr4-runtime</artifactId>
        <version>${antlr4.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>me.sunlan</groupId>
            <artifactId>antlr4-maven-plugin</artifactId>
            <version>${antlr4.version}</version>
            <executions>
                <execution>
                    <goals>
                        <goal>antlr4</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### Gradle

```groovy
plugins {
    id 'java'
    id 'antlr'
}

dependencies {
    antlr "me.sunlan:antlr4:${antlr4Version}"
}
```

## Repository Modules

This repository ships the optimized **Java** runtime and code-generation target (see [doc/targets.md](doc/targets.md) for separate C# and TypeScript target repositories):

* `runtime/Java` (`me.sunlan:antlr4-runtime`) — Optimized Java 8 runtime with shaded HPPC internals
* `runtime/JavaAnnotations` (`me.sunlan:antlr4-annotations`) — Nullability annotations and `NullUsageProcessor`
* `tool` (`me.sunlan:antlr4`) — Grammar analysis pipeline and Java target code generator
* `antlr4-maven-plugin` (`me.sunlan:antlr4-maven-plugin`) — Maven build plugin
* `antlr4-testgen-maven-plugin` — Internal plugin generating runtime test suites from templates
* `runtime-testsuite` — Cross-runtime test templates and harness descriptors
* `perf-testsuite` — Serial/parallel Groovy & Java correctness checks and JMH microbenchmarks

## Building and Testing from Source

**Prerequisites:** JDK 8+ and Maven 3.x (setting `MAVEN_OPTS="-Xmx1g"` is recommended).

### Install to Local Maven Repository

Unsigned local installs should pass `-Dgpg.skip=true` (the root POM binds `maven-gpg-plugin` to `verify` for Maven Central releases). When building a brand-new version that does not yet exist in `~/.m2/repository`, install the parent POM and internal plugins first:

```bash
export MAVEN_OPTS="-Xmx1g"
mvn install -N -Dgpg.skip=true
mvn clean install -pl runtime-testsuite,antlr4-testgen-maven-plugin -DskipTests -Dgpg.skip=true
mvn clean install -pl runtime/JavaAnnotations,runtime/Java,tool,antlr4-maven-plugin -DskipTests -Dgpg.skip=true
mvn clean install -pl perf-testsuite -DskipTests -Dgpg.skip=true
```

Once a version's plugins are installed locally, a standard reactor build works directly:

```bash
mvn clean install -DskipTests -Dgpg.skip=true
```

### Run Unit Tests

To run the test suites (matching CI configuration):

```bash
MAVEN_OPTS="-Xmx1g -Djava.awt.headless=true" mvn test \
  -Dantlr.testinprocess=true \
  -Djava.awt.headless=true \
  -DJDK_SOURCE_ROOT=../runtime/Java/src \
  -Dperformance.package=
```

Add `-DENABLE_JACOCO=true` to generate JaCoCo coverage reports for `runtime/Java` and `tool`.

## Documentation and Resources

* [Optimized fork technical reference](doc/optimized-fork.md)
* [Release notes (GitHub)](https://github.com/daniellansun/antlr4/releases) & [local release notes](doc/release-notes/)
* [Performance verification reports](doc/perf-reports/)
* [Getting started with ANTLR 4](doc/getting-started.md)
* [Documentation index](doc/index.md)
* [Java target documentation](doc/java-target.md)
* [Runtime libraries and targets](doc/targets.md)
* [FAQ](doc/faq/index.md) & [v3 to v4 migration guide](doc/faq/general.md)
* [Building ANTLR](doc/building-antlr.md)
* [Official ANTLR website](https://www.antlr.org/) & [Java API documentation](https://www.antlr.org/api/Java/index.html)
* [Reference (upstream) ANTLR 4 repository](https://github.com/antlr/antlr4)
* [Grammar collection (`antlr/grammars-v4`)](https://github.com/antlr/grammars-v4)
* [ANTLR v3](https://www.antlr3.org/)

## The Definitive ANTLR 4 Reference

For a thorough introduction to building parsers, listeners, visitors, and language tools with ANTLR 4, see [*The Definitive ANTLR 4 Reference*](https://pragprog.com/titles/tpantlr2/the-definitive-antlr-4-reference/) by Terence Parr, along with its [companion book source code](https://pragprog.com/titles/tpantlr2/source_code).

## Authors, Maintainers, and Major Contributors

* [Terence Parr](https://www.cs.usfca.edu/~parrt/) — ANTLR project lead (University of San Francisco)
* [Sam Harwell](https://tunnelvisionlabs.com/) — ANTLR 4 tool co-author and creator of the optimized fork (along with the optimized Java, C#, and TypeScript targets)
* [Daniel Sun](https://sunlan.me) — Maintainer and lead developer of the `me.sunlan` optimized fork continuation (`daniellansun/antlr4`)
* Burt Harris — Co-author of the optimized TypeScript target
* [Ben Hamilton](https://github.com/bhamiltoncx) — Full Unicode support

## License

ANTLR 4 is licensed under the BSD-3-Clause license. See [LICENSE.txt](LICENSE.txt) for details.
