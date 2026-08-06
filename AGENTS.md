# AGENTS.md - Maven Profile Activator Development Guide

## Project Overview

This is a Java/Maven extension that provides flexible profile activation via script-based conditions. It extends Maven's default profile selector with custom activators supporting file existence, property checks, JDK version detection, and nested logical expressions (and/or/not).

**Package:** `com.diamondq.maven.activator`
**Java Version:** 8
**Maven Minimum:** 3.5.2

---

## Build Commands

```bash
# Clean build and install
mvn clean install

# Run all tests
mvn test

# Run integration tests (if any)
mvn verify

# Check code style (checkstyle plugin configured)
mvn checkstyle:check

# Verify Maven version requirement
mvn enforcer:enforce
```

### Running a Single Test

```bash
# Using surefire plugin for unit tests
mvn test -Dtest=TestClassName

# Example: Run tests in a specific package
mvn test -Dtest=com.diamondq.maven.activator.*

# Filter by pattern
mvn test -Dtest=*ProfileSelector*
```

---

## Code Style Guidelines

### Package Structure
- All code under `com.diamondq.maven.activator` package
- No public API exposed; designed as internal Maven extension

### Naming Conventions

**Classes & Interfaces:**
- PascalCase (e.g., `CommonProfileSelector`, `ActivatorLogger`)
- Abstract classes: descriptive name + "Abstract" if generic
- Static inner/anonymous classes avoid explicit naming when used inline

**Methods:**
- camelCase for methods
- Parameter names prefixed with `p` to indicate input parameters (e.g., `pMsg`, `pContext`, `pLogger`)
- Boolean return values use descriptive names, not just `flag` or `result`

**Fields:**
- camelCase
- Final fields are private and clearly documented
- Avoid public mutable state

### Formatting Rules

**Indentation & Spacing:**
- 2-space indentation (Eclipse standard)
- No trailing whitespace
- Blank line between:
  - Package declaration and imports
  - Import groups and class declaration
  - Fields and methods
  - Method bodies of different logical sections
- Zero blank lines after first class body declaration

**Line Length:**
- Maximum 120 characters per line
- Wrap before operators for long expressions
- Use `Collectors.joining(", ")` for multi-line string building

**Braces & Operators:**
- All braces at end of line (K&R style)
- Space around binary operators (`=`, `+`, `-`, etc.)
- No space before opening parenthesis in method calls
- Do not insert space before semicolons

### Import Organization

```java
// 1. External library imports first, grouped by project
import org.apache.maven.model.Activation;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.codehaus.plexus.interpolation.AbstractValueSource;

// 2. Java standard library imports
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.stream.Collectors;
```

- Blank line between import groups
- Use wildcard for `java.util.*` when multiple classes from same package needed
- No blank lines within import groups
- Imports sorted alphabetically within groups

### Javadoc Comments

Required for:
- Public and protected members (not applicable here as code is internal)
- Classes with specific responsibilities
- Complex methods with non-obvious logic

```java
/**
 * This holds a set of dependencies that need to be checked whenever the profile activation context changes. If the
 * dependencies 'fail', then the cachedActiveProfiles are no longer valid
 */
private final List<Dependency> dependencies;
```

### Error Handling

- Catch `RuntimeException` and rethrow with logging when appropriate
- Use `ModelProblemCollectorRequest` to report Maven model problems
- Log debug messages for troubleshooting, not all operations
- Avoid null checks where API guarantees non-null (e.g., Maven core)

---

## Design Patterns Observed

### Dependency Injection
Uses Plexus/Sisu injection pattern:

```java
@Named("extra")
@Priority(999)
@Singleton
public class DiamondQProfileSelector extends DefaultProfileSelector {
    @Inject
    public DiamondQProfileSelector(Logger pLogger, PathTranslator pPathTranslator,
                                   List<ProfileActivator> pActivatorList) {
        // Constructor logic
    }
}
```

### Strategy Pattern
`Dependency` is abstract with factory methods creating anonymous implementations:

```java
public static Dependency onFile(File pFile) {
    return new Dependency() {
        @Override
        public boolean isValid(ProfileActivationContext pContext, ActivatorLogger logger) {
            // Implementation
        }
    };
}
```

### Interface for Logging
Minimal interface to avoid framework coupling:

```java
public interface ActivatorLogger {
    void debug(String pMsg);
    void error(String pMsg, Throwable pThrowable);
    void info(String pMsg);
    void warn(String pMsg);
    boolean isDebugEnabled();
}
```

---

## Testing Guidelines

### Test Structure (when adding tests)
- Use JUnit 4 or Maven Surefire/Failsafe
- Mock `ActivatorLogger` implementations for isolation
- Test edge cases in script parsing logic
- Verify caching behavior with repeated calls

### Common Test Scenarios
1. File-based activation: file exists/missing checks
2. Property-based activation: presence, exact value matching
3. Logical operators: and/or/not combinations
4. JDK version detection with comparison operators
5. Nested `or` expressions within `and` conditions

---

## Key Dependencies

**Maven Core (provided scope):**
- `maven-core:3.9.10` - Core Maven APIs
- `plexus-component-annotations:2.2.0` - Component metadata

---

## System Properties for Debugging

```bash
# Enable detailed profile selector tracing
mvn clean install -DDiamondQProfileSelectorDebug=true -X

# Skip Nexus deployment
-Dskip.nexus=true

# Force local installation only
-Dmaven.test.skip=false
```

---

## Known Behaviors

1. **Context Caching:** Profile activation results are cached; context changes trigger full re-evaluation
2. **Weak References:** `lastProfileActiveContext` uses weak reference to allow garbage collection
3. **Skip Property:** Profiles with `skip.*=true` property automatically bypass custom activators
4. **Path Alignment:** All file paths aligned to project base directory via `PathTranslator`
5. **Property/Env Dependency Gap:** `property()` and `env()` conditions call `Dependency.isValid()` directly but never call `dependencies.add(...)` (unlike `file`, `missing`, `type`, `jdk`, which all register themselves). Practical effect: once a profile's result is cached, a change to a property or environment variable value alone will **not** invalidate the cache on a later context change unless the same script also contains a `file`/`type`/`jdk` condition. This appears to be an existing gap rather than an intentional design choice — don't assume it's safe to copy without checking whether it matters for your use case.

---

## Adding a New Activation Keyword

Touching the script language means editing three places in lockstep:

1. **`CommonProfileSelector.recursiveProcess()`** — add an `else if ("keyword".equalsIgnoreCase(keyword))` branch. Parse `args` (watch for the `=value` and `!` prefix conventions used by `property`/`env` if applicable), then delegate to a `Dependency` factory method.
2. **`Dependency.java`** — add a static factory (e.g. `onXxx(...)`) returning an anonymous `Dependency` with matching debug-log phrasing (`"[DIAMONDQ Profile Activator]   <condition> ... so <true/false>"`) so `-DDiamondQProfileSelectorDebug=true` output stays consistent.
3. **`docs.md`** — add a row to the Conditions table under "Available Keywords", and an example under "Examples" if the keyword has non-obvious semantics.

Also decide explicitly whether the new condition should register itself via `dependencies.add(...)` for cache invalidation (see the Property/Env Dependency Gap above) — don't silently copy `property()`'s behavior without considering whether your keyword's value can change within a single build/daemon lifetime.
