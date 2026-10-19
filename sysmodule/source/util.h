#pragma once
#include <stdbool.h>

#include <switch.h>

bool UtilSetConsoleScreenMode(bool on);

// LiteDock dock mode. Both return the previous state so it can be restored on disconnect.
Result UtilSetSpeakerMute(bool mute, bool* previous);
Result UtilSetHandheldSleepPlan(s32 plan, s32* previous);