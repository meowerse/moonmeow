package com.limelight.meow.keyboard;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** Records what the PC keyboard sends to the host, as "+NAME" (down) / "-NAME" (up). */
final class RecordingSink implements HostKeySink {
    final List<String> events = new ArrayList<>();

    @Override
    public void sendKey(int androidKeyCode, boolean down) {
        events.add((down ? "+" : "-") + name(androidKeyCode));
    }

    @Override
    public void tapVirtualKey(short virtualKey) {
        events.add("tap:0x" + Integer.toHexString(virtualKey & 0xFFFF));
    }

    String joined() {
        return String.join(" ", events);
    }

    void clear() {
        events.clear();
    }

    static String name(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "SHIFT";
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "RSHIFT";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "CTRL";
            case KeyEvent.KEYCODE_CTRL_RIGHT: return "RCTRL";
            case KeyEvent.KEYCODE_ALT_LEFT: return "ALT";
            case KeyEvent.KEYCODE_ALT_RIGHT: return "RALT";
            case KeyEvent.KEYCODE_META_LEFT: return "SUPER";
            case KeyEvent.KEYCODE_META_RIGHT: return "RSUPER";
            case KeyEvent.KEYCODE_TAB: return "TAB";
            case KeyEvent.KEYCODE_ENTER: return "ENTER";
            case KeyEvent.KEYCODE_DEL: return "BKSP";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "DEL";
            case KeyEvent.KEYCODE_ESCAPE: return "ESC";
            case KeyEvent.KEYCODE_SPACE: return "SPACE";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_SLASH: return "SLASH";
            case KeyEvent.KEYCODE_COMMA: return "COMMA";
            case KeyEvent.KEYCODE_SEMICOLON: return "SEMICOLON";
            case KeyEvent.KEYCODE_GRAVE: return "GRAVE";
            default:
                if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) {
                    return String.valueOf((char) ('A' + code - KeyEvent.KEYCODE_A));
                }
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    return String.valueOf((char) ('0' + code - KeyEvent.KEYCODE_0));
                }
                if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) {
                    return "F" + (code - KeyEvent.KEYCODE_F1 + 1);
                }
                return "#" + code;
        }
    }
}
