# Verso Launcher uses no reflection, no serialization, no Parcelable-by-name —
# so there is nothing to keep beyond what AGP's default manifest keep rules
# already retain (MainActivity is referenced from the manifest). R8 full mode
# is free to strip everything else.

# Guru (guru/README.md, "Release builds"): no renaming and line numbers kept, so a crash
# report's trace is readable and its Guru number — the backend's grouping key — stays the
# same from release to release. Shrinking and optimisation stay on.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
