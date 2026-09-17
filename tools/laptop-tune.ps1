<#
.SYNOPSIS  Run as Administrator. Applies the Windows-side latency fixes for KeyboardKu's WiFi path:
  1. Inbound firewall rule for UDP 47800 (so DISCOVER/HELLO from the phone are never dropped).
  2. Wireless adapter power policy = Maximum Performance (AC and battery), so the NIC does not buffer packets in power-save.
  3. Shows Bluetooth adapter power-management state (untick "Allow the computer to turn off this device" manually if Enabled).
#>
param([int]$Port = 47800)
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Write-Error "Run this script from an elevated (Administrator) PowerShell."; exit 1 }

# 1. Firewall
$name = "KeyboardKu UDP $Port"
if (-not (Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $name -Direction Inbound -Protocol UDP -LocalPort $Port -Action Allow -Profile Private,Domain | Out-Null
    Write-Host "Firewall rule added: $name (Private/Domain profiles)"
} else { Write-Host "Firewall rule already present: $name" }

# 2. Wireless adapter power saving -> Maximum Performance (0) for AC and DC on the active scheme
$sub = "19cbb8fa-5279-450e-9fac-8a3d5fedd0c1"   # Wireless Adapter Settings
$set = "12bbebe6-58d6-4636-95bb-3217ef867c1a"   # Power Saving Mode
$scheme = (powercfg /getactivescheme) -replace '.*GUID: ([0-9a-f-]+).*', '$1'
powercfg /setacvalueindex $scheme $sub $set 0
powercfg /setdcvalueindex $scheme $sub $set 0
powercfg /setactive $scheme
Write-Host "Wireless adapter power saving set to Maximum Performance (AC+DC) on scheme $scheme"

# 3. Bluetooth adapter power management state (informational)
Get-PnpDevice -Class Bluetooth -Status OK | ForEach-Object {
    $pm = Get-PnpDeviceProperty -InstanceId $_.InstanceId -KeyName "DEVPKEY_Device_PowerData" -ErrorAction SilentlyContinue
    Write-Host ("BT device: {0}" -f $_.FriendlyName)
}
Write-Host "If the cursor stutters after idle over Bluetooth: Device Manager > Bluetooth > adapter > Power Management > untick 'Allow the computer to turn off this device to save power'."
