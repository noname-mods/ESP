package com.esp.gui;

import com.esp.core.EspConfig;
import com.esp.core.RegistryListCache;
import com.esp.core.RegistryListCache.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Block-selection screen for Block ESP — a searchable, scrolling grid of every
 * registered block with a checkbox each, plus Clear All and Done. Modelled on the
 * entity-xray selection menu.
 *
 * <p>The block list comes from {@link RegistryListCache} (disk-cached, version-keyed),
 * so opening this screen doesn't re-walk the registry. 26.1.2 replaced immediate-mode
 * {@code GuiGraphics} drawing with a render-state extraction model, so this screen does
 * <strong>no manual rendering</strong> — it composes self-rendering vanilla widgets and
 * virtualises the grid (only the visible rows exist as widgets, recreated on
 * scroll/search).</p>
 */
public class BlockEspScreen extends Screen {

    private static final int COLS  = 3;
    private static final int ROW_H = 22;

    private final Screen parent;
    private final List<Entry> all;
    private final Set<String> selected = new TreeSet<>();

    private List<Entry> filtered;
    private String query = "";
    private int scrollRow = 0;

    private int gridTop, gridLeft, colWidth, visibleRows;

    private final List<AbstractWidget> gridWidgets = new ArrayList<>();
    private EditBox searchBox;

    public BlockEspScreen(Screen parent) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font,
                Component.literal("Block ESP — Select Blocks"));
        this.parent = parent;
        this.all = RegistryListCache.blocks();
        this.filtered = all;
        selected.addAll(EspConfig.getInstance().getBlockEspSettings().blocks);
    }

    @Override
    protected void init() {
        int margin = 20;
        gridLeft    = margin;
        gridTop     = 78;
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
            EspConfig.getInstance().getBlockEspSettings().blocks = new ArrayList<>();
            EspConfig.getInstance().save();
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

        int sw = Math.min(300, this.width - margin * 2);
        searchBox = new EditBox(this.font, (this.width - sw) / 2, 52, sw, 18,
                Component.literal("Search"));
        searchBox.setHint(Component.literal("Search blocks…"));
        searchBox.setMaxLength(100);
        searchBox.setValue(query);
        searchBox.setResponder(this::applyFilter);
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);

        refreshGrid();
    }

    private void applyFilter(String q) {
        this.query = q;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            filtered = all;
        } else {
            List<Entry> out = new ArrayList<>();
            for (Entry e : all) {
                if (e.name.toLowerCase(Locale.ROOT).contains(needle)
                        || e.id.toLowerCase(Locale.ROOT).contains(needle)) {
                    out.add(e);
                }
            }
            filtered = out;
        }
        scrollRow = 0;
        refreshGrid();
    }

    private void scrollBy(int rows) {
        scrollRow += rows;
        refreshGrid();
    }

    private void refreshGrid() {
        for (AbstractWidget w : gridWidgets) removeWidget(w);
        gridWidgets.clear();

        int rows = (filtered.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, rows - visibleRows);
        scrollRow = Math.max(0, Math.min(scrollRow, maxScroll));

        for (int rv = 0; rv < visibleRows; rv++) {
            int row = scrollRow + rv;
            for (int col = 0; col < COLS; col++) {
                int idx = row * COLS + col;
                if (idx >= filtered.size()) break;
                Entry e = filtered.get(idx);
                int x = gridLeft + col * colWidth;
                int y = gridTop + rv * ROW_H;
                Checkbox cb = Checkbox.builder(Component.literal(e.name), this.font)
                        .pos(x, y)
                        .maxWidth(colWidth - 6)
                        .selected(selected.contains(e.id))
                        .onValueChange((box, val) -> toggle(e.id, val))
                        .build();
                gridWidgets.add(cb);
                addRenderableWidget(cb);
            }
        }

        StringWidget status = new StringWidget(0, 70, this.width, 10,
                Component.literal(selected.size() + " selected  •  " + filtered.size()
                        + " shown  •  scroll " + (scrollRow + 1) + "/" + (maxScroll + 1)),
                this.font);
        gridWidgets.add(status);
        addRenderableWidget(status);
    }

    private void toggle(String id, boolean on) {
        if (on) selected.add(id); else selected.remove(id);
        commit();
    }

    private void commit() {
        List<String> list = new ArrayList<>(selected);
        Collections.sort(list);
        EspConfig.getInstance().getBlockEspSettings().blocks = list;
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
