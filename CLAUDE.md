# CLAUDE.md

Project conventions for **Guru** — the Amiga-style crash reporter of the
`com.github.reygnn.*` family, vendored into the hosts (Verso first) as source.

Claude Code reads this file automatically at session start. Keep it short and
actionable; the user-facing description is `README.md`. For the working agreement, see
`WHY_CLAUDE.md`. For test conventions, see `library/src/test/CLAUDE.md` and
`library/src/test/java/com/github/reygnn/guru/TESTING_CONVENTIONS.kt`.

Cross-project conventions (stack baseline, git workflow, code language) live in
`~/.claude/CLAUDE.md`. This file only holds what is specific to Guru — and where the two
differ, this one wins.

---

## The one decision: source, never a library

Hosts pull this repo with `git subtree --squash` into `guru/`, pinned to a tag, and
compile `guru/library/src/main/kotlin` as part of their own app module. That is why
everything is `internal` and nothing outside `.kt` files reaches a host. No AAR, no
Maven, no JitPack, no git submodule.

- `library/` — the only code a host gets: one package, `com.github.reygnn.guru`.
- `sample/` — a tiny host for the device test. Arrives in every host through the
  subtree but is never built there. It compiles Guru exactly as a host does:
  `sourceSets { named("main") { kotlin.directories += "../library/src/main/kotlin" } }`
  (the AGP 9 spelling), not as a dependency on `:library`.

The host interface is `Guru.install`, `Guru.showPending`, `GuruSink` and `GuruTexts`.

## Hard rules

1. **No dependency, no permission.** `implementation` stays empty. `GuruSink.Share`
   needs no permission; for `GuruSink.Acra` the host declares INTERNET itself.
2. **No thread of its own.** Disk and network work run on the host's `io`, in submission
   order, each task guarded. The only exception is the dying thread writing its own
   report.
3. **Guru never decides how the process dies.** One capture per process, no binder call
   while dying, then the exception goes unchanged to the previous handler. A failed write
   is swallowed.
4. **Files are atomic** (`.new` + fsync, rename, fsync of the directory); at most 8
   reports.
5. **The file format stays readable.** A host update with a newer Guru reads the older
   one's reports: unknown keys are skipped, no field changes meaning. A new required
   field is a break (major version).
6. **Write first, then move `exits-seen`.** A death in between repeats an exit, it never
   loses one.
7. **Delete only what is gone:** on "continue", on an app picked in the share chooser
   (`ChooserResult` `SELECTED_COMPONENT` — Copy, Edit, unknown or no result keep the
   reports), on a 2xx from the ACRA server.
8. **No manifest, no resources, everything `internal`.** Texts come from the host via
   `GuruTexts`; only the Amiga lines stay fixed English.
9. **No code for a particular host.** What only Verso needs belongs in Verso (like
   `VersoGuruTexts`).
10. **The ACRA fields are a contract** with the family's own ACRA backend
    (`~/PythonProjects/acra`): `PACKAGE_NAME` and `STACK_TRACE` are required; it groups
    by the exception class plus the `at` frames in the trace's first three lines, and for
    ANRs and native crashes (no frames there) by `PACKAGE_NAME` + `CUSTOM_DATA.GURU_MEDITATION`.
    So the trace's head, the number's format (`#XXXXXXXX.XXXXXXXX`) and that field's name
    are part of the contract. A change there is checked against the backend's
    `routes_api.py` / `utils.py`. The stock-ACRA fields (`REPORT_ID`, `INSTALLATION_ID`,
    `USER_CRASH_DATE` as `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`) stay, for Acrarium compatibility.

Working rules, as in Verso: comments and code in English; every `@Suppress` /
`@SuppressLint` carries its reason on the line above; every fix gets a mutation-checked
test.

## Build: Verso's versions and rules, exactly

Hosts compile Guru with their own settings, so a deviation here fails only after the next
`git subtree pull`, in the host. Mirrored from Verso 0.3.1: Gradle 9.8.1, AGP 9.4.1
(`com.android.library`, built-in Kotlin, no Kotlin plugin), `compileSdk = 37`,
`minSdk = 36`, JDK 21, `allWarningsAsErrors` for main and test, lint with
`warningsAsErrors` / `abortOnError` and no baseline, detekt 2.0.0-alpha.6 with
`config/detekt/detekt.yml` (Verso's minus its `HomeStyle.kt` exception) and no baseline.

- An upgrade of AGP, Gradle or detekt happens in both repos in the same step, Guru first.
- No baseline, ever: a finding is fixed or suppressed with a reason.
- No `consumer-rules.pro`: the host decides about `-dontobfuscate` (README, "Release
  builds").

`./gradlew check` is the gate: unit tests, lint, detekt. CI runs exactly that on every
push and pull request (`.github/workflows/check.yml`, JDK 21).

## Versions and releases

Every host sits on a tag, never on `main`. SemVer from `v1.0.0`; every tag has an entry
in `CHANGELOG.md`, newest first.

| Level | When | Examples |
| --- | --- | --- |
| Major | a host must change its code, or old reports are lost | new signature of `install` / `showPending`; a `GuruTexts` member **without** a default; a format old reports no longer parse in; a changed ACRA field |
| Minor | new behaviour, hosts compile unchanged | a `GuruTexts` member **with** a default in the interface; a new optional report field |
| Patch | a bug fix | a fix with its test |

- A new `GuruTexts` member always gets a default in the interface, or every host breaks
  on the next pull.
- `guru/` in a host is never edited by hand. A bug is fixed here, tagged, and pulled with
  `git subtree pull --prefix=guru https://github.com/reygnn/guru <tag> --squash`; the
  host's commit message names the tag.

Before every tag: the device test on the A17 (`README.md`, "Tests"). It runs on the
sample's **debug** build — the only one with the ANR button — installed over USB with
`ANDROID_SERIAL=R5GL51D5VHZ ./gradlew :sample:installDebug`, not via `install-aab-a17.sh`.
