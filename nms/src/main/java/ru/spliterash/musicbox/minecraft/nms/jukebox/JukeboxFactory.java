package ru.spliterash.musicbox.minecraft.nms.jukebox;

import org.bukkit.block.Jukebox;
import ru.spliterash.musicbox.minecraft.nms.NMSUtils;

import java.lang.reflect.InvocationTargetException;

public class JukeboxFactory {
    private static final String START_PATH = "ru.spliterash.musicbox.minecraft.nms.jukebox.versions.";
    private static final Class<? extends IJukebox> clazz;

    static {
        String raw = NMSUtils.getRawVersion();
        int iV = NMSUtils.parseMajorVersion(raw);

        String className;
        if (iV == 21) {
            className = START_PATH + "V21";
        }
        /*
        else if (iV == 20) {
            className = switch (raw) {
                case "1.20", "1.20.1" -> START_PATH + "V20_1";
                case "1.20.2" -> START_PATH + "V20_2";
                case "1.20.3", "1.20.4" -> START_PATH + "V20_3";
                default -> START_PATH + "V20_5";
            };
        } else if (iV == 19) {
            className = switch (raw) {
                case "1.19.2" -> START_PATH + "V19_2";
                case "1.19.3" -> START_PATH + "V19_3";
                case "1.19.4" -> START_PATH + "V19_4";
                default -> null;
            };
        } else if (iV == 18)
            className = START_PATH + "V18";
        else if (iV == 17)
            className = START_PATH + "V17";
        else if (iV >= 13)
            className = START_PATH + "V13_16";
        else if (iV == 12)
            className = START_PATH + "V12";
        */
        else
            className = null;
        if (className == null)
            throw new IllegalArgumentException("Unsupported version: " + raw);
        try {
            //noinspection unchecked
            clazz = (Class<? extends IJukebox>) Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static IJukebox getJukebox(Jukebox jukebox) {
        try {
            return clazz.getConstructor(Jukebox.class).newInstance(jukebox);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
