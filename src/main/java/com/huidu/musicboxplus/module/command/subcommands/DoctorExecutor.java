package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.core.db.schema.Migrations;
import com.huidu.musicboxplus.core.song.CompiledSongCache;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /musicboxplus doctor}: read-only snapshot of the state an operator needs to answer
 * "why is it behaving like that" without reading logs or guessing.
 *
 * <p>Everything it prints is either a live measurement (database round trip, library size) or a
 * configuration fact (module flags, schema version). It never writes, never reloads and never
 * repairs: a diagnostic that changes state is not a diagnostic.
 *
 * <p>The database probe runs off the tick thread; on Folia a region thread blocked on JDBC stalls
 * every player in that region.
 */
public class DoctorExecutor implements SubCommand {

    @Override
    public void execute(CommandSender sender, String[] args) {
        MusicBox plugin = MusicBox.getInstance();
        String lang = plugin.getConfigObject().getLanguage();

        AsyncTaskManager.runAsync(() -> {
            List<String> lines = new ArrayList<>();
            // Server side facts are safe to read here; the database probe is the slow part and this
            // whole block already runs off the tick thread.
            boolean mysql;
            long ping = -1L;
            int schemaVersion = 0;
            if (DatabaseLoader.isInitialized()) {
                var base = DatabaseLoader.getBase();
                mysql = base.isMySQL();
                ping = base.pingMillis();
                schemaVersion = base.getSchemaVersion();
            } else {
                mysql = false;
            }

            // Heading is a language key; the individual readings below stay technical on purpose
            // ("Folia", "SQLite", "schema version" are names, not prose worth translating).
            lines.add(Bukkit.getName() + " " + Bukkit.getVersion());
            lines.add("Folia: " + Scheduler.isFolia()
                    + "   tick thread: " + Bukkit.isPrimaryThread());
            lines.add("Database: " + (DatabaseLoader.isInitialized()
                    ? (mysql ? "MySQL" : "SQLite") : "NOT INITIALIZED")
                    + "   ping: " + (ping < 0 ? "FAILED" : ping + " ms"));
            int latest = Migrations.latestVersion();
            lines.add("Schema version: " + schemaVersion + " (plugin expects " + latest + ")"
                    + (schemaVersion == latest ? "" : "   <-- MISMATCH"));

            lines.add("Songs: " + MusicBoxSongManager.getAllSongs().size()
                    + "   loaded: " + MusicBoxSongManager.isLoaded()
                    + "   loading: " + MusicBoxSongManager.isLoading());
            var playerMusic = com.huidu.musicboxplus.module.edit.PlayerMusicManager.getExistingInstance();
            lines.add("Player songs: " + (playerMusic == null ? "module off"
                    : playerMusic.getLoadedMusicCount()));
            lines.add("Compiled arrangements cached: " + CompiledSongCache.size());
            lines.add("Language: " + lang + "   Web: "
                    + (plugin.getConfigObject().getWeb().isEnabled()
                        ? plugin.getConfigObject().getWeb().getHost() + ":"
                            + plugin.getConfigObject().getWeb().getPort()
                        : "off"));
            lines.add(modules(plugin));

            String report = String.join("\n", lines);
            Scheduler.global(() -> {
                MessageUtils.send(sender, Lang.DOCTOR_HEADING);
                for (String line : lines) {
                    MessageUtils.send(sender, line);
                }
                plugin.getLogger().info(report);
            });
        }, () -> Scheduler.global(() -> MessageUtils.send(sender, Lang.DOCTOR_QUEUE_FULL)));
    }

    private static String modules(MusicBox plugin) {
        List<String> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        record Flag(String name, boolean enabled) {
        }
        List<Flag> flags = List.of(
                new Flag("playback", plugin.isPlaybackModuleEnabled()),
                new Flag("shop", plugin.isShopModuleEnabled()),
                new Flag("playerMusic", plugin.isPlayerMusicModuleEnabled()),
                new Flag("publish", plugin.isPublishModuleEnabled()),
                new Flag("editor", plugin.isEditorModuleEnabled()),
                new Flag("webEditor", plugin.isWebEditorModuleEnabled()),
                new Flag("signs", plugin.isSignsModuleEnabled()),
                new Flag("jukebox", plugin.isJukeboxModuleEnabled()),
                new Flag("playlists", plugin.isPlaylistsModuleEnabled()),
                new Flag("give", plugin.isGiveModuleEnabled()),
                new Flag("textPlayer", plugin.isTextPlayerModuleEnabled()),
                new Flag("songTags", plugin.isSongTagsModuleEnabled()));
        for (Flag flag : flags) {
            (flag.enabled() ? on : off).add(flag.name());
        }
        return "Modules on: " + String.join(", ", on) + "   |   off: " + String.join(", ", off);
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.ADMIN) || sender.hasPermission(Permissions.RELOAD);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
