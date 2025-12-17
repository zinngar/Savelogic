package com.zinngar.savelogic.client;

import com.zinngar.savelogic.OperationType;
import net.minecraft.client.gui.screen.Screen;

public class CloudSaveOperation {
    public final OperationType type;
    public final Screen parentScreen;

    public CloudSaveOperation(OperationType type, Screen parentScreen) {
        this.type = type;
        this.parentScreen = parentScreen;
    }
}
