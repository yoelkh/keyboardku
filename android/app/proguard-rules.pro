# No reflection in the app. Keep the HID callback/service entry points intact for the framework.
-keep class id.keyboardku.service.ConnectionService { *; }
-keep class * extends android.bluetooth.BluetoothHidDevice$Callback { *; }
-dontwarn javax.crypto.**
