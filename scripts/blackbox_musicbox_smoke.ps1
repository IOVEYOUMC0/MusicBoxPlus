param(
    [string]$ServerRoot = "C:\Users\HuiDu_OwO\Desktop\test",
    [string]$PlayerName = "HuiDu_OwO",
    [string]$RconHost = "127.0.0.1",
    [int]$RconPort = 25575,
    [string]$RconPassword = "1234",
    [string]$TestId = "",
    [switch]$SkipScreenshots
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($TestId)) {
    $TestId = "musicbox_smoke_{0}" -f [DateTimeOffset]::Now.ToUnixTimeSeconds()
}

function Send-RconPacket($stream, [int]$id, [int]$type, [string]$body) {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $packet = New-Object byte[] (14 + $bodyBytes.Length)
    [BitConverter]::GetBytes([int](4 + 4 + $bodyBytes.Length + 2)).CopyTo($packet, 0)
    [BitConverter]::GetBytes([int]$id).CopyTo($packet, 4)
    [BitConverter]::GetBytes([int]$type).CopyTo($packet, 8)
    [Array]::Copy($bodyBytes, 0, $packet, 12, $bodyBytes.Length)
    $packet[12 + $bodyBytes.Length] = 0
    $packet[13 + $bodyBytes.Length] = 0
    $stream.Write($packet, 0, $packet.Length)
    $stream.Flush()
}

function Read-RconPacket($stream) {
    $lenBytes = New-Object byte[] 4
    $read = $stream.Read($lenBytes, 0, 4)
    if ($read -le 0) {
        return $null
    }

    $len = [BitConverter]::ToInt32($lenBytes, 0)
    $payload = New-Object byte[] $len
    $offset = 0
    while ($offset -lt $len) {
        $chunk = $stream.Read($payload, $offset, $len - $offset)
        if ($chunk -le 0) {
            break
        }
        $offset += $chunk
    }

    $id = [BitConverter]::ToInt32($payload, 0)
    $type = [BitConverter]::ToInt32($payload, 4)
    $body = [System.Text.Encoding]::UTF8.GetString($payload, 8, [Math]::Max(0, $len - 10))
    [PSCustomObject]@{
        Id = $id
        Type = $type
        Body = $body
    }
}

function Invoke-Rcon([string]$Command) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.ReceiveTimeout = 5000
        $client.SendTimeout = 5000
        $client.Connect($RconHost, $RconPort)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 5000
        $stream.WriteTimeout = 5000

        Send-RconPacket $stream 1 3 $RconPassword
        [void](Read-RconPacket $stream)

        Send-RconPacket $stream 2 2 $Command
        $response = Read-RconPacket $stream
        if ($null -eq $response) {
            return ""
        }
        return $response.Body
    } finally {
        $client.Close()
    }
}

function Get-LatestLogLines([int]$Tail = 120) {
    Get-Content -Path (Join-Path $ServerRoot "logs\latest.log") -Tail $Tail
}

function Get-LogTime([string]$Line) {
    if ($Line -match '^\[(\d{2}):(\d{2}):(\d{2})\]') {
        return Get-Date -Hour ([int]$Matches[1]) -Minute ([int]$Matches[2]) -Second ([int]$Matches[3])
    }
    return [datetime]::MinValue
}

function Wait-ForLogMatch([string]$Pattern, [datetime]$StartTime, [int]$TimeoutSeconds = 10) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $match = Get-LatestLogLines |
            Select-String -Pattern $Pattern |
            Where-Object { (Get-LogTime $_.Line) -ge $StartTime.AddSeconds(-1) } |
            Select-Object -Last 1
        if ($match) {
            return $match.Line
        }
        Start-Sleep -Milliseconds 300
    }
    throw "Timed out waiting for log pattern: $Pattern"
}

function Send-BlackBox([string]$Action, [string]$Json = "{}") {
    $startedAt = Get-Date
    $cmd = "blackbox send $PlayerName $Action $Json"
    Invoke-Rcon $cmd | Out-Null
    return $startedAt
}

function Take-Screenshot([string]$Prefix) {
    if ($SkipScreenshots) {
        return $null
    }

    $json = ('{{"testId":"{0}","prefix":"{1}","playerName":"{2}"}}' -f $TestId, $Prefix, $PlayerName)
    $startedAt = Send-BlackBox "screenshot" $json
    $line = Wait-ForLogMatch ("Screenshot saved: .*{0}" -f [regex]::Escape($Prefix)) $startedAt
    return $line
}

function Query-ContainerState() {
    $startedAt = Send-BlackBox "query_container_state" "{}"
    $line = Wait-ForLogMatch "Container state queried data=" $startedAt
    $json = ($line -replace '^.*data=', '')
    return $json | ConvertFrom-Json
}

function Click-Slot([int]$WindowId, [int]$StateId, [int]$Slot, [int]$Button = 0, [int]$Mode = 0) {
    $json = ('{{"windowId":{0},"stateId":{1},"slot":{2},"button":{3},"mode":{4}}}' -f $WindowId, $StateId, $Slot, $Button, $Mode)
    $startedAt = Send-BlackBox "click_slot" $json
    Wait-ForLogMatch ("Clicked slot {0} in window {1}" -f $Slot, $WindowId) $startedAt | Out-Null
}

Write-Host "== BlackBoxPro / MusicBox smoke test =="
Write-Host "ServerRoot : $ServerRoot"
Write-Host "Player     : $PlayerName"
Write-Host "TestId     : $TestId"

$status = Invoke-Rcon "blackbox status"
Write-Host "`n[BlackBoxPro status]"
Write-Host $status

Take-Screenshot "00_before" | Out-Null

Write-Host "`n[1] Open /musicbox"
$startedAt = Send-BlackBox "chat_command" '{"command":"musicbox"}'
Wait-ForLogMatch ("Sent command: /musicbox") $startedAt
$menuState = Query-ContainerState
Write-Host ("WindowId={0}, StateId={1}, Title={2}" -f $menuState.windowId, $menuState.stateId, $menuState.title)
Take-Screenshot "01_musicbox_open" | Out-Null

Write-Host "`n[2] Click main-menu volume button"
Click-Slot -WindowId $menuState.windowId -StateId $menuState.stateId -Slot 47
$afterVolume = Query-ContainerState
Write-Host ("Volume click advanced stateId: {0} -> {1}" -f $menuState.stateId, $afterVolume.stateId)
Take-Screenshot "02_after_volume_click" | Out-Null

Write-Host "`n[3] Click the first visible song slot"
Click-Slot -WindowId $afterVolume.windowId -StateId $afterVolume.stateId -Slot 10
Take-Screenshot "03_after_play_click" | Out-Null

Write-Host "`n[4] Re-open /musicbox and click control panel entry"
$startedAt = Send-BlackBox "chat_command" '{"command":"musicbox"}'
Wait-ForLogMatch ("Sent command: /musicbox") $startedAt
$menuState = Query-ContainerState
Click-Slot -WindowId $menuState.windowId -StateId $menuState.stateId -Slot 48
$controlState = Query-ContainerState
Write-Host ("Control panel title: {0}" -f $controlState.title)
Take-Screenshot "04_control_panel_open" | Out-Null

Write-Host "`nSmoke test finished."
