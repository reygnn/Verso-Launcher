# CLAUDE-CLOUD.md

For a cloud session: a fresh container whose network goes through an allowlist the user keeps
(claude.ai, Settings > Capabilities). Nothing set up here outlives the session, so every session
starts with these steps. Then `CLAUDE.md` holds as written, "Done means" included.

`./gradlew` needs `services.gradle.org`, `downloads.gradle.org`, `plugins.gradle.org`,
`plugins-artifacts.gradle.org`, `maven.google.com`, `dl.google.com` and
`maven-central.storage-download.googleapis.com`. If one is refused, `check` is not reported green:
the report names the host and says that `check` still has to pass on the user's machine or in CI.

---

## The SDK

1. The command-line tools from `dl.google.com/android/repository/`. `repository2-3.xml` there
   lists every `commandlinetools-linux-<n>_latest.zip`; the one with the highest `<n>` is current.
   The zip holds one folder, `cmdline-tools/`: it becomes `<sdk>/cmdline-tools/latest`.
2. `sdk.dir=<sdk>` in `local.properties`, which git ignores.
3. `./gradlew check` names the packages it misses; `<sdk>/cmdline-tools/latest/bin/android
   --sdk=<sdk> sdk install` with exactly those installs them and writes the licence file.

---

## Maven Central

Maven Central (`repo.maven.apache.org`, `repo1.maven.org`) rate-limits the container's IP with
HTTP 429, whatever the allowlist says. This init script, outside the repo, sends every request for
it, and Robolectric's download of its android-all jars, to Google's mirror:

```groovy
// ~/.gradle/init.d/cloud-session.gradle
def mirror = 'https://maven-central.storage-download.googleapis.com/maven2/'
def central = ['repo.maven.apache.org', 'repo1.maven.org']
def redirect = { RepositoryHandler repos ->
    repos.withType(MavenArtifactRepository).configureEach { r ->
        if (r.url.host in central) r.url = uri(mirror)
    }
}
beforeSettings { settings ->
    redirect(settings.pluginManagement.repositories)
    redirect(settings.dependencyResolutionManagement.repositories)
}
allprojects {
    redirect(buildscript.repositories)
    redirect(repositories)
    tasks.withType(Test).configureEach {
        systemProperty 'robolectric.dependency.repo.url', mirror
    }
}
```

---

## Handing back

When the repo cannot be pushed:

- **A branch even so** (hard rule 17).
- **A patch in `git format-patch` form**, with a message the commit can keep: a plain diff cannot
  go through `git am`. It names the base it applies to, and the patch before it if it needs one.
- **More than one patch:** the order is in the file names (`1-…`, `2-…`), and every patch is
  applied in that order to a clean checkout of the base before it is handed over.
