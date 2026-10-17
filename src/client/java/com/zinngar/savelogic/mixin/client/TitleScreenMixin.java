package com.zinngar.savelogic.mixin.client;

import com.zinngar.savelogic.client.screen.CloudSavesScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    /**
     * Adds a small cloud button to the left of Singleplayer. The column of full-width buttons has no free row
     * (and other mods add to it), but this spot is always on screen at any GUI scale.
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void savelogic$addCloudSavesButton(CallbackInfo ci) {
        int x = this.width / 2 - 100;
        int y = this.height / 4 + 48;
        for (GuiEventListener child : this.children()) {
            if (child instanceof Button button
                    && button.getMessage().getContents() instanceof TranslatableContents contents
                    && contents.getKey().equals("menu.singleplayer")) {
                x = button.getX();
                y = button.getY();
                break;
            }
        }

        Component label = Component.translatable("savelogic.menu.cloud_saves");
        Button cloud = Button.builder(Component.literal("☁"), button -> this.minecraft.gui.setScreen(new CloudSavesScreen(this)))
                .bounds(x - 24, y, 20, 20)
                .tooltip(Tooltip.create(label))
                .createNarration(narration -> label.copy())
                .build();
        this.addRenderableWidget(cloud);
    }
}
