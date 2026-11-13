# Huawei Wear Engine Proguard Rules
-keep class com.huawei.wearengine.** { *; }
-keep interface com.huawei.wearengine.** { *; }
-keep class com.huawei.hmf.tasks.** { *; }
-keep interface com.huawei.hmf.tasks.** { *; }

# AndroidX Security Crypto Proguard Rules
-keep class androidx.security.crypto.** { *; }

# Keep application package classes
-keep class com.bydwatch.companion.** { *; }
