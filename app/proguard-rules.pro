# Release builds are minified with R8. The Pleos SDKs bind to vehicle services by reflection.
-keep class ai.pleos.playground.** { *; }

# Tink (security-crypto, under the TTS sign-in) references Error Prone's compile-time annotations;
# Material Components used to bring them along (excluded in build.gradle.kts).
-dontwarn com.google.errorprone.annotations.**
