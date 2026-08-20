$ErrorActionPreference = "Stop"
$base = "c:\Users\HuiDu_OwO\Downloads\GitHub\MusicBox"
$oldTestRoot = "$base\src\test\java\ru\spliterash\musicbox"
$newTestRoot = "$base\src\test\java\com\huidu\musicbox"

# Create dirs
$testDirs = @(
    "\core\player",
    "\common\db\utils",
    "\module\edit",
    "\common\utils\cache",
    "\common\utils\classes",
    "\common\utils"
)
foreach ($d in $testDirs) {
    New-Item -ItemType Directory -Force -Path "$newTestRoot$d" | Out-Null
}
Write-Host "Test dirs created"

# Copy files
$testMap = @{
    "customPlayers\VolumeManagerRaceTest.java" = "core\player\VolumeManagerRaceTest.java"
    "db\utils\ResultSetRowTest.java" = "common\db\utils\ResultSetRowTest.java"
    "edit\NotePitchMapperTest.java" = "module\edit\NotePitchMapperTest.java"
    "utils\cache\ExpiringCacheTest.java" = "common\utils\cache\ExpiringCacheTest.java"
    "utils\classes\PairTest.java" = "common\utils\classes\PairTest.java"
    "utils\classes\PeekListTest.java" = "common\utils\classes\PeekListTest.java"
    "utils\ArrayUtilsTest.java" = "common\utils\ArrayUtilsTest.java"
    "utils\LocationKeyTest.java" = "common\utils\LocationKeyTest.java"
    "utils\MiniMessageUtilsConvertTest.java" = "common\utils\MiniMessageUtilsConvertTest.java"
    "utils\SingletonHolderTest.java" = "common\utils\SingletonHolderTest.java"
}

$count = 0
foreach ($kv in $testMap.GetEnumerator()) {
    $src = "$oldTestRoot\$($kv.Key)"
    $dst = "$newTestRoot\$($kv.Value)"
    if (Test-Path $src) {
        Copy-Item -Path $src -Destination $dst -Force
        $count++
    } else {
        Write-Warning "Missing: $src"
    }
}
Write-Host "Test files copied: $count"

