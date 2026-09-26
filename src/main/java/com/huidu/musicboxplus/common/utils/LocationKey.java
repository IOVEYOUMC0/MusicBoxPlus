package com.huidu.musicboxplus.common.utils;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

// Location wrapper with proper equals/hashCode so it can be used as a map key
// (Bukkit's Location compares by exact double coordinates).
public final class LocationKey {
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;
    private int cachedHashCode;
    
    public LocationKey(Location location) {
        World world = location.getWorld();
        this.worldName = world != null ? world.getName() : null;
        this.x = location.getBlockX();
        this.y = location.getBlockY();
        this.z = location.getBlockZ();
        this.cachedHashCode = 0;
    }

    // Package-private: lets tests build a key without a real Bukkit Location.
    // Also usable from production code that has chunk-level coords already.
    LocationKey(String worldName, int x, int y, int z) {
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.cachedHashCode = 0;
    }

    public static LocationKey of(Location location) {
        return new LocationKey(location);
    }
    
    public Location toLocation(World world) {
        return new Location(world, x, y, z);
    }
    
    public String getWorldName() {
        return worldName;
    }
    
    public int getX() {
        return x;
    }
    
    public int getY() {
        return y;
    }
    
    public int getZ() {
        return z;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LocationKey that = (LocationKey) o;
        return x == that.x && y == that.y && z == that.z && Objects.equals(worldName, that.worldName);
    }
    
    @Override
    public int hashCode() {
        if (cachedHashCode == 0) {
            cachedHashCode = Objects.hash(worldName, x, y, z);
        }
        return cachedHashCode;
    }
    
    @Override
    public String toString() {
        return "LocationKey{" + worldName + ", " + x + ", " + y + ", " + z + '}';
    }
}
