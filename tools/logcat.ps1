. (Join-Path $PSScriptRoot "env.ps1")
adb logcat -c
adb logcat -v time KbKu:V BluetoothHidDevice:V HidDeviceService:V HidDevService:V bt_stack:E AndroidRuntime:E "*:S"
