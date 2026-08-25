package com.limelight.meow.viewport;

import android.os.Handler;

/**
 * {@link ViewportReporter.Scheduler} over an Android {@link Handler}.
 *
 * <p>One outstanding task at a time: scheduling again replaces the previous one, which is
 * what "settle {@code N}ms after the last movement" needs. All calls happen on the thread
 * the handler is bound to (the UI thread), which is also the only thread the zoom transform
 * is touched from.
 */
public final class HandlerSettleScheduler implements ViewportReporter.Scheduler {

    private final Handler handler;
    private Runnable outstanding;

    public HandlerSettleScheduler(Handler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler is required");
        }
        this.handler = handler;
    }

    @Override
    public void scheduleSettle(long delayMs, Runnable task) {
        cancelSettle();
        outstanding = task;
        handler.postDelayed(task, delayMs);
    }

    @Override
    public void cancelSettle() {
        if (outstanding != null) {
            handler.removeCallbacks(outstanding);
            outstanding = null;
        }
    }
}
