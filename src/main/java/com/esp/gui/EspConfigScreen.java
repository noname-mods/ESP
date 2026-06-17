package com.esp.gui;

import com.esp.core.EspConfig;
import com.esp.core.EspGroup;
import com.esp.core.RegistryListCache;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.awt.Color;

public class EspConfigScreen {

    private EspConfigScreen() {}

    public static Screen create(Screen parent) {
        EspConfig cfg = EspConfig.getInstance();

        var builder = YetAnotherConfigLib.createBuilder()
                .title(Component.literal("ESP Configuration"))

                // ── Global ────────────────────────────────────────────────────
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("Global"))
                        .tooltip(Component.literal("Settings that apply to all groups."))

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Enabled"))
                                .description(OptionDescription.of(Component.literal(
                                        "Master toggle. When off, all groups are disabled\n" +
                                        "and no entities are highlighted.")))
                                .binding(false, cfg::isGlobalEnabled, cfg::setGlobalEnabled)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Double>createBuilder()
                                .name(Component.literal("Label Search Radius (blocks)"))
                                .description(OptionDescription.of(Component.literal(
                                        "How far below and around a matching label to look\n" +
                                        "for the corresponding mob.\n\n" +
                                        "Labels on Hypixel are typically 1–3 blocks above\n" +
                                        "the mob. Increase if mobs are not being found;\n" +
                                        "decrease to avoid picking up the wrong nearby mob.")))
                                .binding(4.0, cfg::getLabelSearchRadius, cfg::setLabelSearchRadius)
                                .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                        .range(1.0, 10.0).step(0.5))
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Debug Logging"))
                                .description(OptionDescription.of(Component.literal(
                                        "Prints scan diagnostics to the game log: how many\n" +
                                        "entities/labels were seen, per-group match counts,\n" +
                                        "and the nearest match's text + coordinates.\n\n" +
                                        "Use this to check whether a pattern just isn't\n" +
                                        "matching, or whether no labels are being found at\n" +
                                        "all (wrong radius, group disabled, etc).\n" +
                                        "Leave off for normal use — it's noisy.")))
                                .binding(false, cfg::isDebugLogging, cfg::setDebugLogging)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Renderer Conflict Warnings"))
                                .description(OptionDescription.of(Component.literal(
                                        "Show a one-time chat warning on world join when a mod\n" +
                                        "that can break the glow outline is installed (e.g.\n" +
                                        "xray-entity, which forces glow off on non-selected mobs).\n\n" +
                                        "Turn this off to silence the warning — same as clicking\n" +
                                        "the 'disable these warnings' link in the message.")))
                                .binding(true,
                                        () -> !cfg.isCompatWarningDismissed(),
                                        v -> cfg.setCompatWarningDismissed(!v))
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Highlight Overlay"))
                                .description(OptionDescription.of(Component.literal(
                                        "Draws a translucent colored box matching the\n" +
                                        "highlighted mob's hitbox. Depth-tested, so it's\n" +
                                        "only visible in line of sight — complements the\n" +
                                        "glow outline, which shows through walls but is\n" +
                                        "only a thin border once you can actually see the\n" +
                                        "mob directly.")))
                                .binding(true, cfg::isOverlayEnabled, cfg::setOverlayEnabled)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(Option.<Double>createBuilder()
                                .name(Component.literal("Overlay Opacity"))
                                .description(OptionDescription.of(Component.literal(
                                        "How opaque the highlight overlay box is.\n" +
                                        "0 = invisible, 1 = fully solid.")))
                                .binding(0.35, cfg::getOverlayAlpha, cfg::setOverlayAlpha)
                                .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                        .range(0.05, 1.0).step(0.05))
                                .build())

                        .option(Option.<Boolean>createBuilder()
                                .name(Component.literal("Update Check"))
                                .description(OptionDescription.of(Component.literal(
                                        "On world join, check GitHub for a newer ESP release\n" +
                                        "and post a one-time chat notice (with a click-to-hide\n" +
                                        "link). If the latest release targets a different\n" +
                                        "Minecraft version, the notice says so. Nothing is\n" +
                                        "downloaded automatically.")))
                                .binding(true, cfg::isUpdateCheckEnabled, cfg::setUpdateCheckEnabled)
                                .controller(BooleanControllerBuilder::create)
                                .build())

                        .option(ButtonOption.createBuilder()
                                .name(Component.literal("Regenerate Block/Entity Lists"))
                                .description(OptionDescription.of(Component.literal(
                                        "The block and entity-type pickers are built from the\n" +
                                        "game's registries and cached to disk\n" +
                                        "(config/esp/registry-cache.json). The cache regenerates\n" +
                                        "automatically when the Minecraft version changes.\n\n" +
                                        "Press this after adding or removing mods so newly\n" +
                                        "available blocks/entities show up in the pickers.")))
                                .text(Component.literal("Regenerate now"))
                                .action((screen, opt) -> {
                                    RegistryListCache.regenerate();
                                    Minecraft mc = Minecraft.getInstance();
                                    if (mc.player != null) {
                                        mc.gui.getChat().addClientSystemMessage(Component.literal(
                                                "[ESP] Rebuilt picker lists: " + RegistryListCache.blockCount()
                                                + " blocks, " + RegistryListCache.entityCount() + " entity types."));
                                    }
                                })
                                .build())

                        .build());

        // ── Mob ESP ───────────────────────────────────────────────────────────
        builder.category(buildEntityEspCategory(cfg));

        // ── Block ESP ─────────────────────────────────────────────────────────
        builder.category(buildBlockEspCategory(cfg));

        // ── Gemstone cluster ESP (future 1.1 layer, deferred) ─────────────────
        // The full gemstone feature (clustering, nearest-distance, waypoints) is
        // built on top of Block ESP later. buildGemstoneCategory(...) is kept
        // intact; re-enable this line with the GemstoneEspManager.tick() call.
        // builder.category(buildGemstoneCategory(cfg));

        // ── One tab per entity-label group ────────────────────────────────────
        for (EspGroup group : cfg.getGroups()) {
            builder.category(buildGroupCategory(group, cfg));
        }

        return builder.build().generateScreen(parent);
    }

    // ── Mob ESP category builder ──────────────────────────────────────────────

    private static ConfigCategory buildEntityEspCategory(EspConfig cfg) {
        EspConfig.EntityEspSettings e = cfg.getEntityEspSettings();
        return ConfigCategory.createBuilder()
                .name(Component.literal("Mob ESP"))
                .tooltip(Component.literal(
                        "Highlights every entity of the types you pick — regardless of\n" +
                        "labels. Uses the same glow + overlay as the label groups."))

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Enabled"))
                        .description(OptionDescription.of(Component.literal(
                                "Master toggle for entity-type ESP. When on, every entity\n" +
                                "whose type is selected gets the glow outline (through\n" +
                                "walls) plus the depth-tested overlay (in line of sight,\n" +
                                "if the global Highlight Overlay is on).")))
                        .binding(false, () -> e.enabled, v -> { e.enabled = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(ButtonOption.createBuilder()
                        .name(Component.literal("Select Entity Types"))
                        .description(OptionDescription.of(Component.literal(
                                "Open a searchable list of every entity type. Tick the\n" +
                                "ones to highlight; Clear All empties the selection.\n" +
                                "Changes save as you go.")))
                        .text(Component.literal("Open entity list →"))
                        .action((screen, opt) ->
                                Minecraft.getInstance().setScreen(new EntityEspScreen(screen)))
                        .build())

                .option(Option.<java.awt.Color>createBuilder()
                        .name(Component.literal("Highlight Color"))
                        .description(OptionDescription.of(Component.literal(
                                "Colour of the glow outline and overlay for matched entities.")))
                        .binding(
                                new java.awt.Color(e.color),
                                () -> new java.awt.Color(e.color),
                                v -> { e.color = v.getRGB() & 0xFFFFFF; cfg.save(); })
                        .controller(ColorControllerBuilder::create)
                        .build())

                .option(Option.<Double>createBuilder()
                        .name(Component.literal("Scan Radius (blocks)"))
                        .description(OptionDescription.of(Component.literal(
                                "How far from the player to scan for matching entities.\n" +
                                "Bounded by the server's entity-tracking range (~48 on\n" +
                                "Hypixel) — past that there's nothing to find.")))
                        .binding(48.0, () -> e.scanRadius, v -> { e.scanRadius = v; cfg.save(); })
                        .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                .range(8.0, 48.0).step(2.0))
                        .build())

                .option(Option.<Integer>createBuilder()
                        .name(Component.literal("Scan Interval (ticks)"))
                        .description(OptionDescription.of(Component.literal(
                                "Ticks between scans. Entities move, so 10 (0.5 s) keeps\n" +
                                "highlights tracking smoothly. Raise to reduce overhead.")))
                        .binding(10, () -> e.scanIntervalTicks,
                                v -> { e.scanIntervalTicks = v; cfg.save(); })
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(1, 100).step(1))
                        .build())

                .build();
    }

    // ── Block ESP category builder ────────────────────────────────────────────

    private static ConfigCategory buildBlockEspCategory(EspConfig cfg) {
        EspConfig.BlockEspSettings b = cfg.getBlockEspSettings();
        return ConfigCategory.createBuilder()
                .name(Component.literal("Block ESP"))
                .tooltip(Component.literal(
                        "Highlights every instance of the block types you pick,\n" +
                        "through walls. Use the Select Blocks button to choose them."))

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Enabled"))
                        .description(OptionDescription.of(Component.literal(
                                "Master toggle for block ESP. When on, all approved\n" +
                                "block types in range are outlined through walls.")))
                        .binding(false, () -> b.enabled, v -> { b.enabled = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(ButtonOption.createBuilder()
                        .name(Component.literal("Select Blocks"))
                        .description(OptionDescription.of(Component.literal(
                                "Open a searchable list of every block. Tick the ones\n" +
                                "to highlight; Clear All empties the selection. Changes\n" +
                                "save as you go.")))
                        .text(Component.literal("Open block list →"))
                        .action((screen, opt) ->
                                Minecraft.getInstance().setScreen(new BlockEspScreen(screen)))
                        .build())

                .option(Option.<java.awt.Color>createBuilder()
                        .name(Component.literal("Highlight Color"))
                        .description(OptionDescription.of(Component.literal(
                                "Colour of the box drawn around each approved block.")))
                        .binding(
                                new java.awt.Color(b.color),
                                () -> new java.awt.Color(b.color),
                                v -> { b.color = v.getRGB() & 0xFFFFFF; cfg.save(); })
                        .controller(ColorControllerBuilder::create)
                        .build())

                .option(Option.<Double>createBuilder()
                        .name(Component.literal("Opacity"))
                        .description(OptionDescription.of(Component.literal(
                                "Fill opacity of each block box. The outline is always\n" +
                                "drawn a little stronger so blocks stay visible.")))
                        .binding(0.35, () -> b.alpha, v -> { b.alpha = v; cfg.save(); })
                        .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                .range(0.05, 1.0).step(0.05))
                        .build())

                .option(Option.<Double>createBuilder()
                        .name(Component.literal("Scan Radius (blocks)"))
                        .description(OptionDescription.of(Component.literal(
                                "Cube half-extent scanned around the player. Bounded by\n" +
                                "loaded chunks (render distance). Higher values scan more\n" +
                                "blocks per pass — cost grows with the cube of the radius,\n" +
                                "so keep it as low as works for you.")))
                        .binding(24.0, () -> b.scanRadius, v -> { b.scanRadius = v; cfg.save(); })
                        .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                .range(8.0, 48.0).step(2.0))
                        .build())

                .option(Option.<Integer>createBuilder()
                        .name(Component.literal("Scan Interval (ticks)"))
                        .description(OptionDescription.of(Component.literal(
                                "Fallback ticks between scans. 20 = 1 s. Scans also run\n" +
                                "automatically when you move 8+ blocks or a block changes\n" +
                                "nearby, so this can stay fairly high.")))
                        .binding(20, () -> b.scanIntervalTicks,
                                v -> { b.scanIntervalTicks = v; cfg.save(); })
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(5, 200).step(5))
                        .build())

                .build();
    }

    // ── Gemstone category builder ─────────────────────────────────────────────

    private static ConfigCategory buildGemstoneCategory(EspConfig cfg) {
        EspConfig.GemstoneSettings g = cfg.getGemstoneSettings();
        return ConfigCategory.createBuilder()
                .name(Component.literal("Gemstone ESP"))
                .tooltip(Component.literal(
                        "Scans nearby blocks for gemstone clusters\n" +
                        "(currently: red stained glass / glass pane).\n\n" +
                        "Clusters are sorted by distance; the nearest\n" +
                        "will be used for waypoint rendering once\n" +
                        "that feature is implemented."))

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Enabled"))
                        .description(OptionDescription.of(Component.literal(
                                "Toggle gemstone block scanning on/off.")))
                        .binding(false, () -> g.enabled,
                                v -> { g.enabled = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<Double>createBuilder()
                        .name(Component.literal("Scan Radius (blocks)"))
                        .description(OptionDescription.of(Component.literal(
                                "Cube half-extent scanned around the player.\n" +
                                "A radius of 30 checks a 61×61×61 volume.\n\n" +
                                "Practical limit: blocks only exist client-side in\n" +
                                "loaded chunks, so this can never see further than\n" +
                                "your render distance. The slider is capped at 32\n" +
                                "(a 65-block cube) because cost grows with the CUBE\n" +
                                "of the radius — doubling it scans ~8× the blocks.\n\n" +
                                "Note: higher values cost SIGNIFICANTLY more per\n" +
                                "scan. The chunk-section palette check skips empty\n" +
                                "space, but veins still trigger full 16³ section\n" +
                                "sweeps — on a busy server like Hypixel keep this\n" +
                                "as low as you can and lengthen the scan interval\n" +
                                "rather than pushing the radius up.")))
                        .binding(30.0, () -> g.scanRadius,
                                v -> { g.scanRadius = v; cfg.save(); })
                        .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                .range(10.0, 32.0).step(2.0))
                        .build())

                .option(Option.<Integer>createBuilder()
                        .name(Component.literal("Scan Interval (ticks)"))
                        .description(OptionDescription.of(Component.literal(
                                "How often the block scan runs.\n" +
                                "20 = 1 s, 40 = 2 s (default).\n\n" +
                                "Gemstone clusters don't move, so a 2-second\n" +
                                "interval is usually plenty.")))
                        .binding(40, () -> g.scanIntervalTicks,
                                v -> { g.scanIntervalTicks = v; cfg.save(); })
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(5, 200).step(5))
                        .build())

                .option(Option.<Double>createBuilder()
                        .name(Component.literal("Cluster Merge Distance (blocks)"))
                        .description(OptionDescription.of(Component.literal(
                                "Any two blocks within this distance of each\n" +
                                "other are merged into the same cluster.\n\n" +
                                "Raise to combine loose veins scattered a few\n" +
                                "blocks apart; lower to keep them split.")))
                        .binding(5.0, () -> g.clusterMergeDistance,
                                v -> { g.clusterMergeDistance = v; cfg.save(); })
                        .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                .range(1.0, 15.0).step(0.5))
                        .build())

                .build();
    }

    // ── Group category builder ────────────────────────────────────────────────

    private static ConfigCategory buildGroupCategory(EspGroup group, EspConfig cfg) {
        return ConfigCategory.createBuilder()
                .name(Component.literal(group.name.isBlank() ? "Group" : group.name))

                .option(Option.<Boolean>createBuilder()
                        .name(Component.literal("Enabled"))
                        .binding(false, () -> group.enabled, v -> { group.enabled = v; cfg.save(); })
                        .controller(BooleanControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Component.literal("Name"))
                        .description(OptionDescription.of(Component.literal(
                                "Display name for this group (shown as the tab label\n" +
                                "after reopening the config screen).")))
                        .binding(group.name, () -> group.name, v -> { group.name = v; cfg.save(); })
                        .controller(StringControllerBuilder::create)
                        .build())

                .option(Option.<String>createBuilder()
                        .name(Component.literal("Patterns"))
                        .description(OptionDescription.of(Component.literal(
                                "Comma-separated match terms. Any term matching = highlight.\n\n" +
                                "Plain term  →  text contains the word\n" +
                                "  Example:  magmatic\n\n" +
                                "Quoted term  →  ALL words inside quotes must appear\n" +
                                "  Example:  \"magmatic muspelheim\"\n\n" +
                                "Combined:  \"sea archer\", muspelheim\n" +
                                "  → highlight if text has both words in 'sea archer'\n" +
                                "    OR if text contains 'muspelheim'\n\n" +
                                "Mob type symbols work too:  ⚓, ♆")))
                        .binding("", () -> group.patterns, v -> { group.patterns = v; cfg.save(); })
                        .controller(StringControllerBuilder::create)
                        .build())

                .option(Option.<Color>createBuilder()
                        .name(Component.literal("Glow Color"))
                        .description(OptionDescription.of(Component.literal(
                                "Colour of the outline drawn around highlighted mobs.")))
                        .binding(
                                new Color(group.color),
                                () -> new Color(group.color),
                                v -> { group.color = v.getRGB() & 0xFFFFFF; cfg.save(); })
                        .controller(ColorControllerBuilder::create)
                        .build())

                .option(Option.<Double>createBuilder()
                        .name(Component.literal("Scan Radius (blocks)"))
                        .description(OptionDescription.of(Component.literal(
                                "How far from the player to scan for label entities\n" +
                                "matching this group's patterns.\n\n" +
                                "Practical limit: the SERVER decides how far away\n" +
                                "entities are sent to your client (its entity-tracking\n" +
                                "range). On Hypixel this is roughly 40-48 blocks, and\n" +
                                "floating text labels often stop arriving even sooner.\n" +
                                "Past that range there is simply nothing to find — the\n" +
                                "entities don't exist client-side — so the slider is\n" +
                                "capped at 48 rather than a number you could never use.\n\n" +
                                "Note: higher values scan a larger volume of entity\n" +
                                "data every interval and will cost noticeably more on\n" +
                                "busy servers. Use the smallest radius that reaches\n" +
                                "your target.")))
                        .binding(16.0, () -> group.scanRadius, v -> { group.scanRadius = v; cfg.save(); })
                        .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                .range(4.0, 48.0).step(2.0))
                        .build())

                .option(Option.<Integer>createBuilder()
                        .name(Component.literal("Scan Interval (ticks)"))
                        .description(OptionDescription.of(Component.literal(
                                "How many ticks between scans for this group.\n" +
                                "20 ticks = 1 second.\n\n" +
                                "Lower = more responsive but more CPU work.\n" +
                                "Higher = less overhead, slight delay on new spawns.")))
                        .binding(10, () -> group.scanIntervalTicks,
                                v -> { group.scanIntervalTicks = v; cfg.save(); })
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                .range(1, 100).step(1))
                        .build())

                .build();
    }
}
