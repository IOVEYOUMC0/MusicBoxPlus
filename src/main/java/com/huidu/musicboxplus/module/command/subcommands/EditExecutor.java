package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.module.command.SubCommand;
import com.huidu.musicboxplus.module.edit.MusicEditListener;
import com.huidu.musicboxplus.module.edit.PlayerMusic;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.edit.gui.EditMenuGUI;
import com.huidu.musicboxplus.module.edit.gui.MusicSelectGUI;
import com.huidu.musicboxplus.module.edit.io.MusicFileImporter;
import com.huidu.musicboxplus.module.edit.io.MusicFileImporter.ImportResult;
import com.huidu.musicboxplus.module.edit.io.NBSExporter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public class EditExecutor implements SubCommand {

    private final PlayerMusicManager musicManager = PlayerMusicManager.getInstance();

    @Override
    public void execute(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            MessageUtils.send(sender, Lang.ONLY_PLAYERS);
            return;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            EditMenuGUI editMenu = new EditMenuGUI(player);
            editMenu.open();
            return;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create":
                handleCreateCommand(player, args);
                break;
            case "edit":
            case "select":
                handleEditCommand(player, args);
                break;
            case "delete":
                handleDeleteCommand(player, args);
                break;
            case "rename":
                handleRenameCommand(player, args);
                break;
            case "list":
                handleListCommand(player, args);
                break;
            case "import":
                handleImportCommand(player, args);
                break;
            case "export":
                handleExportCommand(player, args);
                break;
            case "web":
                handleWebCommand(player, args);
                break;
            case "help":
                sendHelpMessage(player);
                break;
            default:
                String name = String.join(" ", args);
                PlayerMusic music = musicManager.getMusicByName(player.getUniqueId(), name);
                if (music != null) {
                    MusicEditListener.enterEditMode(player, music);
                } else {
                    EditMenuGUI editMenu = new EditMenuGUI(player);
                    editMenu.open();
                }
                break;
        }
    }

    @Override
    public boolean canExecute(org.bukkit.command.CommandSender sender) {
        return sender.hasPermission(Permissions.EDIT);
    }

    @Override
    public List<String> tabComplete(org.bukkit.command.CommandSender sender, String[] args) {
        Stream<String> ownMusic = sender instanceof Player player
                ? musicManager.getMusicByPlayer(player).stream().map(PlayerMusic::getName)
                : Stream.empty();

        if (args.length == 1) {
            Stream<String> subCommands = Stream.of("create", "select", "delete", "rename", "list",
                    "import", "export", "help");
            if (MusicBox.getInstance().isWebEditorModuleEnabled() && MusicBox.getInstance().isWebEditorEnabled()) {
                subCommands = Stream.concat(subCommands, Stream.of("web"));
            }
            return StringUtils.tabCompletePrepare(args, Stream.concat(subCommands, ownMusic));
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "select":
                case "delete":
                case "rename":
                case "web":
                case "export":
                    return StringUtils.tabCompletePrepare(args, 2, ownMusic);
                case "import":
                    return StringUtils.tabCompletePrepare(args, 2, importableFiles().stream());
                default:
                    return List.of();
            }
        }

        return List.of();
    }

    private static final long IMPORT_LISTING_TTL_MS = 5000L;
    private static volatile List<String> importListing = List.of();
    private static volatile long importListingAt;

    // Cached: this runs on the server thread for every keystroke of an /musicboxplus edit import
    // argument, and the data folder is on disk.
    private static List<String> importableFiles() {
        long now = System.currentTimeMillis();
        if (now - importListingAt < IMPORT_LISTING_TTL_MS) {
            return importListing;
        }
        java.io.File[] files = MusicBox.getInstance().getDataFolder().listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".nbs") || lower.endsWith(".mid") || lower.endsWith(".midi");
        });
        List<String> names = new ArrayList<>();
        if (files != null) {
            for (java.io.File file : files) {
                names.add(file.getName());
            }
        }
        importListing = names;
        importListingAt = now;
        return names;
    }

    private void handleCreateCommand(Player player, String[] args) {
        if (args.length < 2) {
            MessageUtils.send(player, Lang.EDIT_CREATE_USAGE);
            return;
        }

        if (musicManager.canCreateMore(player)) {
            int limit = musicManager.getMusicLimit(player);
            int current = musicManager.getMusicCount(player.getUniqueId());
            MessageUtils.send(player, Lang.EDIT_CREATE_LIMIT, "{limit}", String.valueOf(limit));
            if (limit == -1) {
                MessageUtils.send(player, Lang.LIMIT_UNLIMITED, "{current}", String.valueOf(current));
            } else {
                MessageUtils.send(player, Lang.LIMIT_CURRENT, "{current}", String.valueOf(current), "{limit}", String.valueOf(limit));
                MessageUtils.send(player, Lang.LIMIT_UPGRADE_HINT);
            }
            return;
        }

        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        PlayerMusic existing = musicManager.getMusicByName(player.getUniqueId(), name);
        if (existing != null) {
            MessageUtils.send(player, Lang.EDIT_CREATE_EXISTS, "{name}", name);
            return;
        }

        String authorName = player.getName();
        UUID authorUUID = player.getUniqueId();
        int musicLimit = musicManager.getMusicLimit(player);
        musicManager.createMusicAsync(name, authorName, authorUUID, musicLimit).thenAccept(music ->
            Scheduler.entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (music != null) {
                    MessageUtils.send(player, Lang.EDIT_CREATE_SUCCESS, "{name}", music.getName());
                    MusicEditListener.enterEditMode(player, music);
                } else {
                    MessageUtils.send(player, Lang.EDIT_CREATE_FAILED_MSG);
                }
            })
        );
    }

    private void handleEditCommand(Player player, String[] args) {
        if (args.length < 2) {
            openMusicSelect(player);
            return;
        }

        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        PlayerMusic music = musicManager.getMusicByName(player.getUniqueId(), name);
        if (music == null) {
            MessageUtils.send(player, Lang.EDIT_NOT_FOUND, "{name}", name);
            return;
        }

        MusicEditListener.enterEditMode(player, music);
    }

    private void handleDeleteCommand(Player player, String[] args) {
        if (args.length < 2) {
            MessageUtils.send(player, Lang.EDIT_DELETE_USAGE);
            return;
        }

        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        PlayerMusic music = musicManager.getMusicByName(player.getUniqueId(), name);
        if (music == null) {
            MessageUtils.send(player, Lang.EDIT_DELETE_NOT_FOUND, "{name}", name);
            return;
        }

        musicManager.deleteMusicAsync(music.getUniqueId()).thenAccept(success ->
            Scheduler.entity(player, () -> {
                if (success) {
                    MessageUtils.send(player, Lang.EDIT_DELETE_SUCCESS, "{name}", name);
                } else {
                    MessageUtils.send(player, Lang.EDIT_DELETE_FAILED_MSG);
                }
            })
        );
    }

    private void handleRenameCommand(Player player, String[] args) {
        if (args.length < 3) {
            MessageUtils.send(player, Lang.EDIT_RENAME_USAGE);
            return;
        }

        String newName = args[args.length - 1];
        String oldName = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));

        PlayerMusic music = musicManager.getMusicByName(player.getUniqueId(), oldName);
        if (music == null) {
            MessageUtils.send(player, Lang.EDIT_RENAME_NOT_FOUND, "{name}", oldName);
            return;
        }

        PlayerMusic existing = musicManager.getMusicByName(player.getUniqueId(), newName);
        if (existing != null) {
            MessageUtils.send(player, Lang.EDIT_RENAME_EXISTS, "{name}", newName);
            return;
        }

        musicManager.renameMusicAsync(music.getUniqueId(), newName).thenAccept(success ->
            Scheduler.entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (success) {
                    MessageUtils.send(player, Lang.EDIT_RENAME_SUCCESS, "{old}", oldName, "{new}", newName);
                } else {
                    MessageUtils.send(player, Lang.EDIT_RENAME_FAILED_MSG);
                }
            })
        );
    }

    private void handleListCommand(Player player, String[] args) {
        List<PlayerMusic> musicList = musicManager.getMusicByPlayer(player);

        if (musicList.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_LIST_EMPTY_MSG);
            return;
        }

        MessageUtils.send(player, Lang.EDIT_LIST_HEADER);
        MessageUtils.send(player, Lang.EDIT_LIST_TITLE_MSG, "{count}", String.valueOf(musicList.size()));
        MessageUtils.send(player, Lang.EDIT_LIST_HEADER);
        
        for (int i = 0; i < musicList.size(); i++) {
            PlayerMusic music = musicList.get(i);
            MessageUtils.send(player, Lang.EDIT_LIST_ITEM_MSG,
                "{num}", String.valueOf(i + 1),
                "{name}", music.getName(),
                "{notes}", String.valueOf(music.getNoteCount())
            );
        }
        
        MessageUtils.send(player, Lang.EDIT_LIST_FOOTER);
    }

    private void handleImportCommand(Player player, String[] args) {
        if (args.length < 2) {
            MessageUtils.send(player, Lang.IMPORT_USAGE);
            return;
        }

        if (musicManager.canCreateMore(player)) {
            int limit = musicManager.getMusicLimit(player);
            int current = musicManager.getMusicCount(player.getUniqueId());
            MessageUtils.send(player, Lang.EDIT_CREATE_LIMIT, "{limit}", String.valueOf(limit));
            if (limit == -1) {
                MessageUtils.send(player, Lang.LIMIT_UNLIMITED, "{current}", String.valueOf(current));
            } else {
                MessageUtils.send(player, Lang.LIMIT_CURRENT, "{current}", String.valueOf(current), "{limit}", String.valueOf(limit));
            }
            return;
        }

        String fileName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String authorName = player.getName();
        java.util.UUID authorUUID = player.getUniqueId();
        AsyncTaskManager.runAsync(() -> {
            try {
                ImportResult result = MusicFileImporter.getInstance().importByName(fileName, authorName, authorUUID);
                Scheduler.entity(player, () -> {
                    MessageUtils.send(player, Lang.IMPORT_SUCCESS,
                            "{format}", result.format().getDisplayName(),
                            "{name}", result.music().getName());
                    MessageUtils.send(player, Lang.IMPORT_INFO,
                            "{notes}", String.valueOf(result.music().getNoteCount()),
                            "{bpm}", String.valueOf(result.music().getBpm()),
                            "{subdivision}", String.valueOf(result.music().getBeatSubdivision()));
                    sendImportWarnings(player, result);
                    MusicEditListener.enterEditMode(player, result.music());
                });
            } catch (Exception e) {
                Scheduler.entity(player, () -> handleImportFailure(player, fileName, e));
            }
        });
    }

    private void handleExportCommand(Player player, String[] args) {
        if (args.length < 2) {
            MessageUtils.send(player, Lang.EXPORT_USAGE);
            return;
        }

        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        PlayerMusic music = musicManager.getMusicByName(player.getUniqueId(), name);
        if (music == null) {
            MessageUtils.send(player, Lang.EDIT_NOT_FOUND, "{name}", name);
            return;
        }

        AsyncTaskManager.runAsync(() -> {
            try {
                NBSExporter.ExportResult result = NBSExporter.getInstance().export(music);
                Scheduler.entity(player, () -> {
                    MessageUtils.send(player, Lang.EXPORT_SUCCESS,
                            "{name}", music.getName(),
                            "{file}", result.file().getName(),
                            "{path}", result.file().getPath());
                    sendExportWarnings(player, result);
                });
            } catch (Exception e) {
                Scheduler.entity(player,
                        () -> MessageUtils.send(player, Lang.EXPORT_FAILED, "{error}", e.getMessage() == null ? Lang.UNKNOWN.toString() : e.getMessage()));
            }
        });
    }

    private void handleImportFailure(Player player, String fileName, Exception e) {
        String message = e.getMessage() == null ? "Unknown error" : e.getMessage();
        if ("Unsupported import format".equals(message)) {
            MessageUtils.send(player, Lang.IMPORT_UNSUPPORTED_FORMAT);
        } else if ("Import file not found".equals(message)) {
            MessageUtils.send(player, Lang.IMPORT_FILE_NOT_FOUND, "{filename}", fileName);
            MessageUtils.send(player, Lang.IMPORT_FILE_LOCATION_HINT, "{path}", MusicBox.getInstance().getDataFolder().getPath());
        } else {
            MessageUtils.send(player, Lang.IMPORT_FAILED, "{error}", message);
        }
    }

    private void sendImportWarnings(Player player, ImportResult result) {
        if (result.warnings() == null || result.warnings().isEmpty()) {
            return;
        }
        boolean pitchClampWarned = false;
        for (String warning : result.warnings()) {
            if ("tempo_changes_collapsed".equals(warning)) {
                MessageUtils.send(player, Lang.IMPORT_WARNING_TEMPO_CHANGES);
            } else if (!pitchClampWarned
                    && ("pitch_clamped_low".equals(warning) || "pitch_clamped_high".equals(warning))) {
                MessageUtils.send(player, Lang.IMPORT_WARNING_PITCH_CLAMPED);
                pitchClampWarned = true;
            }
        }
    }

    private void sendExportWarnings(Player player, NBSExporter.ExportResult result) {
        if (result.warnings() == null || result.warnings().isEmpty()) {
            return;
        }
        for (String warning : result.warnings()) {
            if ("pitch_clamped_low".equals(warning) || "pitch_clamped_high".equals(warning)) {
                MessageUtils.send(player, Lang.EXPORT_WARNING_PITCH_CLAMPED);
                break;
            }
        }
    }

    private void handleWebCommand(Player player, String[] args) {
        if (!MusicBox.getInstance().isWebEditorModuleEnabled() || !MusicBox.getInstance().isWebEditorEnabled()) {
            MessageUtils.send(player, Lang.EDIT_WEB_DISABLED);
            return;
        }

        com.huidu.musicboxplus.module.web.WebEditorServer webServer = MusicBox.getInstance().getWebEditorServer();

        if (args.length < 2) {
            MessageUtils.send(player, Lang.EDIT_WEB_USAGE);
            return;
        }

        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        PlayerMusic music = musicManager.getMusicByName(player.getUniqueId(), name);
        if (music == null) {
            MessageUtils.send(player, Lang.EDIT_NOT_FOUND, "{name}", name);
            return;
        }

        String sessionId = webServer.createSession(player.getUniqueId(), music.getUniqueId());
        String url = webServer.buildSessionUrl(sessionId);
        
        MessageUtils.send(player, Lang.EDIT_WEB_LINK, "{name}", music.getName(), "{url}", url);
    }

    private void openMusicSelect(Player player) {
        List<PlayerMusic> musicList = musicManager.getMusicByPlayer(player);
        
        if (musicList.isEmpty()) {
            MessageUtils.send(player, Lang.EDIT_NO_MUSIC);
            return;
        }
        
        if (musicList.size() == 1) {
            MusicEditListener.enterEditMode(player, musicList.get(0));
        } else {
            new MusicSelectGUI(player).open();
        }
    }

    private void sendHelpMessage(Player player) {
        MessageUtils.send(player, Lang.EDIT_HELP_HEADER);
        MessageUtils.send(player, Lang.EDIT_HELP_TITLE);
        MessageUtils.send(player, Lang.EDIT_HELP_HEADER);
        MessageUtils.send(player, Lang.EDIT_HELP_EDIT);
        MessageUtils.send(player, Lang.EDIT_HELP_CREATE);
        MessageUtils.send(player, Lang.EDIT_HELP_SELECT);
        MessageUtils.send(player, Lang.EDIT_HELP_DELETE);
        MessageUtils.send(player, Lang.EDIT_HELP_RENAME);
        MessageUtils.send(player, Lang.EDIT_HELP_LIST);
        MessageUtils.send(player, Lang.EDIT_HELP_IMPORT);
        MessageUtils.send(player, Lang.EXPORT_USAGE);
        MessageUtils.send(player, Lang.EDIT_HELP_WEB);
        MessageUtils.send(player, Lang.EDIT_HELP_FOOTER);
    }
}
