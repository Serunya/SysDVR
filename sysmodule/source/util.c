#include <switch.h>
#include "util.h"
#include "modes/modes.h"

bool UtilSetConsoleScreenMode(bool on)
{
	Result rc = lblInitialize();
	LOG("lblInitialize %x", rc);

	if (R_SUCCEEDED(rc))
	{
		LblBacklightSwitchStatus lblstatus = LblBacklightSwitchStatus_Disabled;
		if (R_SUCCEEDED(lblGetBacklightSwitchStatus(&lblstatus)))
		{
			LOG("lblGetBacklightSwitchStatus %x", rc);
			if (on && lblstatus == LblBacklightSwitchStatus_Disabled)
				rc = lblSwitchBacklightOn(0);
			else if (!on && lblstatus == LblBacklightSwitchStatus_Enabled)
				rc = lblSwitchBacklightOff(0);
		}
		lblExit();
	}
	
	LOG("UtilSetConsoleScreenMode(%d) %x", on, rc);
	return R_SUCCEEDED(rc);
}


Result UtilSetSpeakerMute(bool mute, bool* previous)
{
	Result rc = audctlInitialize();
	if (R_FAILED(rc))
		return rc;

	bool current = false;
	if (previous)
	{
		rc = audctlIsTargetMute(&current, AudioTarget_Speaker);
		if (R_SUCCEEDED(rc))
			*previous = current;
	}

	if (R_SUCCEEDED(rc))
		rc = audctlSetTargetMute(AudioTarget_Speaker, mute);

	audctlExit();
	LOG("UtilSetSpeakerMute(%d) %x\n", mute, rc);
	return rc;
}

Result UtilSetHandheldSleepPlan(s32 plan, s32* previous)
{
	Result rc = setsysInitialize();
	if (R_FAILED(rc))
		return rc;

	SetSysSleepSettings settings;
	rc = setsysGetSleepSettings(&settings);
	if (R_SUCCEEDED(rc))
	{
		if (previous)
			*previous = settings.handheld_sleep_plan;
		settings.handheld_sleep_plan = plan;
		rc = setsysSetSleepSettings(&settings);
	}

	setsysExit();
	LOG("UtilSetHandheldSleepPlan(%d) %x\n", plan, rc);
	return rc;
}
