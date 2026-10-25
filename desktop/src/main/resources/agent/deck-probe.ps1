# Deck Probe: sends this PC's inventory (name, model, serial number, OS, CPU, RAM, disks,
# network cards) to Deck Link, which adds or updates its sheet in the field kit inventory.
# Windows PowerShell 5.1 or later. Config: deck-probe.json next to this script
# (written by Deck Link: servers, port, fleet key). Kept ASCII-only on purpose.
# -Print writes the report to the output instead of sending it (Deck Link uses it to add its own PC).
param(
    [string]$Config = (Join-Path $PSScriptRoot 'deck-probe.json'),
    [switch]$Print
)
$ErrorActionPreference = 'Stop'

function Try-Get([scriptblock]$block) { try { & $block } catch { $null } }
function Text($value) { if ($null -eq $value) { '' } else { ([string]$value).Trim() } }

$cs = Try-Get { Get-CimInstance -ClassName Win32_ComputerSystem }
$bios = Try-Get { Get-CimInstance -ClassName Win32_BIOS }
$os = Try-Get { Get-CimInstance -ClassName Win32_OperatingSystem }
$cpu = Try-Get { Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1 }
$release = Try-Get { (Get-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').DisplayVersion }

$disks = @()
foreach ($d in @(Try-Get { Get-CimInstance -ClassName Win32_LogicalDisk -Filter 'DriveType=3' })) {
    if ($d) { $disks += [ordered]@{ name = (Text $d.DeviceID); sizeGB = [int64][math]::Round($d.Size / 1GB); freeGB = [int64][math]::Floor($d.FreeSpace / 1GB) } }
}
$network = @()
foreach ($n in @(Try-Get { Get-CimInstance -ClassName Win32_NetworkAdapterConfiguration -Filter 'IPEnabled=True' })) {
    if ($n) {
        $ipv4 = @($n.IPAddress | Where-Object { $_ -match '^\d+\.\d+\.\d+\.\d+$' }) -join ', '
        $network += [ordered]@{ name = (Text $n.Description); mac = (Text $n.MACAddress); ipv4 = $ipv4 }
    }
}

# Installed software, from the uninstall keys (machine-wide, 32-bit view, and the user's own).
$software = @()
$keys = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
foreach ($k in @(Try-Get { Get-ItemProperty -Path $keys -ErrorAction SilentlyContinue })) {
    if (-not $k -or -not $k.DisplayName) { continue }
    if ($k.SystemComponent -eq 1 -or $k.ParentKeyName) { continue }
    $software += [ordered]@{ name = (Text $k.DisplayName); version = (Text $k.DisplayVersion); publisher = (Text $k.Publisher) }
}

# Windows Update: newest installed update, updates waiting (searched with a time limit), pending restart.
function UnixMs($date) { if ($date) { [int64]([DateTimeOffset]$date).ToUnixTimeMilliseconds() } else { [int64]0 } }
$lastHotfix = Try-Get { (Get-HotFix | Where-Object { $_.InstalledOn } | Sort-Object InstalledOn -Descending | Select-Object -First 1).InstalledOn }
$reboot = (Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Auto Update\RebootRequired') -or
    (Test-Path 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending')
$pending = -1
$job = Try-Get {
    Start-Job -ScriptBlock {
        $searcher = (New-Object -ComObject Microsoft.Update.Session).CreateUpdateSearcher()
        $searcher.Search("IsInstalled=0 and IsHidden=0 and Type='Software'").Updates.Count
    }
}
if ($job) {
    if (Wait-Job $job -Timeout 120) { $count = Try-Get { Receive-Job $job }; if ($null -ne $count) { $pending = [int]$count } }
    Remove-Job $job -Force -ErrorAction SilentlyContinue
}

$report = [ordered]@{
    probe        = 1
    hostname     = $env:COMPUTERNAME
    domain       = (Text $cs.Domain)
    user         = (Text $cs.UserName)
    manufacturer = (Text $cs.Manufacturer)
    model        = (Text $cs.Model)
    serial       = (Text $bios.SerialNumber)
    os           = ((Text $os.Caption) + ' ' + (Text $release)).Trim()
    osBuild      = (Text $os.BuildNumber)
    cpu          = (Text $cpu.Name)
    ramGB        = if ($cs) { [int64][math]::Round($cs.TotalPhysicalMemory / 1GB) } else { 0 }
    disks        = $disks
    network      = $network
    software     = $software
    updates      = [ordered]@{ lastInstalled = (UnixMs $lastHotfix); pending = $pending; rebootRequired = [bool]$reboot }
}

$json = $report | ConvertTo-Json -Depth 5 -Compress
if ($Print) {
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    Write-Output $json
    exit 0
}

$cfg = Get-Content -Raw -Encoding UTF8 -Path $Config | ConvertFrom-Json
$body = [Text.Encoding]::UTF8.GetBytes($json)
$time = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

# HMAC-SHA256 of "<time>\n<body>" with the fleet key (dev.netrunner.sync.AgentAuth).
$key = New-Object byte[] 32
for ($i = 0; $i -lt 32; $i++) { $key[$i] = [Convert]::ToByte($cfg.key.Substring($i * 2, 2), 16) }
$prefix = [Text.Encoding]::UTF8.GetBytes("$time`n")
$signed = New-Object byte[] ($prefix.Length + $body.Length)
[Array]::Copy($prefix, 0, $signed, 0, $prefix.Length)
[Array]::Copy($body, 0, $signed, $prefix.Length, $body.Length)
$hmac = New-Object System.Security.Cryptography.HMACSHA256 (, $key)
$signature = -join ($hmac.ComputeHash($signed) | ForEach-Object { $_.ToString('x2') })

$headers = @{ 'X-DeckProbe-Time' = "$time"; 'X-DeckProbe-Signature' = $signature }
$last = $null
foreach ($server in @($cfg.servers)) {
    try {
        Invoke-WebRequest -UseBasicParsing -Method Post -Uri ("http://{0}:{1}/deck-probe/report" -f $server, $cfg.port) `
            -Body $body -ContentType 'application/json; charset=utf-8' -Headers $headers -TimeoutSec 15 | Out-Null
        Write-Output "Deck Probe: inventaire envoye a $server"
        exit 0
    } catch {
        $last = $_.Exception.Message
    }
}
Write-Output "Deck Probe: Deck Link injoignable ($last)"
exit 1
