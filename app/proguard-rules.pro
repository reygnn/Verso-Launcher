# Verso Launcher uses no reflection, no serialization, no Parcelable-by-name —
# so beyond what AGP's default manifest keep rules retain (MainActivity is
# referenced from the manifest) only one class is kept, below. R8 full mode is
# free to strip everything else.

# Guru (guru/README.md, "Release builds"): no renaming and line numbers kept, so a crash
# report's trace is readable and its Guru number — the backend's grouping key — stays the
# same from release to release. Shrinking and optimisation stay on.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# The watchdog's exception names a stall in Guru's report and the backend's grouping: without a
# keep, R8 merges it into a look-alike (seen: kotlin.NoWhenBranchMatchedException, A17, 0.5.8).
-keep class com.github.reygnn.verso_launcher.IoStallException
