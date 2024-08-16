package ru.spliterash.musicbox;


import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.spliterash.musicbox.utils.ComponentUtils;
import ru.spliterash.musicbox.utils.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


@SuppressWarnings({"ArraysAsListWithZeroOrOneArgument", "SpellCheckingInspection", "unused", "RedundantSuppression"})
public enum Lang {
    NO_PERMISSIONS(
            "&cSorry, but you don't have permission to do that!",
            "&6Похоже у тебя нет разрешения на это действие"
    ),
    // В консоли русский вариант будет смотреться... да никак не будет, UTF-8 👍👍👍
    ONLY_PLAYERS(
            "&cOnly players can execute this command!"
    ),
    SPECIFY_PLAYER(
            "&cThis command can only be executed if you give a specific player's username."
    ),
    SONG_NAME(
            "&6{song}"
    ),
    SONG_LORE(
            Arrays.asList(
                    "&6Duration&8: &e{length}",
                    "&6Author&8: &e{author}",
                    "&6Original author&8: &e{original_author}"
            ),
            Arrays.asList(
                    "&7Продолжительность: &b{length}",
                    "&7Автор: &b{author}",
                    "&7Оригинальный автор: &b{original_author}"
            )
    ),
    GUI_TITLE(
            "&l&3MusicBox &8{container} &0{page}&7/&0{last_page}"
    ),
    FOLDER_FORMAT(
            "&e{folder}"
    ),
    CURRENTLY_PLAYING(
            "&e6Now playing &e{song}"
    ),
    ADD_CONTAINER_TO_PLAYLIST(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&6Right click&e to add to your playlist"
            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&bПравый клик&7 чтобы добавить в своей плейлист музыку отсюда"
            )
    ),
    CURRENT_IN_PLAYLIST(
            "&aIn playlist",
            "&aВ плейлисте"
    ),
    SONG_PANEL_NOW_PLAYING(
            Arrays.asList(
                    "",
                    "&aɴᴏᴡ ᴘʟᴀʏɪɴɢ"
            ),
            Arrays.asList(
                    "",
                    "&aСейчас играет"
            )
    ),
    SONG_PANEL_SWITCH_TO(
            Arrays.asList(
                    "",
                    "&7ᴄʟɪᴄᴋ ᴛᴏ ᴘʟᴀʏ"
            ),
            Arrays.asList(
                    "",
                    "&7Нажми чтобы проиграть"
            )
    ),
    ADD_MUSIC_TO_PLAYLIST(
            Arrays.asList("&6Left click&7 to add this song to playlist"),
            Arrays.asList("&bЛевый клик&7 чтобы добавить эту мелодию в плейлист")
    ),
    COMMAND_HELP_SHOP(
            "&6/musicbox shop &8- &eOpen disc shop",
            "&b/musicbox shop&6 - Открыть магазин дисков"
    ),
    COMMAND_HELP_GIVE(
            "&6/musicbox give &8- &eGive disc",
            "&b/musicbox give&6 - Выдать диск"
    ),
    COMMAND_HELP(
            Arrays.asList(
                    "&6/musicbox &8- &eOpen music GUI"
            ),
            Arrays.asList(
                    "&b/musicbox &6- Открыть инвентарь с музыкой"
            )
    ),
    ADMIN_HELP(
            Arrays.asList(
                    "&6/musicbox shop [player] (name) &8-&e Open disc shop or buy disc with name for player",
                    "&6/musicbox give [player] (name) &8-&e Open give disc gui or give song with name to the player",
                    "&6/musicbox give_single [player] &8-&e Open give GUI to player, but he can only select 1 disc",
                    "&6/musicbox play [player] [name] &8-&e Play sound to player",
                    "&6/musicbox silent [on,off,switch] (player) &8-&e Change player silent mode",
                    "&6/musicbox shutup [player] &8-&e off player SongPlayer",
                    "&6/musicbox reload &8-&e Reload plugin & all music"
            ),
            Arrays.asList(
                    "&b/musicbox shop [player] (name)&6 - Открыть магазин дисков или купить пластинку с именем для игрока",
                    "&b/musicbox give [player] (name)&6 - Выдать диск игроку",
                    "&b/musicbox give_single [player]&6 - Открыть GUI выдачи игроку, но выбрать можно только 1 диск",
                    "&b/musicbox play [player] [name]&6 - Включить игроку определённую музыку",
                    "&b/musicbox silent [on,off,switch] [player] &6- Сменить тихий режим игрока",
                    "&b/musicbox shutup [player] &6- выключить проигрыватель игрока",
                    "&b/musicbox reload &6- перезагрузить плагин со всей музыкой"
            )
    ),
    BUY_MUSIC_LORE(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&eClick to buy this disc",
                    "&7Price&8: &6{price} $"
            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&eНажми чтобы купить эту пластинку",
                    "&7Цена: &6{price} $"
            )
    ),
    CANT_SWITCH(
            "You cant switch play mode",
            "&6Вы не можете поменять режим проигрывания"
    ),
    NEXT(
            "&6Next",
            "&6Вперёд"
    ),
    BACK(
            "&6Back",
            "&6Назад"
    ),
    BUY_CONTAINER_LORE(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&6Right click&e to buy this category",
                    "&7Price&8: &6{price} $"
            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&eНажми &cправой кнопкой&e чтобы купить весь этот сундук",
                    "&7Цена: &6{price} $"
            )
    ),
    NO_HAS_MONEY(
            "&6You don't have enough &e{amount}&6 to buy this!",
            "&6Вам не хватает &b{amount}&6 чтобы купить это"
    ),
    NO_INVENTORY_SPACE(
            "&cYou dont have space in your inventory, sorry!",
            "&6У вас нет места в инвентаре"
    ),
    DISC_PURCHASED(
            "&6You have successfully purchased the &e{disc}&6 disc!",
            "&6Вы успешно купили диск &b{disc}"
    ),
    PARENT_CONTAINER(
            "&6Return to parent folder",
            "&6Вернуться на уровень выше"
    ),
    HUMAN_TIME_MINUTE(
            "{value}m.",
            "{value}м."
    ),
    HUMAN_TIME_SECOND(
            "{value}s.",
            "{value}с."
    ),
    SONG_STOP(
            "&cStop the music?",
            "&cСтоп"
    ),
    REWIND_BUTTON(
            "&6Rewind",
            "&6Перемотка"
    ),
    NOT_PLAY(
            "&6Music currently isn't playing",
            "&6В данный момент вы не слушаете музыку"
    ),
    BLOCK_NOT_PLAY(
            "&6This block doesn't currently play music",
            "&6Этот блок в данный момент не проигрывает музыку"
    ),
    CONTROL_GUI_TITLE(
            "&6ɴᴏᴡ ᴘʟᴀʏɪɴɢ &8- &e&n{song}",
            "Музыкальная панель - играет &0&n{song}"
    ),
    REWIND_TO(
            "&6Rewind to &e{time} &8({percent}%)",
            "&6Перемотать на &b{time}&e({percent}%)"
    ),
    REWOUND(
            "&6Song rewound to &e{time} &8({percent}%)",
            "&6Вы перемотали проигрыватель на &b{time}&e({percent}%)"
    ),
    CLOSE(
            "&cClose",
            "&cЗакрыть"
    ),
    ENABLE(
            "&aEnable",
            "&aВключено"
    ),
    DISABLE(
            "&cDisable",
            "&cВыключено"
    ),
    SWITCH_MODE_LORE(
            Arrays.asList(
                    "&7Status&8: {status}",
                    "&7Speaker mode allows &6nearby players&7 hear your music"
            ),
            Arrays.asList(
                    "&7Статус: {status}",
                    "&7Режим колонки позволит игрокам рядом с вами слышать вашу музыку"
            )
    ),
    SWITCH_MODE_NO_PERMISSIONS_LORE(
            Arrays.asList(
                    "&cYou don't have permission to do that! You'll need &4musicbox.speaker&c to change modes!"
            ),
            Arrays.asList(
                    "&7Вам необходимо иметь &bmusicbox.speaker чтобы включить колонку"
            )
    ),
    SPEAKER_MODE(
            "&6Speaker mode",
            "&6Режим колонки"
    ),
    PLAYLIST_EDITOR(
            "&6Playlist list",
            "&6Список плейлистов"
    ),
    PLAYLIST_NAME(
            "&6{name}"
    ),
    GO_BACK_TO_PLAYLIST(
            "&6Return to playlist",
            "&6Вернуться к плейлисту"
    ),
    PLAYLIST_LORE(
            Arrays.asList(
                    "&6Track count&8: &e{count}",
                    "&6Duration&8: &e{duration}"
            ),
            Arrays.asList(
                    "&7Количество треков: &b{count}",
                    "&7Продолжительность: &b{duration}"
            )
    ),
    PLAYLIST_ITEM_LORE(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&ePress the &6left mouse button&e to listen",
                    "&eTo delete, press the &6right mouse button&e!"
            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&7Чтобы прослушать нажми &bлевую кнопку",
                    "&7Чтобы удалить нажми &bправую кнопку"
            )
    ),
    PLAYLIST_LIST_TITLE(
            "&l&3Playlist list &0{page}&7/&0{last_page}",
            "&l&3Список плейлистов &0{page}&7/&0{last_page}"
    ),
    PLAYLIST_EDITOR_LIST_TITLE(
            "&l&3Edit {playlist}&f &0{page}&7/&0{last_page}",
            "&l&3Редактирование {playlist}&f &0{page}&7/&0{last_page}"
    ),
    MASTER_PLAYLIST(
            "&6Master playlist",
            "&6Главный плейлист"
    ),
    MASTER_PLAYLIST_LORE(
            Arrays.asList(
                    "&7Include &6ALL&7 tracks",
                    "&6Always&7 random"
            ),
            Arrays.asList(
                    "&7Включает в себя &aВСЕ&7 треки",
                    "&cВсегда&7 рандомный"
            )
    ),
    CREATE_NEW_PLAYLIST(
            "&6Create a new playlist",
            "&6Создать новый плейлист"
    ),
    NEW_PLAYLIST_MESSAGE(
            "&6To create a new playlist type &e/musicbox playlist <name>&6!",
            "&6Чтобы создать новый плейлист напиши &b/musicbox playlist Имя плейлиста"),
    SAVE_PLAYLIST_CHANGE(
            "&6Save changes",
            "&6Сохранить изменения"
    ),
    PLAYLIST_SAVED(
            "&6Playlist &e{playlist}&6 saved",
            "&6Плейлист &b{playlist}&6 успешно сохранён"
    ),
    DELETE_PLAYLIST(
            "&6Delete playlist",
            "&6Удалить плейлист"
    ),
    PLAYLIST_DELETED(
            "&6You deleted your playlist named \"&e{playlist}\"&6!",
            "&6Вы удалили плейлист &b{playlist}"
    ),
    SHUFFLE_PLAYLIST(
            "&6Shuffle playlist",
            "&6Перемешать плейлист"
    ),
    RENAME_PLAYLIST(
            "&6Rename playlist",
            "&6Переименовать плейлист"
    ),
    DONT_FORGET_TO_SAVE(
            Arrays.asList(
                    "&cDON'T FORGET TO SAVE!"
            ),
            Arrays.asList(
                    "&cНЕ ЗАБУДЬ&7 сохранить"
            )
    ),
    ADD_MUSIC_TO_PLAYLIST_ITEM(
            "&6Add music to this playlist",
            "&6Добавить музыку в этот плейлист"
    ),
    PLAYLIST_ZERO_SIZE(
            "&cYou can't save empty playlist!",
            "&cВы не можете сохранить пустой плейлист"
    ),
    CHILL_CHILL_MAN(
            "&cA save is currently in progress. Have a little patience, please!",
            "&cОстынь чел, сохранение в процессе"
    ),
    NEXT_PLAYLIST_SONG_TITLE(
            "&6Play next playlist song",
            "&6Следующая мелодия"
    ),
    PLAYLIST_SONG_NUM(
            "&9{num}) "
    ),
    CURRENT_PLAYLIST_SONG(
            "{num}&6&l{song}"
    ),
    ANOTHER_PLAYLIST_SONG(
            "{num}&e{song}"
    ),
    DEFAULT_PLAYLIST_LORE(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&6Left click&e to play",
                    "&6Right click&e to edit"
            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&bЛевый клик для прослушивания",
                    "&bПравый клик для редактирования"
            )
    ),
    SONG_NOT_FOUND(
            "&cSong not found. Are you sure you typed it in correctly?",
            "&Мелодия не найдена"
    ),
    YOU_GET_DISC(
            "&6You got the &e{disc} &6 disc!",
            "&6Вы получили диск &b{disc}"
    ),
    GET_DISC_LORE(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&6Left click&e to get this disc"
            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&7Нажми чтобы получить этот диск"
            )
    ),
    GET_ALL_CONTAINER_LORE(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&6Right click&e to get all discs"
            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&7Нажми правой кнопкой чтобы получить всё из этого сундука"
            )
    ),
    INPUT_NAME(
            "&6Input a name &e/musicbox playlist <name>",
            "&6Введи имя &b/musicbox playlist имя"
    ),
    PLAYER_OFLLINE(
            "&6Player &e{player}&6 is currently offline!",
            "&6Игрок &b{player}&6 не в сети"
    ),
    SHUT_UPPED(
            "&6Player &e{player}&6 has been muted.",
            "&6Игрок &b{player}&6 больше не воспроизводит музыку"
    ),
    SIGN_PLAYLIST_LORE(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&6Click to setup a sign playlist"
            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&eНажми чтобы установить плейлист этой таблички"
            )),
    SIGN_SONG_LORE(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&6Click to setup a sign song"

            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&eНажми чтобы выбрать музыку для таблички"

            )
    ),
    RANDOM_MODE_BUTTON(
            "&6Random mode {status}",
            "&6Рандомный режим {status}"
    ),
    SIGN_CONTAINER_LORE(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&eRight click&e to setup this container on sign"

            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&eПравый клик&7 чтобы установить этот контейнер на табличку"

            )
    ), WRONG_SIGN_FACE(
            "&cThe sign is backwards. &6Don't worry, we've fixed it for you!",
            "&cНеправильная сторона таблички, но ничего, сейчас мы это исправим"),
    SEARCH_INFO_SIGN_HOVER(
            Arrays.asList(
                    "&7If enabled, it will display the current playlist",
                    "&7on the sign above or below. This searches for it from below and",
                    "&7from above at a length of &e5&7 blocks from the current sign."

            ),
            Arrays.asList(
                    "&7Если включено, то будет выводить текущий список проигрывания",
                    "&7на табличку сверху или снизу",
                    "&7Ищет её снизу и сверху на длине &e5&7 блоков от текущей таблички"
            )
    ),
    ENDLESS_SIGN_MODE(
            "&6Endless sign mode {status}",
            "&6Бесконечное проигрывание {status}"
    ),
    SEARCH_INFO_SIGN_TITLE(
            "&6Search info sign {status}",
            "&6Искать информационную табличку {status}"
    ),
    INFO_SIGN_OFF(
            Arrays.asList(
                    "&8[&6&lᴊᴜᴋᴇʙᴏx ɪɴꜰᴏ&8]",
                    "&e-=[||]=-",
                    "&7Current Status&8:",
                    "&cᴏꜰꜰʟɪɴᴇ"
            ),
            Arrays.asList(
                    "Это табличка нужна",
                    "для проигрывателя",
                    "Сейчас он",
                    "&cВЫКЛЮЧЕН"
            )
    ),
    CONTROL_PANEL_BUTTON(
            "&6Songplayer panel",
            "&6Панель проигрывателя"
    ),
    PREVENT_DESTROY_TITLE(
            "&6Prevent sign destroy {status}",
            "&6Предотвратить автоуничтожение проигрывателя {status}"
    ),
    PREVENT_DESTROY_LORE(
            Arrays.asList(
                    "&7If enabled, the plugin will not destroy the sign if no one hears",
                    "&7it for more than &e60&7 seconds. &8(Configurable in the config!)",
                    "&7Also protects the songplayer between server restarts!",
                    "",
                    "&cThis button is visible only to those who have &4musicbox.admin&c!"
            ),
            Arrays.asList(
                    "&7Если включено, плагин не будет уничтожать проигрыватель таблички",
                    "&7в случае если её никто не слышит более &b60&7 секунд(настраивается в конфиге)",
                    "&7а так же это защищает табличку от рестарта сервера",
                    "",
                    "&7Эту кнопку видят только те, у кого есть &cmusicbox.admin"
            )
    ),
    CLICK_TO_PLAY_CONTAINER(
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&7Right click&e to play this chest"
            ),
            Arrays.asList(
                    "",
                    "&7==========================",
                    "&eПравый клик&7 чтобы послушать всё что тут лежит"
            )
    ),
    LEGACY_DISC_NOT_FOUND(
            "&cSorry, but we can't find any song matching &4{song}&c!",
            "&6Извини, но я не могу найти звук для &b{song}"),
    LEGACY_DISC_REPLACE(
            "&6It looks like this is a disc from an older version of the plugin. " +
                    "Don't worry, we'll convert it to a new one! &eTry again with the new disc!",
            "&6Похоже это диск от старой версии плагина, но не волнуйся, " +
                    "мы переделаем его в новый. Кликни ещё раз, но теперь новым диском, он должен быть где то у тебя в инвентаре"
    ),
    SILENT_MODE_LORE(
            Arrays.asList(
                    "&7In silent mode you can hear only hear songs played by &6yourself&7.",
                    "&7Songs from player speakers, jukeboxes, or signs will NOT play!",
                    "&7Status&8: {status}"
            ),
            Arrays.asList(
                    "&7В тихом режиме вы можете слышать &bтолько свою музыку&7.",
                    "&7Никаких других проигрывателей от игроков с колокной, дисков или табличек",
                    "&7Статус: {status}"
            )
    ),
    SILENT_MODE(
            "&6Silent mode",
            "&6Тихий режим"
    ),
    SILENT_MODE_RESPONSE(
            "&6Set &e{player}&6's silent mode to {state}",
            "&6Тихий режим установлен в состояние {state}&6 для игрока &b{player}"
    );
    /**
     * Оригинальные переводы
     * 0 индекс - англиский
     * 1 индекс - русский
     */
    private final Object[] original = new Object[2];
    private Object selected;

    /**
     * Конструктор для простых строк
     *
     * @param en На англиском
     * @param ru На русском
     */
    Lang(String en, String ru) {
        original[0] = en;
        original[1] = ru;
    }

    /**
     * Конструктор для многострочных переводов
     *
     * @param en На англиском
     * @param ru На русском
     */
    Lang(List<String> en, List<String> ru) {
        original[0] = en;
        original[1] = ru;
    }

    Lang(List<String> en) {
        this(en, en);
    }

    Lang(String en) {
        this(en, en);
    }

    public static void reload(File folder, String lang) {
        File langFile = new File(folder, lang + ".yml");
        int index;
        if (lang.equals("ru"))
            index = 1;
        else
            index = 0;
        fill(langFile, index);
    }

    private static void fill(File langFile, int index) {
        YamlConfiguration conf = YamlConfiguration.loadConfiguration(langFile);
        boolean saveNeed = false;
        for (Lang value : values()) {
            Object obj = conf.get(value.name());
            if (obj == null) {
                obj = value.original[index];
                conf.set(value.name(), obj);
                saveNeed = true;
            }
            if (obj instanceof String) {
                value.selected = StringUtils.t(obj.toString());
            } else {
                //noinspection unchecked
                List<String> list = (List<String>) obj;
                value.selected = StringUtils.t(list);
            }
        }
        if (saveNeed) {
            try {
                conf.save(langFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public String toString() {
        if (isString()) {
            return selected.toString();
        } else {
            //noinspection unchecked
            List<String> list = (List<String>) selected;
            return String.join("\n", list);
        }
    }

    private boolean isString() {
        return selected instanceof String;
    }

    public List<String> toList(String... replace) {
        if (isString()) {
            ArrayList<String> list = new ArrayList<>(1);
            String text = StringUtils.replace(selected.toString(), replace);
            list.add(text);
            return list;
        } else if (replace.length > 0) {
            //noinspection unchecked
            return ((List<String>) selected)
                    .stream()
                    .map(s -> StringUtils.replace(s, replace))
                    .collect(Collectors.toList());
        } else
            //noinspection unchecked
            return new ArrayList<>(((List<String>) selected));
    }

    public BaseComponent[] toComponent(String... replace) {
        if (isString())
            return TextComponent.fromLegacyText(toString(replace));
        else {
            //noinspection unchecked
            return ComponentUtils.join(((List<String>) selected)
                    .stream()
                    .map(s -> StringUtils.replace(s, replace))
                    .collect(Collectors.toList()), "\n");
        }
    }

    public String toString(String... replace) {
        return StringUtils.replace(toString(), replace);
    }

    public String[] toArray() {
        return toList().toArray(new String[0]);
    }

    public String toPlainText(String... replace) {
        return ChatColor.stripColor(toString(replace));
    }
}
