package com.zinngar.savelogic.util;

/** Receives progress updates from a long-running transfer. Called from background threads. */
public interface Progress {
    Progress NONE = new Progress() {
        @Override public void phase(String label, long totalBytes) {}
        @Override public void bytes(long doneBytes) {}
    };

    /** Starts a new step, such as "Zipping world" or "Uploading part 2 of 3". */
    void phase(String label, long totalBytes);

    /** Bytes completed so far in the current step. */
    void bytes(long doneBytes);
}
