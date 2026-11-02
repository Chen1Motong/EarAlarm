# 保持接收器与服务类名，避免被混淆后系统无法实例化
-keep class com.earalarm.AlarmReceiver { *; }
-keep class com.earalarm.BootReceiver { *; }
-keep class com.earalarm.RingService { *; }
