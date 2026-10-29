# Verso Launcher uses no reflection, no serialization, no Parcelable-by-name —
# so there is nothing to keep beyond what AGP's default manifest keep rules
# already retain (MainActivity is referenced from the manifest). R8 full mode
# is free to strip everything else.

# Line numbers and the source file kept: R8 then writes its map id as each frame's source file
# (r8-map-id-<hash>), and retrace picks the archived mapping by it (app/build.gradle.kts,
# archiveProguardMapping). Without them a crash trace in logcat cannot be read back at all.
-keepattributes SourceFile,LineNumberTable
