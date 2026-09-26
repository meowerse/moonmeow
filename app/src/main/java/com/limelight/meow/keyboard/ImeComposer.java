package com.limelight.meow.keyboard;

/**
 * Mirrors the system keyboard's composing text onto the host as it is typed.
 *
 * <p>Gboard and most IMEs do not commit a word letter by letter: they <em>compose</em> it
 * ({@code setComposingText("hel")}, then {@code "hell"}, then {@code "hello"}) and commit it at
 * the space, possibly autocorrected. The host has no composing region, so the only faithful
 * mirror is to type the difference each time: keep what was already sent, backspace over the
 * part that changed, type the rest. That is what this does, with the text shared by the old
 * and the new version never touched, so an autocorrect of "helo" to "hello" costs one
 * backspace and two letters rather than a retype.
 *
 * <p>Pure: it reports what to send through {@link Output} and keeps only the text currently
 * composed on the host.
 */
public final class ImeComposer {

    /** Receives the edits to apply on the host, in order. */
    public interface Output {
        /** Deletes this many characters (code points) before the host cursor. */
        void delete(int codePoints);

        /** Types this text at the host cursor. */
        void insert(String text);
    }

    private final Output output;
    private String composed = "";

    public ImeComposer(Output output) {
        this.output = output;
    }

    /** The composing text as the host currently has it. */
    public String composed() {
        return composed;
    }

    /** The IME replaced its composing text. */
    public void setComposing(CharSequence text) {
        String next = text == null ? "" : text.toString();
        replace(next);
        composed = next;
    }

    /** The IME committed text, replacing whatever it was composing. */
    public void commit(CharSequence text) {
        replace(text == null ? "" : text.toString());
        composed = "";
    }

    /** The IME ended composition: the composed text stays on the host as typed. */
    public void finish() {
        composed = "";
    }

    /**
     * The IME deleted text around the cursor. Whatever was composing is treated as committed
     * first: the host cursor sits after it, so that is what a backspace removes.
     */
    public void deleteBefore(int codePoints) {
        composed = "";
        if (codePoints > 0) {
            output.delete(codePoints);
        }
    }

    private void replace(String next) {
        int common = commonPrefix(composed, next);
        int removed = composed.codePointCount(common, composed.length());
        if (removed > 0) {
            output.delete(removed);
        }
        if (common < next.length()) {
            output.insert(next.substring(common));
        }
    }

    /** The shared prefix, never ending inside a surrogate pair. */
    static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        if (i > 0 && Character.isHighSurrogate(a.charAt(i - 1))) {
            // The pair is shared only in its first half: it differs, so it goes.
            i--;
        }
        return i;
    }
}
