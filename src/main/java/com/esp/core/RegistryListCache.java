package com.esp.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Disk-backed cache of the block and entity-type lists used by the picker screens
 * ({@link com.esp.gui.BlockEspScreen} / {@link com.esp.gui.EntityEspScreen}).
 *
 * <p>The lists are generated from the live game registries — so they always match
 * the running version exactly, including other mods' content, with no maintained
 * list anywhere. Generating them walks ~1300 blocks + resolves display names,
 * which is cheap but not free; to keep menus snappy the result is cached to
 * {@code config/esp/registry-cache.json} and only regenerated when:</p>
 * <ul>
 *   <li>the cache file is missing,</li>
 *   <li>the stored Minecraft version differs from the running one, or</li>
 *   <li>the player presses <em>Regenerate</em> in the config (mod add/remove case).</li>
 * </ul>
 *
 * <p>Generation is <strong>lazy</strong> (never at startup) and can be
 * {@linkplain #prewarmAsync() pre-warmed} off-thread on world join, so the first
 * picker open doesn't hitch. Display names are only valid once the language is
 * loaded, which is guaranteed by world-join / menu time.</p>
 */
public final class RegistryListCache {

    private RegistryListCache() {}

    /** One block- or entity-type entry: registry id + localized display name. */
    public static final class Entry {
        public String id;
        public String name;
        public Entry() {}
        public Entry(String id, String name) { this.id = id; this.name = name; }
    }

    private static final class CacheData {
        String mcVersion;
        List<Entry> blocks;
        List<Entry> entities;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            FabricLoader.getInstance().getConfigDir().resolve("esp/registry-cache.json");

    private static volatile CacheData data;

    // ── Public API ─────────────────────────────────────────────────────────────

    public static List<Entry> blocks()   { return ensureCurrent().blocks; }
    public static List<Entry> entities()  { return ensureCurrent().entities; }

    /** Forces a fresh generation and rewrites the cache file. Used by the config button. */
    public static synchronized void regenerate() {
        generateAndSave(mcVersion());
    }

    /** Number of cached blocks / entity types (for the regenerate confirmation message). */
    public static int blockCount()  { return ensureCurrent().blocks.size(); }
    public static int entityCount() { return ensureCurrent().entities.size(); }

    /**
     * Builds the cache off the main thread if it isn't already current, so the
     * first picker open doesn't pay the generation cost. Safe no-op once warm.
     * Call on world join (registries + language are loaded by then).
     */
    public static void prewarmAsync() {
        CacheData d = data;
        if (d != null && mcVersion().equals(d.mcVersion)) return;
        Thread t = new Thread(() -> {
            try {
                ensureCurrent();
            } catch (Exception e) {
                System.err.println("[ESP] Registry-list prewarm failed: " + e.getMessage());
            }
        }, "esp-registry-cache");
        t.setDaemon(true);
        t.start();
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private static synchronized CacheData ensureCurrent() {
        String ver = mcVersion();
        if (data != null && ver.equals(data.mcVersion)) return data;

        CacheData disk = readDisk();
        if (disk != null && ver.equals(disk.mcVersion)
                && disk.blocks != null && disk.entities != null) {
            data = disk;
            return data;
        }
        return generateAndSave(ver);
    }

    private static CacheData generateAndSave(String ver) {
        CacheData c = new CacheData();
        c.mcVersion = ver;
        c.blocks    = buildBlocks();
        c.entities  = buildEntities();
        data = c;
        writeDisk(c);
        System.out.println("[ESP] Generated registry list cache for MC " + ver + ": "
                + c.blocks.size() + " blocks, " + c.entities.size() + " entity types.");
        return c;
    }

    private static List<Entry> buildBlocks() {
        List<Entry> list = new ArrayList<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            if (b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR) continue;
            Identifier id = BuiltInRegistries.BLOCK.getKey(b);
            if (id == null) continue;
            list.add(new Entry(id.toString(), b.getName().getString()));
        }
        list.sort((a, c) -> a.name.compareToIgnoreCase(c.name));
        return list;
    }

    private static List<Entry> buildEntities() {
        List<Entry> list = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null) continue;
            list.add(new Entry(id.toString(), type.getDescription().getString()));
        }
        list.sort((a, c) -> a.name.compareToIgnoreCase(c.name));
        return list;
    }

    private static CacheData readDisk() {
        if (!Files.exists(FILE)) return null;
        try {
            return GSON.fromJson(Files.readString(FILE), CacheData.class);
        } catch (Exception e) {
            System.err.println("[ESP] Failed to read registry cache (will regenerate): " + e.getMessage());
            return null;
        }
    }

    private static void writeDisk(CacheData c) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(c));
        } catch (IOException e) {
            System.err.println("[ESP] Failed to write registry cache: " + e.getMessage());
        }
    }

    private static String mcVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
