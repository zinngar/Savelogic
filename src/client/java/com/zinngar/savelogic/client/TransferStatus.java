package com.zinngar.savelogic.client;

import com.zinngar.savelogic.util.Progress;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The one cloud upload or download in progress, drawn as a progress bar at the top of every menu screen.
 * Updated from background threads and read on the render thread, so all state is volatile.
 */
public final class TransferStatus implements Progress {

    public static final TransferStatus INSTANCE = new TransferStatus();

    private static final long RESULT_SHOWN_MS = 5000;
    private static final int WIDTH = 280;

    private volatile String title;
    private volatile String phase = "";
    private volatile long total;
    private volatile long done;
    private volatile long phaseStartNanos;
    private volatile boolean active;
    private volatile String result;
    private volatile boolean failed;
    private volatile long finishedAtMs;

    private TransferStatus() {
    }

    /** Draws the bar on every screen once it has been set up. */
    public static void register() {
        ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) ->
                ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, delta) -> INSTANCE.draw(s, graphics)));
    }

    public boolean isActive() {
        return active;
    }

    /** Starts tracking a transfer; returns false if one is already running. */
    public synchronized boolean begin(String title) {
        if (active) {
            return false;
        }
        this.title = title;
        this.result = null;
        this.failed = false;
        this.active = true;
        phase("Starting", 0);
        return true;
    }

    @Override
    public void phase(String label, long totalBytes) {
        this.phase = label;
        this.total = totalBytes;
        this.done = 0;
        this.phaseStartNanos = System.nanoTime();
    }

    @Override
    public void bytes(long doneBytes) {
        this.done = doneBytes;
    }

    /** Stops showing the bar without a result, e.g. when the user is asked a question first. */
    public void cancel() {
        this.result = null;
        this.active = false;
    }

    public void succeed(String message) {
        finish(message, false);
    }

    public void fail(String message) {
        finish(message, true);
    }

    private void finish(String message, boolean failed) {
        this.result = message;
        this.failed = failed;
        this.finishedAtMs = System.currentTimeMillis();
        this.active = false;
    }

    private void draw(Screen screen, GuiGraphics graphics) {
        boolean showResult = !active && result != null && System.currentTimeMillis() - finishedAtMs < RESULT_SHOWN_MS;
        if (!active && !showResult) {
            return;
        }
        Font font = screen.getFont();
        int left = (screen.width - WIDTH) / 2;
        int top = 4;
        int barTop = top + 16;
        // Results (especially errors) can be long, so wrap them onto up to three lines.
        List<FormattedCharSequence> lines = active ? List.of() : font.split(Component.literal(result), WIDTH);
        lines = lines.subList(0, Math.min(3, lines.size()));
        int bottom = barTop + 12 + 10 * Math.max(1, lines.size());

        graphics.fill(left - 4, top - 2, left + WIDTH + 4, bottom, 0xD0000000);
        graphics.drawString(font, title, left, top + 2, 0xFFFFFFFF);

        if (!active) {
            graphics.fill(left, barTop, left + WIDTH, barTop + 8, failed ? 0xFFB03030 : 0xFF30A040);
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawString(font, lines.get(i), left, barTop + 11 + 10 * i, failed ? 0xFFFF7070 : 0xFF80FF80);
            }
            return;
        }

        String phaseText = phase;
        graphics.drawString(font, phaseText, left + WIDTH - font.width(phaseText), top + 2, 0xFFA0A0A0);

        // Bar background, then fill. Without a known size, show a sliding block so it still looks alive.
        graphics.fill(left, barTop, left + WIDTH, barTop + 8, 0xFF404040);
        long total = this.total;
        long done = Math.min(this.done, total);
        if (total > 0) {
            int filled = (int) (WIDTH * done / total);
            graphics.fill(left, barTop, left + filled, barTop + 8, 0xFF3B82F6);
        } else {
            int block = WIDTH / 5;
            int offset = (int) ((System.currentTimeMillis() / 8) % (WIDTH + block)) - block;
            graphics.fill(left + Math.max(0, offset), barTop, left + Math.min(WIDTH, offset + block), barTop + 8, 0xFF3B82F6);
        }

        graphics.drawString(font, details(total, done), left, barTop + 11, 0xFFD0D0D0);
    }

    private String details(long total, long done) {
        if (total <= 0) {
            return "Working...";
        }
        StringBuilder text = new StringBuilder();
        text.append(done * 100 / total).append("%   ").append(mb(done)).append(" / ").append(mb(total));
        double seconds = (System.nanoTime() - phaseStartNanos) / 1e9;
        if (seconds > 1 && done > 0) {
            double perSecond = done / seconds;
            text.append("   ").append(mb((long) perSecond)).append("/s");
            long remaining = (long) ((total - done) / perSecond);
            text.append("   ").append(remaining / 60).append(':').append(String.format("%02d", remaining % 60)).append(" left");
        }
        return text.toString();
    }

    private static String mb(long bytes) {
        return bytes >= 1024L * 1024 * 1024
                ? String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
                : String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
