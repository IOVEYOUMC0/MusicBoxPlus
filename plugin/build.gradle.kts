java {
    targetCompatibility = JavaVersion.VERSION_21
    sourceCompatibility = JavaVersion.VERSION_21
}
dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7")
    compileOnly("com.github.koca2000:NoteBlockAPI:2.0-SNAPSHOT")

    api("com.github.cryptomorin:XSeries:11.3.0")
    api("io.github.bananapuncher714:nbteditor:7.19.0")
    api("org.bstats:bstats-bukkit:3.0.2")

    api(project(":nms"))

    compileOnly("org.yaml:snakeyaml:2.0")

}