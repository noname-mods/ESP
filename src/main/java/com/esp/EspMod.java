package com.esp;

import com.esp.block.BlockEspManager;
import com.esp.block.GemstoneEspManager;
import com.esp.core.EntityEspManager;
import com.esp.core.EspConfig;
import com.esp.core.EspManager;
import com.esp.core.RegistryListCache;
import com.esp.gui.EspConfigScreen;
import com.playerapi.PlayerAPIEvents;
import com.playerapi.PlayerInfo;
import com.playerapi.UpdateChecker;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public class EspMod implements ClientModInitializer {

    /** Set to true to open the config screen on the next tick (avoids chat-close race). */
    public static boolean openConfigNextTick = false;

    public static KeyMapping keyToggle;
    public static KeyMapping keyOpenConfig;

    private static final KeyMapping.Category ESP_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("esp", "category"));

    /**
     * Confirmed to break ESP's glow outline. {@code xray-entity} forces glowing
     * OFF on every mob it hasn't itself selected, so ESP's highlighted mobs are
     * marked glowing but never outline. Removing it restores the glow. (The
     * depth-tested overlay is drawn independently and still shows in line of
     * sight even with xray-entity installed.)
     */
    private static final String[] CONFIRMED_GLOW_BREAKERS = {
            "xray-entity"
    };

    /**
     * Mods that <em>may</em> interfere with the vanilla entity-outline render
     * pass depending on setup — Sodium-family renderers when Iris isn't present
     * to restore the pass, and EntityCulling skipping off-screen entities. Not a
     * guaranteed break, just a heads-up.
     */
    private static final String[] POSSIBLE_RENDER_CONFLICTS = {
            "sodium", "embeddium", "rubidium", "entityculling"
    };

    private List<String> confirmedConflicts = List.of();
    private List<String> possibleConflicts  = List.of();
    private boolean compatWarningShown = false;

    private static final String GITHUB_RELEASES_URL =
            "https://api.github.com/repos/noname-mods/ESP/releases/latest";

    @Override
    public void onInitializeClient() {
        System.out.println("[ESP] Initialising...");

        EspConfig.getInstance().load();

        registerKeybinds();
        registerCommands();
        checkRenderCompatibility();

        PlayerAPIEvents.TICK.register(this::onTick);
        PlayerAPIEvents.WORLD_JOIN.register(this::onWorldJoin);

        System.out.println("[ESP] Ready.");
    }

    // ── Render compatibility check ───────────────────────────────────────────

    /**
     * Detects mods that affect the vanilla entity-outline render pass and queues
     * a one-time in-chat notice. Splits findings into confirmed glow-breakers
     * (xray-entity) and possible conflicts (Sodium-family without Iris,
     * EntityCulling), so the warning can be specific about which it is.
     */
    private void checkRenderCompatibility() {
        FabricLoader loader = FabricLoader.getInstance();
        boolean irisPresent = loader.isModLoaded("iris");

        List<String> confirmed = new ArrayList<>();
        for (String modId : CONFIRMED_GLOW_BREAKERS) {
            if (loader.isModLoaded(modId)) confirmed.add(modId);
        }

        List<String> possible = new ArrayList<>();
        for (String modId : POSSIBLE_RENDER_CONFLICTS) {
            if (!loader.isModLoaded(modId)) continue;
            // Sodium-family renderers generally restore outline support when Iris
            // is present, so don't flag them in that case.
            if ((modId.equals("sodium") || modId.equals("embeddium") || modId.equals("rubidium"))
                    && irisPresent) continue;
            possible.add(modId);
        }

        confirmedConflicts = confirmed;
        possibleConflicts  = possible;

        if (!confirmed.isEmpty()) {
            System.out.println("[ESP] WARNING: " + confirmed + " forces the glow outline OFF on "
                    + "mobs it hasn't selected, so ESP's highlighted mobs won't outline through "
                    + "walls. ESP's Mob ESP now replicates everything it does (highlight entities "
                    + "by type), so removing it loses no capability and restores ESP's glow.");
        }
        if (!possible.isEmpty()) {
            System.out.println("[ESP] Note: " + possible + " may interfere with the glow outline "
                    + "render pass. If highlights don't appear despite ESP detecting the entity "
                    + "(check Debug Logging), this is a likely cause.");
        }
    }

    // ── Keybinds ──────────────────────────────────────────────────────────────

    private void registerKeybinds() {
        keyToggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.esp.toggle",
                InputConstants.UNKNOWN.getValue(),
                ESP_CATEGORY));

        keyOpenConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.esp.config",
                InputConstants.UNKNOWN.getValue(),
                ESP_CATEGORY));
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("esp")
                        .executes(ctx -> {
                            openConfigNextTick = true;
                            return 1;
                        })
                        .then(ClientCommands.literal("nowarn")
                                .executes(ctx -> {
                                    dismissCompatWarning();
                                    return 1;
                                }))
                        .then(ClientCommands.literal("types")
                                .executes(ctx -> {
                                    printMobTypeCodes();
                                    return 1;
                                }))));

        // Fallback: fires when ClientCommandManager did NOT handle the command
        // (server overrode the client command tree).
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String c = command.trim();
            if (c.equalsIgnoreCase("esp nowarn")) {
                dismissCompatWarning();
                return false; // suppress — don't send to server
            }
            if (c.equalsIgnoreCase("esp types")) {
                printMobTypeCodes();
                return false; // suppress — don't send to server
            }
            if (c.equalsIgnoreCase("esp")) {
                openConfigNextTick = true;
                return false; // suppress — don't send to server
            }
            return true;
        });
    }

    /**
     * Prints every Hypixel mob-type icon to chat with its {@code \\u} code. Each line is
     * click-to-copy so the user can paste the code into a group's Patterns field. Shared
     * by {@code /esp types} and the "Mob Type Codes" config button.
     */
    public static void printMobTypeCodes() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui == null) return;
        var chat = mc.gui.getChat();
        chat.addClientSystemMessage(Component.literal(
                "[ESP] Mob-type codes — click a line to copy its code, then paste it into a group's Patterns field:")
                .withStyle(ChatFormatting.AQUA));
        for (com.esp.core.MobTypes.Type t : com.esp.core.MobTypes.ALL) {
            String esc = t.escape();
            chat.addClientSystemMessage(Component.literal("  " + t.glyph() + " " + t.name() + "  §8" + esc)
                    .withStyle(s -> s
                            .withColor(ChatFormatting.GRAY)
                            .withClickEvent(new ClickEvent.CopyToClipboard(esc))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                    "Click to copy " + esc + "  (" + t.hex() + ")")))));
        }
    }

    /** Suppresses future renderer-compatibility warnings (set via the chat link or {@code /esp nowarn}). */
    private void dismissCompatWarning() {
        EspConfig.getInstance().setCompatWarningDismissed(true);
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) {
            mc.gui.getChat().addClientSystemMessage(Component.literal(
                    "[ESP] Renderer-compatibility warnings disabled. Re-enable them in the config (Global tab)."));
        }
    }

    // ── World join ──────────────────────────────────────────────────────────────

    private void onWorldJoin() {
        // Pre-warm the picker lists off-thread (registries + language are loaded by now).
        RegistryListCache.prewarmAsync();
        if (EspConfig.getInstance().isUpdateCheckEnabled()) {
            UpdateChecker.check("esp", GITHUB_RELEASES_URL);
        }
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    private void onTick() {
        if (openConfigNextTick) {
            openConfigNextTick = false;
            try {
                Minecraft.getInstance().setScreen(EspConfigScreen.create(null));
            } catch (Exception e) {
                System.err.println("[ESP] Failed to open config screen: " + e.getMessage());
            }
        }

        if (!PlayerInfo.isInWorld()) return;

        if (!compatWarningShown
                && !EspConfig.getInstance().isCompatWarningDismissed()
                && (!confirmedConflicts.isEmpty() || !possibleConflicts.isEmpty())) {
            compatWarningShown = true;
            String msg;
            if (!confirmedConflicts.isEmpty()) {
                msg = "[ESP] " + confirmedConflicts + " forces ESP's glow off on non-selected mobs. "
                        + "ESP's Mob ESP now does everything it does, so you lose nothing by removing "
                        + "it — and removing it restores the through-wall glow. ";
            } else {
                msg = "[ESP] Heads up: " + possibleConflicts + " may block the glow outline from "
                        + "rendering even when ESP detects a match. See log for details. ";
            }
            Component link = Component.literal("[Click to disable these warnings]")
                    .withStyle(s -> s
                            .withColor(ChatFormatting.GRAY)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent.RunCommand("/esp nowarn"))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                    "Stop showing ESP renderer-compatibility warnings"))));
            Minecraft.getInstance().gui.getChat().addClientSystemMessage(
                    Component.literal(msg).append(link));
        }

        handleKeybinds();
        EspManager.getInstance().tick();
        EntityEspManager.getInstance().tick();
        BlockEspManager.getInstance().tick();
        // Gemstone cluster ESP (GemstoneEspManager) is the future layer built on
        // top of BlockEspManager — clustering, nearest-distance, waypoints. It's
        // left intact but not ticked; its config tab stays hidden until then.
        // GemstoneEspManager.getInstance().tick();
    }

    private void handleKeybinds() {
        if (keyToggle.consumeClick()) {
            EspConfig cfg = EspConfig.getInstance();
            cfg.setGlobalEnabled(!cfg.isGlobalEnabled());
        }
        if (keyOpenConfig.consumeClick()) {
            openConfigNextTick = true;
        }
    }
}
