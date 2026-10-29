# Verso Launcher uses no reflection, no serialization, no Parcelable-by-name —
# so there is nothing to keep beyond what AGP's default manifest keep rules
# already retain (MainActivity is referenced from the manifest). R8 full mode
# is free to strip everything else.

# No renaming and line numbers kept, so a crash trace in logcat is readable as it stands;
# R8 still inlines, so an exact retrace needs the release mapping. Shrinking and optimisation
# stay on.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
