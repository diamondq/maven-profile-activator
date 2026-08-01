# Maven Profile Activator — Documentation

**Version:** 1.3.1
**Minimum Java:** 8
**Minimum Maven:** 3.5.2
**License:** Apache License 2.0

---

## What Is This?

Maven Profile Activator is a Maven extension that replaces the default profile selector with one that supports **script-based profile activation**. Instead of being limited to Maven's built-in activators (JDK, OS, property, file), you can write declarative activation scripts that combine conditions with `and`, `or`, and `not`, and evaluate file existence, properties, JDK versions, and type markers.

This extension works **alongside** all of Maven's built-in profile activators — profiles using standard `<activation>` blocks continue to work unchanged.

---

## Installation

Place the `maven-profile-activator` JAR on Maven's extension classpath so it is loaded during every build:

```xml
<!-- .mvn/extensions.xml -->
<extensions xmlns="http://maven.apache.org/EXTENSIONS/1.0.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/EXTENSIONS/1.0.0
                                http://maven.apache.org/xsd/core-extensions-1.0.0.xsd">
  <extension>
    <groupId>com.diamondq.maven</groupId>
    <artifactId>maven-profile-activator</artifactId>
    <version>1.3.1</version>
  </extension>
</extensions>
```

After adding this file, every `mvn` invocation in the project will load the custom profile selector.

---

## Quick Start

Define a profile in your POM that uses the special `[DIAMONDQ]` activation property to supply an activation script:

```xml
<profile>
  <id>my-custom-profile</id>
  <activation>
    <property>
      <name>[DIAMONDQ]</name>
      <value>file(profiles/my-marker)</value>
    </property>
  </activation>
  <!-- ... profile config ... -->
</profile>
```

This profile activates when the file `profiles/my-marker` exists in the project directory.

---

## Activation Script Syntax

Scripts are written as nested function calls. Every script must be enclosed in parentheses:

```
keyword(arguments)
```

Multiple arguments are comma-separated. Nested calls are fully supported, enabling arbitrary depth of logical composition.

### Available Keywords

#### Logical Operators

| Keyword | Arguments | Description |
|---------|-----------|-------------|
| `and`   | `expr, expr, ...` | Returns `true` only if **all** child expressions return `true`. Short-circuits on first `false`. |
| `or`    | `expr, expr, ...` | Returns `true` if **any** child expression returns `true`. Short-circuits on first `true`. |
| `not`   | `expr` | Returns the inverse of the child expression. |

#### Conditions

| Keyword | Arguments | Description |
|---------|-----------|-------------|
| `file(path)` | A file path (may use `${basedir}` and property interpolation) | Returns `true` if the file **exists**. |
| `missing(path)` | A file path | Returns `true` if the file does **not** exist. |
| `property(key)` | Property name | Returns `true` if the property is set. |
| `property(key=value)` | Property name and expected value | Returns `true` if the property equals the given value. |
| `property(!key)` | Property name with `!` prefix | Returns `true` if the property is **not** set. |
| `type(name)` | Type identifier | Returns `true` if any file in `profiles/` starts with `type-{name}`. |
| `jdk(version)` | A version number or comparison (`<8`, `>=11`, `<=17`) | Compares against the `type-java-XX` marker found in the `profiles/` directory. |
| `profile(name)` | Profile identifier | Checks for a file at `profiles/{name}`. If not found, walks up parent directories checking `parentProfiles/{name}`. |

---

## Examples

### Multiple Conditions (AND)

```xml
<activation>
  <property>
    <name>[DIAMONDQ]</name>
    <value>and(file(profiles/build-docker), property(env=production))</value>
  </property>
</activation>
```

Activates only if `profiles/build-docker` exists **and** the `env` property equals `production`.

### Negation (NOT)

```xml
<activation>
  <property>
    <name>[DIAMONDQ]</name>
    <value>not(property(skip.my-profile=true))</value>
  </property>
</activation>
```

Activates unless `skip.my-profile=true` is set.

### Complex Nesting

```xml
<activation>
  <property>
    <name>[DIAMONDQ]</name>
    <value>and(or(file(profiles/cond-a), file(profiles/cond-b)), not(missing(profiles/required)))</value>
  </property>
</activation>
```

Activates if:
1. Either `profiles/cond-a` **or** `profiles/cond-b` exists, **and**
2. `profiles/required` exists (i.e., it is not missing).

### JDK Version Detection

```xml
<activation>
  <property>
    <name>[DIAMONDQ]</name>
    <value>jdk(>=11)</value>
  </property>
</activation>
```

