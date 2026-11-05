package com.mikeflat.gateway.app.usage;

public final class RecordingOutage {

    private boolean ongoing;

    public boolean failed() {
        boolean starts = !ongoing;
        ongoing = true;
        return starts;
    }

    public boolean succeeded() {
        boolean ends = ongoing;
        ongoing = false;
        return ends;
    }
}
