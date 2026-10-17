package com.zinngar.savelogic.mixin.client;

import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.SaveLogicClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldSelectionList.WorldListEntry.class)
public abstract class WorldListWidgetEntryMixin {

    @Shadow @Final private Screen screen;
    @Shadow @Final private LevelSummary summary;

    @Inject(method = "joinWorld", at = @At("HEAD"), cancellable = true)
    private void savelogic$uploadInsteadOfJoin(CallbackInfo ci) {
        if (SaveLogicClient.uploadPickerScreen == null || this.screen != SaveLogicClient.uploadPickerScreen) {
            return;
        }
        ci.cancel();
        SaveLogicClient.uploadPickerScreen = null;

        CloudSync.uploadWorld(this.summary.getLevelId());
        Minecraft.getInstance().gui.setScreen(SaveLogicClient.uploadReturnScreen);
    }
}
