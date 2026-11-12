# Development workflow

## Local validation

Install a Java 21 JDK and run from the repository root:

```bash
cd backend
./gradlew clean build
```

For focused work:

```bash
./gradlew :financial-engine:test
./gradlew spotlessApply
./gradlew check
```

Formatting edits should be reviewed with the code changes. The first build needs access to the pinned Gradle distribution and dependency repositories; unit tests themselves use no network or external data.

`build` runs compilation, JUnit tests, real PostgreSQL Testcontainers checks, and Spotless checks.
It requires Docker reachable by Java; missing Docker must fail integration validation.
For unit-only work use `./gradlew :financial-engine:test :job-store:test :api-service:test`.
Engine tests use JUnit 5; service tests use JUnit 6 as required by Spring Framework 7.
Java compilation enables `-Xlint:all -Werror`, so compiler warnings fail validation.
There is no separate static analyzer. GitHub Actions runs the same build in the `Java CI`
workflow, with the `Java 21 validation` job. See [local API development](local-api.md).

## Git discipline

Inspect the branch, working tree, and diff before editing. Preserve existing changes. Use short-lived feature branches from `main`, Conventional Commit messages, and pull requests. Commits require explicit authorization; pushes and merges require separate authorization. Never rewrite published history without agreement.

When continuing sequential milestones while earlier local feature branches remain unmerged,
start the next feature from the preceding verified commit and record that dependency.
Keep `main` unchanged; do not merge merely to simplify the local branch graph.
Review and merge dependent features in order once separately authorized.

Suggested independently reviewable commit boundaries for this delivery:

1. `chore: establish Java 21 Gradle project and repository conventions`
2. `feat(engine): add validated price models and basic indicators`
3. `docs: record architecture and incremental delivery plan`

Include tests with the behavior they verify. Validate each selected commit's contents before committing; the dependency-free library must compile after the build foundation is present.

## Pull request checklist

- Explain the behavior changed and why.
- Record commands actually executed and their results, including failures or environmental limits.
- Review numerical conventions, invalid inputs, and deterministic expected values.
- Run compilation, unit tests, formatting checks, and configured static checks.
- Review the final diff for unrelated changes, secrets, generated files, and stale documentation.
- Describe meaningful risks and deferred functionality without claiming future milestones are implemented.

## Recommended GitHub protection

Branch protection is **manual and unverified**. Configure a ruleset for `main` that requires pull requests, passing `Java 21 validation`, `Infrastructure validation`, `Terraform mock validation`, and `Dashboard validation` checks from `Java CI`, and resolved review conversations; block force pushes and branch deletion. Prevent direct pushes through the ruleset. Optionally require one reviewer and restrict bypass permissions. Confirm the exact status-check names after the workflow has run successfully before making them required.

The local agent does not enable these settings or claim that they are enabled. Prefer squash merges for short-lived feature branches. Verify that fork pull requests receive no deployment credentials; the initial CI has no AWS deployment step.

No project license has been selected. Do not add license terms on the owner's behalf.
