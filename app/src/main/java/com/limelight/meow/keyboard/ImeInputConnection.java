package com.limelight.meow.keyboard;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;

import com.limelight.ui.StreamContainer;

import java.util.ArrayDeque;

/**
 * The input connection the stream exposes to the system keyboard when "commit text" is on.
 *
 * <h2>The bug this replaces</h2>
 * The connection it replaces overrode {@code commitText} only and extended a
 * {@link BaseInputConnection} in dummy mode. In dummy mode the base class keeps the composing
 * text in a private buffer and <em>sends that buffer as key events</em> from
 * {@code finishComposingText()}. Because the override never let the base class see the
 * commit, the buffer still held the word when the keyboard later finished composing (on
 * hide, on a cursor move, on the next field), and the host received every composed word a
 * second time. Composition was also invisible until the space bar.
 *
 * <p>Here the composing text is mirrored live through {@link ImeComposer}: each keystroke
 * reaches the host as it is typed, autocorrect costs a few backspaces, and nothing is ever
 * held back to be sent twice. Non-Latin text (Russian from Gboard) takes the same route as
 * UTF-8 text, and still combines with PC-keyboard modifiers through
 * {@code Game.handleCommitText}.
 *
 * <h2>Ordering</h2>
 * {@code Game} queues text and sends it a main-loop turn later, but sends backspaces at once.
 * An insert followed by a delete could therefore reach the host in the wrong order. Edits are
 * drained from one queue, and after an insert the drain yields a turn (re-posts itself), so the
 * text Game queued is always sent before the next edit.
 */
public final class ImeInputConnection extends BaseInputConnection implements ImeComposer.Output {

    private static final Object DELETE = new Object();

    private final StreamContainer.InputCallbacks callbacks;
    private final ImeComposer composer = new ImeComposer(this);
    private final Handler handler;
    private final ArrayDeque<Object> pending = new ArrayDeque<>();
    private final ArrayDeque<Integer> deleteCounts = new ArrayDeque<>();
    private boolean drainPosted;
    private final Runnable drain = this::drain;

    public ImeInputConnection(View view, StreamContainer.InputCallbacks callbacks) {
        super(view, false);
        this.callbacks = callbacks;
        this.handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        composer.commit(text);
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        composer.setComposing(text);
        return true;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        // There is no host text to re-compose: the host owns it and we cannot read it back.
        return false;
    }

    @Override
    public boolean finishComposingText() {
        composer.finish();
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        composer.deleteBefore(beforeLength);
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        composer.deleteBefore(beforeLength);
        return true;
    }

    // ---- ImeComposer.Output --------------------------------------------------------------

    @Override
    public void delete(int codePoints) {
        pending.add(DELETE);
        deleteCounts.add(codePoints);
        scheduleDrain();
    }

    @Override
    public void insert(String text) {
        pending.add(text);
        scheduleDrain();
    }

    private void scheduleDrain() {
        if (!drainPosted) {
            drainPosted = true;
            handler.post(drain);
        }
    }

    private void drain() {
        drainPosted = false;
        while (!pending.isEmpty()) {
            Object op = pending.poll();
            if (callbacks == null) {
                continue;
            }
            if (op == DELETE) {
                callbacks.handleDeleteSurroundingText(deleteCounts.poll(), 0);
            } else {
                callbacks.handleCommitText((String) op);
                if (!pending.isEmpty()) {
                    scheduleDrain();
                    return;
                }
            }
        }
        deleteCounts.clear();
    }

    /** What the host has as composing text; for tests. */
    String composedForTest() {
        return composer.composed();
    }
}
