rootProject.name = "MusicBox"

// The root project is the plugin itself; musicbox-api is a library the plugin shades in and
// downstream plugins can depend on separately.
include("musicbox-api")
