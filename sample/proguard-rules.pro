# Guru's README, "Release builds": keep class names and line numbers, so a release trace is
# readable and the Guru number stays the same from build to build. Shrinking and optimisation
# stay on.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
