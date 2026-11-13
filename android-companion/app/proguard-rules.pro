# Huawei Wear Engine Proguard Rules
-keep class com.huawei.hms.wear.** { *; }
-keep interface com.huawei.hms.wear.** { *; }

# AndroidX Security Crypto Proguard Rules
-keep class androidx.security.crypto.** { *; }

# Keep application package classes
-keep class com.bydwatch.companion.** { *; }
