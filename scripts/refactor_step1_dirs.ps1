$ErrorActionPreference = "Stop"
$base = "c:\Users\HuiDu_OwO\Downloads\GitHub\MusicBox"
$newRoot = "$base\src\main\java\com\huidu\musicbox"

$dirs = @(
    "\common\lang",
    "\common\stats\bukkit",
    "\common\stats\charts",
    "\common\stats\config",
    "\common\stats\json",
    "\common\config",
    "\common\db\model",
    "\common\db\types",
    "\common\db\utils",
    "\common\utils\cache",
    "\common\utils\classes",
    "\common\utils\nbt",
    "\common\utils\scheduler",
    "\module\command\subcommands",
    "\module\edit\audio",
    "\module\edit\gui",
    "\module\edit\io",
    "\module\edit\publish",
    "\module\gui\layout",
    "\module\gui\playlist",
    "\module\gui\song",
    "\module\gui\textplayer",
    "\module\gui\minecraft\actions",
    "\module\hook",
    "\module\jukebox\minecraft",
    "\module\sign",
    "\module\speaker",
    "\module\radio",
    "\module\textdisplay",
    "\module\web",
    "\core\player\loop",
    "\core\player\models",
    "\core\player\playlist",
    "\core\song\songContainers\containers",
    "\core\song\songContainers\factory",
    "\core\song\songContainers\types",
    "\core\playback",
    "\core\lifecycle",
    "\api\player",
    "\api\event",
    "\shadow\nbteditor"
)
foreach ($d in $dirs) {
    New-Item -ItemType Directory -Force -Path "$newRoot$d" | Out-Null
}
Write-Host "Directories created: $($dirs.Count)"

