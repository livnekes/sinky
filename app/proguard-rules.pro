# Add project specific ProGuard rules here.

# AWS SDK
-keep class com.amazonaws.** { *; }
-keepnames class com.amazonaws.** { *; }
-dontwarn com.amazonaws.**

# Keep MaterialComponents
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
