package com.limelight.meow.viewport;

import android.os.Handler;

/**
 * {@link ViewportReporter.Scheduler} over an Android {@link Handler}.
 *
 * <p>One outstanding task at a time: scheduling again replaces the previous one, which is
 * what an "answer by this deadline or the host does not support it" timer needs. All calls
 * happen on the thread the handler is bound to, which is the same thread the reporter runs
 * on -- {@link StreamViewportBinder} owns both.
 */
public final class HandlerDeadlineScheduler implements ViewportReporter.Scheduler {

    private final Handler handler;
    private Runnable outstanding;

    public HandlerDeadlineScheduler(Handler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler is required");
        }
        this.handler = handler;
    }

    @Override
    public void schedule(long delayMs, Runnable task) {
        cancel();
        outstanding = task;
        handler.postDelayed(task, delayMs);
    }

    @Override
    public void cancel() {
        if (outstanding != null) {
            handler.removeCallbacks(outstanding);
            outstanding = null;
        }
    }
}
