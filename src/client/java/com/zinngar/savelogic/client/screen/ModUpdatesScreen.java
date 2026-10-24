package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.client.ModUpdater;
import com.zinngar.savelogic.client.SetupSync;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.mods.ModUpdatePlanner.Dependency;
import com.zinngar.savelogic.mods.ModUpdatePlanner.ModStatus;
import com.zinngar.savelogic.mods.ModUpdatePlanner.Plan;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lists the installed mods with what Modrinth has for this Minecraft version. Updates are ticked by default; "Update"
 * downloads the ticked ones (plus libraries they need) and they are installed when Minecraft closes.
 */
public class ModUpdatesScreen extends Screen {

    private static final int ROW_HEIGHT = 22;

    private final Screen parent;
    private Plan plan;
    private final Set<String> selected = new HashSet<>();
    private String status = "Checking your mods on Modrinth...";
    private boolean statusIsError;
    private int scroll;

    public ModUpdatesScreen(Screen parent) {
        super(Component.literal("Mod Updates for Minecraft " + SetupSync.minecraftVersion()));
        this.parent = parent;
    }

    @Override
    public void added() {
        super.added();
        ModUpdater.check(result -> {
            plan = result;
            result.updates().forEach(u -> selected.add(u.installed().sha1()));
            int updates = result.updates().size();
            status = updates == 0 ? "All your mods that Modrinth knows are up to date."
                    : updates + " update" + (updates == 1 ? "" : "s") + " available. Untick any you want to skip.";
            this.rebuildWidgets();
        }, error -> {
            status = "Couldn't check for updates: " + error;
            statusIsError = true;
            this.rebuildWidgets();
        });
    }

    @Override
    protected void init() {
        List<Row> rows = rows();
        int visible = Math.max(1, (this.height - 92) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, rows.size() - visible));
        int left = this.width / 2 - 150;
        int y = 42;
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
            Row row = rows.get(i);
            Button button = this.addRenderableWidget(Button.builder(row.label(), b -> {
                if (row.update() != null) {
                    String key = row.update().installed().sha1();
                    if (!selected.remove(key)) {
                        selected.add(key);
                    }
                    this.rebuildWidgets();
                }
            }).bounds(left, y, 300, 20).tooltip(row.tooltip() != null ? Tooltip.create(row.tooltip()) : null).build());
            button.active = row.update() != null && !TransferStatus.INSTANCE.isActive();
            y += ROW_HEIGHT;
        }
        if (rows.size() > visible) {
            Button up = this.addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
                scroll--;
                this.rebuildWidgets();
            }).bounds(left + 304, 42, 20, 20).build());
            up.active = scroll > 0;
            Button down = this.addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
                scroll++;
                this.rebuildWidgets();
            }).bounds(left + 304, 42 + (visible - 1) * ROW_HEIGHT, 20, 20).build());
            down.active = scroll + visible < rows.size();
        }

        List<ModStatus> chosen = chosenUpdates();
        Button update = this.addRenderableWidget(Button.builder(Component.literal(chosen.isEmpty() ? "Update"
                        : "Update " + chosen.size() + " Mod" + (chosen.size() == 1 ? "" : "s")), b -> startUpdate())
                .bounds(this.width / 2 - 100, this.height - 26, 98, 20).build());
        update.active = !chosen.isEmpty() && !TransferStatus.INSTANCE.isActive() && !SetupSync.isInstallPending();
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> this.onClose())
                .bounds(this.width / 2 + 2, this.height - 26, 98, 20).build());
    }

    private record Row(Component label, Component tooltip, ModStatus update) {}

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (plan == null) {
            return rows;
        }
        for (ModStatus mod : plan.mods()) {
            switch (mod.kind()) {
                case UPDATE -> {
                    boolean on = selected.contains(mod.installed().sha1());
                    rows.add(new Row(Component.literal((on ? "☑ " : "☐ ") + mod.title() + ":  "
                                    + mod.current().versionNumber() + "  →  " + mod.latest().versionNumber()),
                            Component.literal(on ? "Click to skip this update" : "Click to include this update"), mod));
                }
                case UP_TO_DATE -> rows.add(new Row(Component.literal("✔ " + mod.title() + "  " + mod.current().versionNumber())
                        .withStyle(ChatFormatting.GRAY), Component.literal("Up to date"), null));
                case NO_BUILD -> rows.add(new Row(Component.literal(mod.title() + ": no build for " + SetupSync.minecraftVersion() + " yet")
                        .withStyle(ChatFormatting.GRAY), Component.literal("Modrinth has this mod, but no newer Fabric build "
                        + "for this Minecraft version."), null));
                case UNKNOWN -> rows.add(new Row(Component.literal(mod.installed().fileName() + ": not on Modrinth")
                        .withStyle(ChatFormatting.GRAY), Component.literal("Modrinth doesn't recognise this file (for example "
                        + "CurseForge-only or self-built mods), so it can't be checked."), null));
            }
        }
        for (Dependency dependency : neededDependencies()) {
            rows.add(new Row(Component.literal("+ " + dependency.title() + " " + dependency.version().versionNumber()
                    + " (needed by " + dependency.neededBy() + ")").withStyle(ChatFormatting.AQUA),
                    Component.literal("A library the update needs. It will be added too."), null));
        }
        return rows;
    }

    private List<ModStatus> chosenUpdates() {
        return plan == null ? List.of()
                : plan.updates().stream().filter(u -> selected.contains(u.installed().sha1())).toList();
    }

    /** Libraries needed by the ticked updates only. */
    private List<Dependency> neededDependencies() {
        if (plan == null) {
            return List.of();
        }
        Set<String> chosenTitles = new HashSet<>();
        chosenUpdates().forEach(u -> chosenTitles.add(u.title()));
        return plan.dependencies().stream().filter(d -> chosenTitles.contains(d.neededBy())).toList();
    }

    private void startUpdate() {
        ModUpdater.stage(chosenUpdates(), neededDependencies(), () -> this.minecraft.setScreen(new ConfirmScreen(quit -> {
            if (quit) {
                this.minecraft.stop();
            } else {
                this.minecraft.setScreen(parent);
            }
        }, Component.literal("Mod updates downloaded").withStyle(ChatFormatting.GREEN),
                Component.literal("Close Minecraft to install them; your current mods are backed up to savelogic-backups first. "
                        + "To get the same updates on your other computer, use Upload My Mods & Configs after restarting."),
                Component.translatable("savelogic.setup.quit_now"), Component.translatable("savelogic.setup.later"))));
        this.rebuildWidgets();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, this.font.plainSubstrByWidth(status, this.width - 20), this.width / 2, 26,
                statusIsError ? 0xFFFF6060 : 0xFFA0A0A0);
    }
}
