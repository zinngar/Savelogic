package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.ModUpdater;
import com.zinngar.savelogic.client.SelfUpdate;
import com.zinngar.savelogic.client.SetupSync;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.mods.ModUpdatePlanner.Dependency;
import com.zinngar.savelogic.mods.ModUpdatePlanner.ModStatus;
import com.zinngar.savelogic.mods.ModUpdatePlanner.Plan;
import com.zinngar.savelogic.update.SelfUpdater.Release;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 * Lists the installed mods with what Modrinth has for this Minecraft version, and SaveLogic itself with what GitHub has.
 * Updates are ticked by default; "Update" downloads the ticked ones (plus libraries they need) and they are installed
 * when Minecraft closes.
 */
public class ModUpdatesScreen extends Screen {

    private static final int ROW_HEIGHT = 22;

    private final Screen parent;
    private Plan plan;
    private final Set<String> selected = new HashSet<>();
    private Release selfUpdate;
    private boolean selfUpdateSelected = true;
    private String selfUpdateError;
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
        if (SelfUpdate.canUpdateItself()) {
            SelfUpdate.find().whenComplete((release, error) -> this.minecraft.execute(() -> {
                selfUpdate = release == null ? null : release.orElse(null);
                selfUpdateError = error == null ? null : CloudSync.rootMessage(error);
                this.rebuildWidgets();
            }));
        }
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
                if (row.action() != null) {
                    row.action().run();
                    this.rebuildWidgets();
                }
            }).bounds(left, y, 300, 20).tooltip(row.tooltip() != null ? Tooltip.create(row.tooltip()) : null).build());
            button.active = row.action() != null && !TransferStatus.INSTANCE.isActive();
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
        boolean updatingSelf = chosenSelfUpdate() != null;
        int count = chosen.size() + (updatingSelf ? 1 : 0);
        Button update = this.addRenderableWidget(Button.builder(Component.literal(count == 0 ? "Update"
                        : updatingSelf && chosen.isEmpty() ? "Update SaveLogic"
                        : "Update " + count + " Mod" + (count == 1 ? "" : "s")), b -> startUpdate())
                .bounds(this.width / 2 - 100, this.height - 26, 98, 20).build());
        update.active = (updatingSelf || (!chosen.isEmpty() && !SetupSync.isInstallPending())) && !TransferStatus.INSTANCE.isActive();
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> this.onClose())
                .bounds(this.width / 2 + 2, this.height - 26, 98, 20).build());
    }

    /** A row with an action can be clicked: it ticks or unticks an update. */
    private record Row(Component label, Component tooltip, Runnable action) {}

    private void toggle(ModStatus mod) {
        String key = mod.installed().sha1();
        if (!selected.remove(key)) {
            selected.add(key);
        }
    }

    private Release chosenSelfUpdate() {
        return selfUpdateSelected && SelfUpdate.pendingFileName().isEmpty() ? selfUpdate : null;
    }

    /** SaveLogic's own row, if there is anything to say about it. */
    private Row selfUpdateRow() {
        if (SelfUpdate.pendingFileName().isPresent()) {
            return new Row(Component.literal("SaveLogic update waiting: click to cancel").withStyle(ChatFormatting.YELLOW),
                    Component.literal(SelfUpdate.pendingFileName().get() + " will be installed when you close Minecraft."),
                    SelfUpdate::cancelPending);
        }
        if (selfUpdate != null) {
            return new Row(Component.literal((selfUpdateSelected ? "☑ " : "☐ ") + "SaveLogic:  " + SelfUpdate.currentVersion()
                    + "  →  " + selfUpdate.version()),
                    Component.literal(selfUpdateSelected ? "Click to skip this update" : "Click to include this update"),
                    () -> selfUpdateSelected = !selfUpdateSelected);
        }
        if (selfUpdateError != null) {
            return new Row(Component.literal("SaveLogic: couldn't check GitHub").withStyle(ChatFormatting.GRAY),
                    Component.literal(selfUpdateError), null);
        }
        return null;
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        Row selfRow = selfUpdateRow();
        if (selfRow != null) {
            rows.add(selfRow);
        }
        if (plan == null) {
            return rows;
        }
        for (ModStatus mod : plan.mods()) {
            switch (mod.kind()) {
                case UPDATE -> {
                    boolean on = selected.contains(mod.installed().sha1());
                    rows.add(new Row(Component.literal((on ? "☑ " : "☐ ") + mod.title() + ":  "
                                    + mod.current().versionNumber() + "  →  " + mod.latest().versionNumber()),
                            Component.literal(on ? "Click to skip this update" : "Click to include this update"), () -> toggle(mod)));
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
        List<ModStatus> chosen = chosenUpdates();
        Release self = chosenSelfUpdate();
        if (chosen.isEmpty()) {
            SelfUpdate.stage(self, () -> askToQuit(false));
        } else if (self == null) {
            ModUpdater.stage(chosen, neededDependencies(), () -> askToQuit(true));
        } else {
            ModUpdater.stage(chosen, neededDependencies(), () -> SelfUpdate.stage(self, () -> askToQuit(true)));
        }
        this.rebuildWidgets();
    }

    private void askToQuit(boolean modsToo) {
        this.minecraft.gui.setScreen(new ConfirmScreen(quit -> {
            if (quit) {
                this.minecraft.stop();
            } else {
                this.minecraft.gui.setScreen(parent);
            }
        }, Component.literal(modsToo ? "Mod updates downloaded" : "SaveLogic update downloaded").withStyle(ChatFormatting.GREEN),
                Component.literal(modsToo
                        ? "Close Minecraft to install them; your current mods are backed up to savelogic-backups first. "
                        + "To get the same updates on your other computer, use Upload My Mods & Configs after restarting."
                        : "Close Minecraft to install it; the current version is backed up to savelogic-backups first."),
                Component.translatable("savelogic.setup.quit_now"), Component.translatable("savelogic.setup.later")));
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        graphics.centeredText(this.font, this.font.plainSubstrByWidth(status, this.width - 20), this.width / 2, 26,
                statusIsError ? 0xFFFF6060 : 0xFFA0A0A0);
    }
}
