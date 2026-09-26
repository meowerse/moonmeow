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

#include <stdbool.h>
#include <stdint.h>

// ConnListenerSetViewportV2: the host's echo of the viewport rectangle it applied, in
// the negotiated stream resolution, plus the captured desktop size (0/0 when the host
// did not report it) and the host frame number of the first frame encoded with it (0
// when the host is an echo-v1 host). Defined in meowjni.c; installed into
// CONNECTION_LISTENER_CALLBACKS.setViewportV2 by callbacks.c. Installing V2 means the
// library no longer calls .setViewport for the same echo, so there is no V1 handler.
//
// Safe to call before the feature is initialised: it returns immediately when
// MeowViewportBridge has not been class-initialised.
void MeowBridgeClSetViewportV2(uint16_t x, uint16_t y, uint16_t width, uint16_t height,
                               uint16_t desktopWidth, uint16_t desktopHeight,
                               uint32_t frameIndex);

// ConnListenerCursorPosition: the host cursor hotspot (0x3004) in the same uncropped
// reference space as the viewport. Only arrives after LiSendCursorSubscribe(true).
// Inert until MeowStreamBridge is class-initialised.
void MeowBridgeClCursorPosition(uint16_t x, uint16_t y, bool visible, uint16_t seq);

// ConnListenerBitrateApplied: the host changed the encoder bitrate in answer to our
// receiver reports (0x3005 APPLIED). Inert until MeowStreamBridge is class-initialised.
void MeowBridgeClBitrateApplied(uint32_t kbps);
