package com.zinngar.savelogic.client.screen;

import com.zinngar.savelogic.SaveLogic;
import com.zinngar.savelogic.client.CloudStorageManager;
import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.TransferStatus;
import com.zinngar.savelogic.client.WorldSyncMarker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Lists the worlds on this computer and whether each one syncs with the cloud. Only worlds the player chose sync:
 * "Sync" uploads a world (from then on it syncs automatically), "Stop Syncing" keeps a world on this computer only
 * (its cloud copy is left alone and can still be downloaded).
 */
public class SyncedWorldsScreen extends Screen {

    private static final int ROW_HEIGHT = 24;

    private final Screen parent;
    private List<LocalWorld> worlds = List.of();
    private int scroll;
    private boolean wasTransferring;

    public record LocalWorld(String folder, Optional<WorldSyncMarker> marker, long lastPlayed) {
        public boolean synced() {
            return marker.isPresent() && marker.get().linked();
        }

        /** The cloud world this one syncs with, when it isn't the folder's own name (e.g. "My World (2)"). */
        public String cloudName() {
            return marker.map(WorldSyncMarker::cloudName).filter(name -> !name.isEmpty()).orElse(folder);
        }
    }

    public SyncedWorldsScreen(Screen parent) {
        super(Component.translatable("savelogic.worlds.title"));
        this.parent = parent;
    }

    /** Every world in the saves folder, most recently played first. */
    public static List<LocalWorld> localWorlds() {
        List<LocalWorld> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(CloudSync.savesDir())) {
            for (Path dir : dirs.filter(d -> Files.isRegularFile(d.resolve("level.dat"))).toList()) {
                result.add(new LocalWorld(dir.getFileName().toString(), WorldSyncMarker.read(dir), WorldSyncMarker.lastPlayed(dir)));
            }
        } catch (Exception e) {
            SaveLogic.LOGGER.warn("Couldn't list the saves folder", e);
        }
        result.sort(Comparator.comparingLong(LocalWorld::lastPlayed).reversed());
        return result;
    }

    /** Keeps a world on this computer only: it no longer uploads when you leave it or checks the cloud when opened. */
    public static void stopSyncing(String folder) {
        WorldSyncMarker.unlinkedMarker().write(CloudSync.savesDir().resolve(folder));
    }

    @Override
    protected void init() {
        worlds = localWorlds();
        wasTransferring = TransferStatus.INSTANCE.isActive();
        boolean canUpload = CloudStorageManager.getProvider() != null && !wasTransferring;

        int visible = Math.max(1, (this.height - 108) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, worlds.size() - visible));
        int left = left();
        int y = 58;
        for (int i = scroll; i < Math.min(worlds.size(), scroll + visible); i++) {
            LocalWorld world = worlds.get(i);
            if (world.synced()) {
                Button upload = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.worlds.upload_now"),
                        b -> CloudSync.uploadWorld(world.folder(), false, this)).bounds(left + 156, y, 80, 20).build());
                upload.active = canUpload;
                this.addRenderableWidget(Button.builder(Component.translatable("savelogic.worlds.stop"),
                        b -> confirmStop(world)).bounds(left + 240, y, 80, 20).build());
            } else {
                Button sync = this.addRenderableWidget(Button.builder(Component.translatable("savelogic.worlds.sync"),
                        b -> CloudSync.uploadWorld(world.folder(), false, this)).bounds(left + 240, y, 80, 20).build());
                sync.active = canUpload;
            }
            y += ROW_HEIGHT;
        }
        if (worlds.size() > visible) {
            Button up = this.addRenderableWidget(Button.builder(Component.literal("▲"), b -> {
                scroll--;
                this.rebuildWidgets();
            }).bounds(left + 324, 58, 20, 20).build());
            up.active = scroll > 0;
            Button down = this.addRenderableWidget(Button.builder(Component.literal("▼"), b -> {
                scroll++;
                this.rebuildWidgets();
            }).bounds(left + 324, 58 + (visible - 1) * ROW_HEIGHT, 20, 20).build());
            down.active = scroll + visible < worlds.size();
        }

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
    }

    private int left() {
        return this.width / 2 - 160;
    }

    private void confirmStop(LocalWorld world) {
        this.minecraft.setScreen(new ConfirmScreen(stop -> {
            if (stop) {
                stopSyncing(world.folder());
            }
            this.minecraft.setScreen(this);
        }, Component.literal("Stop syncing \"" + world.folder() + "\"?"),
                Component.literal("It stays on this computer and won't be uploaded or checked against the cloud any more. "
                        + "The copy already in the cloud is kept, so you can still download it from Load World from Cloud. "
                        + "Press Sync to start syncing it again."),
                Component.translatable("savelogic.worlds.stop"), Component.translatable("gui.cancel")));
    }

    @Override
    public void tick() {
        // Refresh when an upload started here finishes, so a newly synced world shows as synced.
        if (wasTransferring != TransferStatus.INSTANCE.isActive()) {
            this.rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font, Component.translatable("savelogic.worlds.help"), this.width / 2, 32, 0xFFA0A0A0);
        if (worlds.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.literal("No worlds on this computer yet."), this.width / 2, 66, 0xFFA0A0A0);
        }
        int left = left();
        int y = 58;
        int visible = Math.max(1, (this.height - 108) / ROW_HEIGHT);
        for (int i = scroll; i < Math.min(worlds.size(), scroll + visible); i++) {
            LocalWorld world = worlds.get(i);
            int nameWidth = world.synced() ? 150 : 234;
            Component name = Component.literal(this.font.plainSubstrByWidth((world.synced() ? "☁ " : "") + world.folder(), nameWidth));
            graphics.drawString(this.font, name, left, y + 1, 0xFFFFFFFF);
            String detail = !world.synced() ? "This computer only"
                    : world.cloudName().equals(world.folder()) ? (SaveLogic.CONFIG.autoUpload ? "Synced" : "Synced (upload by hand)")
                    : "Synced as \"" + world.cloudName() + "\"";
            graphics.drawString(this.font, Component.literal(this.font.plainSubstrByWidth(detail, nameWidth))
                    .withStyle(world.synced() ? ChatFormatting.AQUA : ChatFormatting.GRAY), left, y + 11, 0xFFFFFFFF);
            y += ROW_HEIGHT;
        }
    }
}
