package com.esp.gui;

import com.esp.core.EspConfig;
import com.esp.core.MobTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mob-type selection screen for Mob Type ESP — a simple checkbox grid of the 23
 * Hypixel bestiary types ({@link MobTypes#ALL}). Ticking a type highlights every
 * mob whose name plate carries that type's pack glyph. Structurally the same
 * self-rendering-widget grid as {@link EntityEspScreen}, minus the search box
 * (the list is short and fixed).
 *
 * <p>Selection is stored by type <em>name</em> in
 * {@link EspConfig.MobTypeEspSettings#types}; the manager resolves names to glyphs
 * at scan time, so the config stays readable and pack-independent.</p>
 */
public class MobTypeEspScreen extends Screen {

    private static final int COLS  = 2;
    private static final int ROW_H = 22;

    private final Screen parent;
    private final Set<String> selected = new TreeSet<>();

    private int gridTop, gridLeft, colWidth, visibleRows;
    private int scrollRow = 0;

    private final List<AbstractWidget> gridWidgets = new ArrayList<>();

    public MobTypeEspScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font,
                Component.literal("Mob Type ESP — Select Types"));
        this.parent = parent;
        selected.addAll(EspConfig.getInstance().getMobTypeEspSettings().types);
    }

    @Override
    protected void init() {
        int margin = 20;
        gridLeft    = margin;
        gridTop     = 60;
        int gridBottom = this.height - 16;
        colWidth    = (this.width - margin * 2) / COLS;
        visibleRows = Math.max(1, (gridBottom - gridTop) / ROW_H);

        addRenderableWidget(new StringWidget(0, 8, this.width, 12, getTitle(), this.font));

        int by = 26;
        int wClear = 90, wScroll = 36, wDone = 80, gap = 6;
        int total = wClear + wScroll + wScroll + wDone + gap * 3;
        int x = (this.width - total) / 2;
        addRenderableWidget(Button.builder(Component.literal("Clear All"), b -> {
            selected.clear();
            commit();
            refreshGrid();
        }).bounds(x, by, wClear, 20).build());
        x += wClear + gap;
        addRenderableWidget(Button.builder(Component.literal("▲"), b -> scrollBy(-visibleRows))
                .bounds(x, by, wScroll, 20).build());
        x += wScroll + gap;
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> scrollBy(visibleRows))
                .bounds(x, by, wScroll, 20).build());
        x += wScroll + gap;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(x, by, wDone, 20).build());

        refreshGrid();
    }

    private void scrollBy(int rows) {
        scrollRow += rows;
        refreshGrid();
    }

    private void refreshGrid() {
        for (AbstractWidget w : gridWidgets) removeWidget(w);
        gridWidgets.clear();

        List<MobTypes.Type> all = MobTypes.ALL;
        int rows = (all.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, rows - visibleRows);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll));

        for (int rv = 0; rv < visibleRows; rv++) {
            int row = scrollRow + rv;
            for (int col = 0; col < COLS; col++) {
                int idx = row * COLS + col;
                if (idx >= all.size()) break;
                MobTypes.Type t = all.get(idx);
                int x = gridLeft + col * colWidth;
                int y = gridTop + rv * ROW_H;
                // Label shows the name + the glyph (renders as the icon with the pack loaded).
                Checkbox cb = Checkbox.builder(
                                Component.literal(t.name() + "  " + t.glyph()), this.font)
                        .pos(x, y)
                        .maxWidth(colWidth - 6)
                        .selected(selected.contains(t.name()))
                        .onValueChange((box, val) -> toggle(t.name(), val))
                        .build();
                gridWidgets.add(cb);
                addRenderableWidget(cb);
            }
        }

        StringWidget status = new StringWidget(0, 46, this.width, 10,
                Component.literal(selected.size() + " selected  •  " + all.size()
                        + " types  •  scroll " + (scrollRow + 1) + "/" + (maxScroll + 1)),
                this.font);
        gridWidgets.add(status);
        addRenderableWidget(status);
    }

    private void toggle(String name, boolean on) {
        if (on) selected.add(name); else selected.remove(name);
        commit();
    }

    private void commit() {
        EspConfig.getInstance().getMobTypeEspSettings().types = new ArrayList<>(selected);
        EspConfig.getInstance().save();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scrollBy(scrollY > 0 ? -2 : 2);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        commit();
        this.minecraft.setScreen(parent);
    }
}
