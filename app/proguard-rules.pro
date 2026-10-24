# Release builds are minified with R8. The Pleos SDKs bind to vehicle services by reflection.
-keep class ai.pleos.playground.** { *; }
