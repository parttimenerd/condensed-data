package me.bechberger.condensed;

import me.bechberger.condensed.Message.StartMessage;
import org.jetbrains.annotations.Nullable;

public class Universe {

    private @Nullable StartMessage startMessage;

    void setStartMessage(StartMessage startMessage) {
        this.startMessage = startMessage;
    }

    public @Nullable StartMessage getStartMessage() {
        return startMessage;
    }

    public boolean hasStartMessage() {
        return startMessage != null;
    }
}