This relies on a marker file named `type-java-XX` (e.g., `type-java-17`) in the project's `profiles/` directory. The script compares that marker version against the given threshold.

Supported operators: `=`, `<`, `<=`, `=<`, `>`, `>=`, `=>`. A bare number performs equality.

### Type Matching

```xml
<activation>
  <property>
    <name>[DIAMONDQ]</name>
    <value>type(docker)</value>
  </property>
</activation>
```

Activates if any file in `profiles/` starts with `type-docker` (e.g., `profiles/type-docker-compose.yml`).

### Profile Search (Up the Directory Tree)

```xml
<activation>
  <property>
    <name>[DIAMONDQ]</name>
    <value>profile(shared-build)</value>
  </property>
</activation>
```

First checks `profiles/shared-build` in the current project. If not found, walks up the directory tree checking `parentProfiles/shared-build` in each ancestor until the file system root.

### Automatic Skip Property

Every DiamondQ activation script is automatically wrapped with:

```
and(not(property(skip.{profileId}=true)), <your-script>)
```

This means any profile can be disabled at runtime by passing:

```bash
mvn clean install -Dskip.my-custom-profile=true
```

---

## Path Resolution

File paths in scripts are resolved using Maven's `PathTranslator`, which aligns relative paths to the project base directory. You can also use property interpolation:

- `${basedir}` — the project's base directory
- Any property from `projectProperties`, `userProperties`, or `systemProperties`

```xml
<value>file(${basedir}/profiles/custom-marker)</value>
```

Note: `${project.basedir}` is intentionally **not** supported — only `${basedir}` is available.

---

## Dependency Tracking & Caching

The activator caches profile activation results to avoid redundant work across builds. It tracks **dependencies** for each evaluation (file existence, property values, project directory). If any dependency becomes invalid between evaluations, the cache is cleared and all profiles are re-evaluated.

This means:
- Creating or deleting a marker file triggers a full re-evaluation.
- Changing a property used in a script triggers a full re-evaluation.
- Running a build in a different project directory triggers a full re-evaluation.

---

## Detecting the Extension in POMs

The extension injects a system property into the activation context:

```
[DIAMONDQ-PROFILE-ACTIVATOR] = true
```

You can use standard Maven property activation to conditionally configure your POM based on whether the extension is present:

```xml
<profile>
  <id>with-diamondq</id>
  <activation>
    <property>
      <name>[DIAMONDQ-PROFILE-ACTIVATOR]</name>
    </property>
  </activation>
  <!-- ... -->
</profile>
```

---

## Debugging

Enable detailed logging during a build:

```bash
mvn clean install -DDiamondQProfileSelectorDebug=true -X
```

This prints every profile evaluation, script parsing step, and dependency check to the build log. Example output:

```
[DIAMONDQ Profile Activator] getActiveProfiles([profile-a, profile-b], ..., ...)
[DIAMONDQ Profile Activator]   Context has changed. Checking all 3 dependencies...
[DIAMONDQ Profile Activator]   Checking if profile profile-a is active?
[DIAMONDQ Profile Activator]   recursiveProcess(and(file(profiles/marker), property(env=prod)))
[DIAMONDQ Profile Activator]   Keyword: |and| -> |file(profiles/marker), property(env=prod)|
[DIAMONDQ Profile Activator]   file exists so true -> /project/profiles/marker
[DIAMONDQ Profile Activator]   property "env" -> "prod" equals to "prod" so true
[DIAMONDQ Profile Activator]     Activating profile profile-a
[DIAMONDQ Profile Activator] Activated profiles: profile-a
```

---

## Project Structure

| File | Description |
|------|-------------|
| `DiamondQProfileSelector` | The Maven extension entry point. Replaces the default profile selector via Eclipse Sisu (`@Named("extra")`, `@Priority(999)`). |
| `CommonProfileSelector` | Core logic: script parsing, dependency tracking, caching, and recursive evaluation of activation conditions. |
| `Dependency` | Abstract base with factory methods (`onFile`, `onProperty`, `onNoStartsWith`, `onFail`, `onProjectDir`) for tracking conditions that may invalidate the cache. |
| `ActivatorLogger` | Minimal logging interface decoupled from Plexus/Maven internals. |
| `PlexusActivatorLogger` | Adapter that bridges `ActivatorLogger` to Plexus `Logger`. |

---

## Building the Project

```bash
# Full build
mvn clean install

# Run tests
mvn test

# Check code style
mvn checkstyle:check

# Run a single test class
mvn test -Dtest=TestClassName
```

---

## License

Apache License, Version 2.0 — see the [LICENSE](LICENSE) file for details.