# Replace package references
$replacements = @(
    @("ru.spliterash.musicbox.bstats.bukkit", "com.huidu.musicboxplus.common.stats.bukkit"),
    @("ru.spliterash.musicbox.bstats.charts", "com.huidu.musicboxplus.common.stats.charts"),
    @("ru.spliterash.musicbox.bstats.config", "com.huidu.musicboxplus.common.stats.config"),
    @("ru.spliterash.musicbox.bstats.json", "com.huidu.musicboxplus.common.stats.json"),
    @("ru.spliterash.musicbox.bstats", "com.huidu.musicboxplus.common.stats"),
    @("ru.spliterash.musicbox.commands.subcommands", "com.huidu.musicboxplus.module.command.subcommands"),
    @("ru.spliterash.musicbox.commands", "com.huidu.musicboxplus.module.command"),
    @("ru.spliterash.musicbox.config", "com.huidu.musicboxplus.common.config"),
    @("ru.spliterash.musicbox.customPlayers.objects.jukebox", "com.huidu.musicboxplus.module.jukebox"),
    @("ru.spliterash.musicbox.customPlayers.objects.sign", "com.huidu.musicboxplus.module.sign"),
    @("ru.spliterash.musicbox.customPlayers.objects.speaker", "com.huidu.musicboxplus.module.speaker"),
    @("ru.spliterash.musicbox.customPlayers.objects.radio", "com.huidu.musicboxplus.module.radio"),
    @("ru.spliterash.musicbox.customPlayers.objects.textdisplay", "com.huidu.musicboxplus.module.textdisplay"),
    @("ru.spliterash.musicbox.customPlayers.objects", "com.huidu.musicboxplus.module"),
    @("ru.spliterash.musicbox.customPlayers.abstracts", "com.huidu.musicboxplus.core.player"),
    @("ru.spliterash.musicbox.customPlayers.interfaces", "com.huidu.musicboxplus.api.player"),
    @("ru.spliterash.musicbox.customPlayers.loop", "com.huidu.musicboxplus.core.player.loop"),
    @("ru.spliterash.musicbox.customPlayers.models", "com.huidu.musicboxplus.core.player.models"),
    @("ru.spliterash.musicbox.customPlayers.playlist", "com.huidu.musicboxplus.core.player.playlist"),
    @("ru.spliterash.musicbox.customPlayers", "com.huidu.musicboxplus.core.player"),
    @("ru.spliterash.musicbox.db.model", "com.huidu.musicboxplus.common.db.model"),
    @("ru.spliterash.musicbox.db.types", "com.huidu.musicboxplus.common.db.types"),
    @("ru.spliterash.musicbox.db.utils", "com.huidu.musicboxplus.common.db.utils"),
    @("ru.spliterash.musicbox.db", "com.huidu.musicboxplus.common.db"),
    @("ru.spliterash.musicbox.edit.audio", "com.huidu.musicboxplus.module.edit.audio"),
    @("ru.spliterash.musicbox.edit.gui", "com.huidu.musicboxplus.module.edit.gui"),
    @("ru.spliterash.musicbox.edit.io", "com.huidu.musicboxplus.module.edit.io"),
    @("ru.spliterash.musicbox.edit.publish", "com.huidu.musicboxplus.module.edit.publish"),
    @("ru.spliterash.musicbox.edit", "com.huidu.musicboxplus.module.edit"),
    @("ru.spliterash.musicbox.events", "com.huidu.musicboxplus.api.event"),
    @("ru.spliterash.musicbox.gui.layout", "com.huidu.musicboxplus.module.gui.layout"),
    @("ru.spliterash.musicbox.gui.playlist", "com.huidu.musicboxplus.module.gui.playlist"),
    @("ru.spliterash.musicbox.gui.song", "com.huidu.musicboxplus.module.gui.song"),
    @("ru.spliterash.musicbox.gui.textplayer", "com.huidu.musicboxplus.module.gui.textplayer"),
    @("ru.spliterash.musicbox.gui", "com.huidu.musicboxplus.module.gui"),
    @("ru.spliterash.musicbox.hooks", "com.huidu.musicboxplus.module.hook"),
    @("ru.spliterash.musicbox.lifecycle", "com.huidu.musicboxplus.core.lifecycle"),
    @("ru.spliterash.musicbox.minecraft.gui.actions", "com.huidu.musicboxplus.module.gui.minecraft.actions"),
    @("ru.spliterash.musicbox.minecraft.gui", "com.huidu.musicboxplus.module.gui.minecraft"),
    @("ru.spliterash.musicbox.minecraft.jukebox", "com.huidu.musicboxplus.module.jukebox.minecraft"),
    @("ru.spliterash.musicbox.players", "com.huidu.musicboxplus.core.playback"),
    @("ru.spliterash.musicbox.shadow.nbteditor", "com.huidu.musicboxplus.shadow.nbteditor"),
    @("ru.spliterash.musicbox.song.songContainers.containers", "com.huidu.musicboxplus.core.song.songContainers.containers"),
    @("ru.spliterash.musicbox.song.songContainers.factory", "com.huidu.musicboxplus.core.song.songContainers.factory"),
    @("ru.spliterash.musicbox.song.songContainers.types", "com.huidu.musicboxplus.core.song.songContainers.types"),
    @("ru.spliterash.musicbox.song.songContainers", "com.huidu.musicboxplus.core.song.songContainers"),
    @("ru.spliterash.musicbox.song", "com.huidu.musicboxplus.core.song"),
    @("ru.spliterash.musicbox.utils.cache", "com.huidu.musicboxplus.common.utils.cache"),
    @("ru.spliterash.musicbox.utils.classes", "com.huidu.musicboxplus.common.utils.classes"),
    @("ru.spliterash.musicbox.utils.nbt", "com.huidu.musicboxplus.common.utils.nbt"),
    @("ru.spliterash.musicbox.utils.scheduler", "com.huidu.musicboxplus.common.utils.scheduler"),
    @("ru.spliterash.musicbox.utils", "com.huidu.musicboxplus.common.utils"),
    @("ru.spliterash.musicbox.web", "com.huidu.musicboxplus.module.web"),
    @("ru.spliterash.musicbox", "com.huidu.musicboxplus")
)

$testFiles = Get-ChildItem -Path $newTestRoot -Recurse -Filter *.java
$totalChanged = 0
foreach ($file in $testFiles) {
    $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8
    $changed = $false
    foreach ($rep in $replacements) {
        $oldStr = $rep[0]
        $newStr = $rep[1]
        if ($content.Contains($oldStr)) {
            $content = $content.Replace($oldStr, $newStr)
            $changed = $true
        }
    }
    if ($changed) {
        [System.IO.File]::WriteAllText($file.FullName, $content, (New-Object System.Text.UTF8Encoding $false))
        $totalChanged++
    }
}
Write-Host "Test files updated: $totalChanged"

# Delete old test dir
Remove-Item -Path "$base\src\test\java\ru" -Recurse -Force
Write-Host "Old test src deleted"

