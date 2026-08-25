// Declarations moonmeow's native entry points share with upstream translation units.
//
// This exists so callbacks.c and meowjni.c cannot drift: an `extern` declaration in
// one file and a definition in the other is a signature mismatch the compiler
// cannot see, and the result is undefined behaviour at the call site rather than a
// build error. One declaration, included by both.
//
// Keep this file free of anything but declarations -- it is included from an
// upstream file, and the point of that include being one line is that an upstream
// sync can never conflict on it.

#pragma once

#include <stdint.h>

// ConnListenerSetViewport: the host's echo of the viewport rectangle it applied, in
// the negotiated stream resolution, plus the captured desktop size (0/0 when the
// host did not report it). Defined in meowjni.c; installed into
// CONNECTION_LISTENER_CALLBACKS.setViewport by callbacks.c.
//
// Safe to call before the feature is initialised: it returns immediately when
// MeowViewportBridge has not been class-initialised, which is the case whenever the
// viewport-following preference is off.
void MeowBridgeClSetViewport(uint16_t x, uint16_t y, uint16_t width, uint16_t height,
                             uint16_t desktopWidth, uint16_t desktopHeight);
