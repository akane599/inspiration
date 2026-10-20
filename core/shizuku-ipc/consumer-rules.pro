# Framework interfaces are supplied by the device boot classpath; the local hidden-api stubs are compileOnly.
-dontwarn com.android.internal.app.**
-dontwarn android.os.BatteryConsumer
-dontwarn android.os.BatteryConsumer$*
-dontwarn android.os.BatteryUsageStats
-dontwarn android.os.BatteryUsageStats$*
-dontwarn android.os.BatteryUsageStatsQuery
-dontwarn android.os.BatteryUsageStatsQuery$*
-dontwarn android.os.ServiceManager
-dontwarn android.os.UidBatteryConsumer
-dontwarn android.os.UidBatteryConsumer$*

# Shizuku instantiates this binder from the app APK by class name.
-keep class com.akane.inspiration.ipc.service.TelemetryUserService { <init>(...); }
# Stub and Proxy stay whole on purpose (reviewed in audit §3). The release binder then implements, and can
# call, every transaction ITelemetryUserService.aidl declares, whatever R8 finds reachable from app code. The
# cost is a few Proxy methods that release code never calls.
-keep class com.akane.inspiration.ipc.ITelemetryUserService$Stub { *; }
-keep class com.akane.inspiration.ipc.ITelemetryUserService$Stub$Proxy { *; }
-dontwarn android.os.AggregateBatteryConsumer
-dontwarn android.os.BatteryStats
-dontwarn android.os.BatteryStats$*
-dontwarn com.android.internal.os.BatteryStatsHistoryIterator
