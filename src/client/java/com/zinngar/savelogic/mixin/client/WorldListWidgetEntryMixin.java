package com.zinngar.savelogic.mixin.client;

import com.zinngar.savelogic.client.CloudSync;
import com.zinngar.savelogic.client.SaveLogicClient;
import com.zinngar.savelogic.client.SyncGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.level.storage.LevelSummary.SymlinkLevelSummary;
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
    private void savelogic$beforeJoin(CallbackInfo ci) {
        if (SaveLogicClient.uploadPickerScreen != null && this.screen == SaveLogicClient.uploadPickerScreen) {
            // "Save World to Cloud" picker: upload the chosen world instead of opening it.
            ci.cancel();
            SaveLogicClient.uploadPickerScreen = null;
            Screen returnTo = SaveLogicClient.uploadReturnScreen;
            Minecraft.getInstance().setScreen(returnTo);
            CloudSync.uploadWorld(this.summary.getLevelId(), false, returnTo);
            return;
        }
        // Synced worlds get a cloud check before opening; everything else (including vanilla's own prompts) is untouched.
        if (this.summary.primaryActionActive() && !(this.summary instanceof SymlinkLevelSummary)
                && SyncGate.interceptOpen(this.summary.getLevelId(), this.screen)) {
            ci.cancel();
        }
    }
}
