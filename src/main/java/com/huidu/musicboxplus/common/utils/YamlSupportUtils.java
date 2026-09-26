package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.MusicBoxConfig;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.CustomClassLoaderConstructor;

import java.lang.reflect.Constructor;

public final class YamlSupportUtils {
    public static CustomClassLoaderConstructor createCustomClassLoaderConstructor() {
        try {
            Constructor<?> constructor = CustomClassLoaderConstructor.class.getConstructor(Class.class, ClassLoader.class);
            return (CustomClassLoaderConstructor)constructor.newInstance(MusicBoxConfig.class, MusicBoxConfig.class.getClassLoader());
        }
        catch (Exception e) {
            return new CustomClassLoaderConstructor(MusicBoxConfig.class, MusicBoxConfig.class.getClassLoader(), new LoaderOptions());
        }
    }

    private YamlSupportUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}

