package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.core.song.SongAliasConfig;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SongTagExecutor
implements SubCommand {
    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            this.sendUsage(sender);
            return;
        }
        String songName = args[0].replace("_", " ");
        String action = args[1].toLowerCase();
        MusicBoxSong song = MusicBoxSongManager.findByName(songName).orElse(null);
        if (song == null) {
            MessageUtils.send(sender, Lang.SONG_NOT_FOUND, "{song}", songName);
            return;
        }
        SongAliasConfig aliasConfig = SongAliasConfig.getInstance();
        switch (action) {
            case "addalias": 
            case "add-alias": {
                if (args.length < 3) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_ADDALIAS);
                    return;
                }
                aliasConfig.addSongAlias(song.getName(), args[2]);
                MessageUtils.send(sender, Lang.TAG_ADDED_ALIAS, "{alias}", args[2], "{song}", song.getName());
                break;
            }
            case "removealias": 
            case "remove-alias": {
                if (args.length < 3) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_REMOVEALIAS);
                    return;
                }
                if (song.getAliases().contains(args[2].toLowerCase())) {
                    aliasConfig.removeSongAlias(song.getName(), args[2]);
                    MessageUtils.send(sender, Lang.TAG_REMOVED_ALIAS, "{alias}", args[2], "{song}", song.getName());
                    break;
                }
                MessageUtils.send(sender, Lang.TAG_ALIAS_NOT_FOUND, "{alias}", args[2], "{song}", song.getName());
                break;
            }
            case "addtag": 
            case "add-tag": {
                if (args.length < 3) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_ADDTAG);
                    return;
                }
                aliasConfig.addSongTag(song.getName(), args[2]);
                MessageUtils.send(sender, Lang.TAG_ADDED_TAG, "{tag}", args[2], "{song}", song.getName());
                break;
            }
            case "removetag": 
            case "remove-tag": {
                if (args.length < 3) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_REMOVETAG);
                    return;
                }
                if (song.getTags().contains(args[2].toLowerCase())) {
                    aliasConfig.removeSongTag(song.getName(), args[2]);
                    MessageUtils.send(sender, Lang.TAG_REMOVED_TAG, "{tag}", args[2], "{song}", song.getName());
                    break;
                }
                MessageUtils.send(sender, Lang.TAG_TAG_NOT_FOUND, "{tag}", args[2], "{song}", song.getName());
                break;
            }
            case "list": 
            case "info": {
                MessageUtils.send(sender, Lang.TAG_LIST_HEADER, "{song}", song.getName());
                MessageUtils.send(sender, Lang.TAG_LIST_ALIASES, "{aliases}", song.getAliases().isEmpty() ? Lang.UNKNOWN.toString() : String.join(", ", song.getAliases()));
                MessageUtils.send(sender, Lang.TAG_LIST_TAGS, "{tags}", song.getTags().isEmpty() ? Lang.UNKNOWN.toString() : String.join(", ", song.getTags()));
                break;
            }
            case "setmaterial":
            case "set-material": {
                if (args.length < 3) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_SETMATERIAL);
                    return;
                }
                // Must be an obtainable item: a block-only or legacy material is stored happily
                // and then NPEs in every later item render.
                Material material = Material.matchMaterial(args[2]);
                if (material == null || !material.isItem()) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_SETMATERIAL);
                    return;
                }
                aliasConfig.setSongCustomMaterial(song.getName(), material.name());
                MessageUtils.send(sender, Lang.TAG_SET_MATERIAL, "{material}", material.name(), "{song}", song.getName());
                break;
            }
            case "setmodeldata":
            case "set-model-data": {
                if (args.length < 3) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_SETMODELDATA);
                    return;
                }
                try {
                    int modelData = Integer.parseInt(args[2]);
                    aliasConfig.setSongCustomModelData(song.getName(), modelData);
                    MessageUtils.send(sender, Lang.TAG_SET_MODELDATA, "{modelData}", String.valueOf(modelData), "{song}", song.getName());
                } catch (NumberFormatException e) {
                    MessageUtils.send(sender, Lang.TAG_INVALID_NUMBER);
                }
                break;
            }
            case "setjukebox":
            case "set-jukebox-playable": {
                if (args.length < 3) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_SETJUKEBOX);
                    return;
                }
                aliasConfig.setSongJukeboxPlayable(song.getName(), args[2]);
                MessageUtils.send(sender, Lang.TAG_SET_JUKEBOX, "{key}", args[2], "{song}", song.getName());
                break;
            }
            case "setitemmodel":
            case "set-item-model": {
                if (args.length < 3) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_SETITEMMODEL);
                    return;
                }
                aliasConfig.setSongItemModel(song.getName(), args[2]);
                MessageUtils.send(sender, Lang.TAG_SET_ITEMMODEL, "{itemModel}", args[2], "{song}", song.getName());
                break;
            }
            case "setcraftengineitem":
            case "set-craft-engine-item": {
                if (args.length < 3) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_SETCRAFTENGINEITEM);
                    return;
                }
                aliasConfig.setSongCraftEngineItem(song.getName(), args[2]);
                MessageUtils.send(sender, Lang.TAG_SET_CRAFTENGINEITEM, "{item}", args[2], "{song}", song.getName());
                break;
            }
            case "unset":
            case "clear": {
                if (args.length < 3) {
                    MessageUtils.send(sender, Lang.TAG_USAGE_UNSET);
                    return;
                }
                String field = args[2].toLowerCase().replace("-", "");
                switch (field) {
                    case "material":
                    case "custommaterial": {
                        aliasConfig.setSongCustomMaterial(song.getName(), null);
                        break;
                    }
                    case "modeldata":
                    case "custommodeldata": {
                        aliasConfig.setSongCustomModelData(song.getName(), 0);
                        break;
                    }
                    case "itemmodel": {
                        aliasConfig.setSongItemModel(song.getName(), null);
                        break;
                    }
                    case "craftengineitem": {
                        aliasConfig.setSongCraftEngineItem(song.getName(), null);
                        break;
                    }
                    case "jukebox":
                    case "jukeboxplayable": {
                        aliasConfig.setSongJukeboxPlayable(song.getName(), null);
                        break;
                    }
                    default: {
                        MessageUtils.send(sender, Lang.TAG_UNSET_UNKNOWN_FIELD, "{field}", args[2]);
                        return;
                    }
                }
                MessageUtils.send(sender, Lang.TAG_UNSET, "{field}", field, "{song}", song.getName());
                break;
            }
            default: {
                this.sendUsage(sender);
            }
        }
    }

    private void sendUsage(CommandSender sender) {
        MessageUtils.send(sender, Lang.TAG_USAGE_TITLE);
        MessageUtils.send(sender, Lang.TAG_USAGE_ADDALIAS);
        MessageUtils.send(sender, Lang.TAG_USAGE_REMOVEALIAS);
        MessageUtils.send(sender, Lang.TAG_USAGE_ADDTAG);
        MessageUtils.send(sender, Lang.TAG_USAGE_REMOVETAG);
        MessageUtils.send(sender, Lang.TAG_USAGE_LIST);
        MessageUtils.send(sender, Lang.TAG_USAGE_SETMATERIAL);
        MessageUtils.send(sender, Lang.TAG_USAGE_SETMODELDATA);
        MessageUtils.send(sender, Lang.TAG_USAGE_SETITEMMODEL);
        MessageUtils.send(sender, Lang.TAG_USAGE_SETCRAFTENGINEITEM);
        MessageUtils.send(sender, Lang.TAG_USAGE_SETJUKEBOX);
        MessageUtils.send(sender, Lang.TAG_USAGE_UNSET);
        MessageUtils.send(sender, Lang.TAG_NOTE);
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.ADMIN) || sender.hasPermission(Permissions.TAG);
    }

    @Override
    public List<String> tabComplete(CommandSender player, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return MusicBoxSongManager.getAllSongs().stream().map(song -> song.getName().replace(" ", "_")).filter(name -> name.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return Stream.of("addalias", "removealias", "addtag", "removetag", "list", "setmaterial", "setmodeldata", "setitemmodel", "setcraftengineitem", "setjukebox", "unset").filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        if (args.length == 3) {
            String action = args[1].toLowerCase();
            String songName = args[0].replace("_", " ");
            String upperPrefix = args[2].toUpperCase();
            String lowerPrefix = args[2].toLowerCase();
            switch (action) {
                case "setmaterial":
                case "set-material":
                    return Arrays.stream(Material.values())
                        .filter(Material::isItem)
                        .map(Enum::name)
                        .filter(name -> name.startsWith(upperPrefix))
                        .limit(50)
                        .collect(Collectors.toList());
                case "unset":
                case "clear":
                    return Stream.of("material", "modeldata", "itemmodel", "craftengineitem", "jukebox")
                        .filter(s -> s.startsWith(lowerPrefix)).collect(Collectors.toList());
                case "removealias":
                case "remove-alias": {
                    MusicBoxSong song = MusicBoxSongManager.findByName(songName).orElse(null);
                    if (song != null) {
                        return song.getAliases().stream().filter(a -> a.toLowerCase().startsWith(lowerPrefix)).collect(Collectors.toList());
                    }
                    break;
                }
                case "removetag":
                case "remove-tag": {
                    MusicBoxSong song = MusicBoxSongManager.findByName(songName).orElse(null);
                    if (song != null) {
                        return song.getTags().stream().filter(t -> t.toLowerCase().startsWith(lowerPrefix)).collect(Collectors.toList());
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return new ArrayList<String>();
    }
}
