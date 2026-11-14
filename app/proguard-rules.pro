# Huawei Wear Engine Proguard Rules
-keep class com.huawei.wearengine.** { *; }
-keep interface com.huawei.wearengine.** { *; }
-keep class com.huawei.hmf.tasks.** { *; }
-keep interface com.huawei.hmf.tasks.** { *; }

# AndroidX Security Crypto Proguard Rules
-keep class androidx.security.crypto.** { *; }

# Keep Android manifest components & App entry points while obfuscating internals
-keep class com.bydwatch.companion.BydWatchApp
-keep class com.bydwatch.companion.ui.MainActivity
-keep class com.bydwatch.companion.ui.NavigationPromptActivity
-keep class com.bydwatch.companion.ui.VehicleWidgetProvider
-keep class com.bydwatch.companion.ui.LocationWidgetProvider
-keep class com.bydwatch.companion.ui.WidgetActionReceiver
-keep class com.bydwatch.companion.ui.WidgetSyncJobService
-keep class com.bydwatch.companion.ui.QuickControlTileService
-keep class com.bydwatch.companion.wear.WearBridgeService
