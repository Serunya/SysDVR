#include <string.h>
#include "modes.h"
#include "../USB/Serial.h"
#include "../capture.h"
#include "proto.h"

static bool VideoConnected = false;
static bool AudioConnected = false;
static Mutex UsbMutex;
static u64 LastConnection;

// LiteDock: caps:sc JPEG screenshots for what grc can't capture (HOME menu, games without video capture)
// grc produced no frame for this long -> the JPEG thread takes over
#define JPEG_FALLBACK_DELAY_NS 300000000ULL
#define JPEG_DEFAULT_FPS 30

static u8 JpegFlags = 0;
static bool UseSystemAudio = false;
static u64 JpegIntervalNs = 0;
static Thread JpegThread;
static u8 alignas(0x1000) JpegStackArea[0x2000 + LOGGING_STACK_BOOST];
static atomic_bool JpegThreadRunning = false;

// In the HOME menu the video thread is stuck inside grc until a game resumes, so it can't
// accept a new client. The JPEG thread performs the handshake in its place, this serializes them.
static Mutex ConnectMutex;
static atomic_bool VideoThreadInGrc = false;

// Diagnostics for the LiteDock client, see ERROR_TYPE_JPEG_*
static struct {
	PacketHeader Header;
	ErrorPacket Error;
} __attribute__((packed)) DiagPkt;

static u64 JpegFramesSent = 0;
static atomic_bool SendDockStatus = false;
static u8 ConnectedFeatureFlags = 0;

static bool ClientConnected()
{
	return VideoConnected || AudioConnected;
}

static void DisconnectClient()
{
	VideoConnected = false;
	AudioConnected = false;
	ProtoClientGlobalStateDisconnected();
}

static bool SendData(void* data, size_t length)
{
	bool success;
	mutexLock(&UsbMutex);

	bool isTooLate = armGetSystemTick() - LastConnection > armNsToTicks(1.5E+9);
	if (isTooLate || !ClientConnected())
		success = false;
	else
		success = UsbStreamingSend(data, length);

	if (success)
		LastConnection = armGetSystemTick();

	mutexUnlock(&UsbMutex);
	return success;
}

static bool USB_ConnectClientLocked()
{
	size_t num = 0;

	// Try this in a loop until a client connects
	do
		num = usbSerialWrite(PROTO_HANDSHAKE_HELLO, sizeof(PROTO_HANDSHAKE_HELLO), 1E+9);
	while (num == 0 && IsThreadRunning);

	if (num != sizeof(PROTO_HANDSHAKE_HELLO) || !IsThreadRunning)
		return false;

	u8 buffer[PROTO_HANDSHAKE_SIZE];
	if (usbSerialRead(buffer, sizeof(buffer), 1E+9) != sizeof(buffer))
		return false;

	LOG("USB request received\n");
	ProtoParsedHandshake res = ProtoHandshake(ProtoHandshakeAccept_Any, buffer, sizeof(buffer));

	if (!UsbStreamingSend(&res.Result, sizeof(res.Result)))
	{
		ProtoClientGlobalStateDisconnected();
		return false;
	}

	if (res.Result.Code != Handshake_Ok)
	{
		ProtoClientGlobalStateDisconnected();
		return false;
	}

	u32 fps = res.JpegMaxFps ? res.JpegMaxFps : JPEG_DEFAULT_FPS;
	if (fps > 60)
		fps = 60;
	JpegIntervalNs = 1000000000ULL / fps;
	JpegFlags = res.RequestedVideo ? res.JpegFlags : 0;
	ConnectedFeatureFlags = res.FeatureFlags;
	SendDockStatus = true;
	UseSystemAudio = res.RequestedAudio && res.SystemAudio;
	LOG("JPEG flags %x fps %d\n", JpegFlags, fps);

	if (res.RequestedVideo)
		CaptureVideoConnected();

	if (res.RequestedAudio)
		CaptureAudioConnected();

	LastConnection = armGetSystemTick();
	VideoConnected = res.RequestedVideo;
	AudioConnected = res.RequestedAudio;
	return true;
}

bool USB_ConnectClient()
{
	mutexLock(&ConnectMutex);
	// Another thread may have connected while we were waiting
	bool ok = ClientConnected() || USB_ConnectClientLocked();
	mutexUnlock(&ConnectMutex);
	return ok;
}

static void SendDiag(u32 type, u32 code, u64 ctx1, u64 ctx2, u64 ctx3)
{
	DiagPkt.Header.Magic = STREAM_PACKET_HEADER;
	DiagPkt.Header.DataSize = sizeof(ErrorPacket);
	DiagPkt.Header.Timestamp = armTicksToNs(armGetSystemTick()) / 1000;
	DiagPkt.Header.MetaData = PacketMeta_Type_Video | PacketMeta_Content_Error;
	DiagPkt.Header.ReplaySlot = 0xFF;
	DiagPkt.Error.ErrorType = type;
	DiagPkt.Error.ErrorCode = code;
	DiagPkt.Error.Context1 = ctx1;
	DiagPkt.Error.Context2 = ctx2;
	DiagPkt.Error.Context3 = ctx3;
	SendData(&DiagPkt, sizeof(DiagPkt));
}

static void USB_JpegStreamThread(void*)
{
	u64 lastStatus = 0, lastError = 0;

	while (JpegThreadRunning && IsThreadRunning)
	{
		if (!ClientConnected())
		{
			// Take over the handshake only while the video thread can't do it
			if (VideoThreadInGrc)
			{
				if (!USB_ConnectClient())
					svcSleepThread(5E+8L);
			}
			else
				svcSleepThread(1E+8L);
			continue;
		}

		if (SendDockStatus)
		{
			SendDockStatus = false;
			SendDiag(ERROR_TYPE_DOCK_STATUS, ProtoDockSpeakerResult, ProtoDockSleepResult, ConnectedFeatureFlags, 0);
		}

		const bool jpegOnly = JpegFlags & ExtraFeatureFlags_JpegOnly;
		const bool fallback = JpegFlags & ExtraFeatureFlags_JpegFallback;
		const bool active = VideoConnected && (jpegOnly || (fallback && CaptureVideoIdleNs() > JPEG_FALLBACK_DELAY_NS));

		if (JpegFlags && armTicksToNs(armGetSystemTick() - lastStatus) > 2000000000ULL)
		{
			lastStatus = armGetSystemTick();
			SendDiag(ERROR_TYPE_JPEG_STATUS, 0, CaptureVideoIdleNs() / 1000000,
				JpegFlags | ((u64)VideoThreadInGrc << 8) | ((u64)active << 9), JpegFramesSent);
		}

		if (!active)
		{
			svcSleepThread(5E+7L);
			continue;
		}

		u64 start = armGetSystemTick();

		if (CaptureReadJpeg())
		{
			if (!SendData(JPkt, JPkt->Header.DataSize + sizeof(PacketHeader)))
			{
				if (ClientConnected())
					DisconnectClient();
				svcSleepThread(5E+8L);
				continue;
			}
			JpegFramesSent++;
		}
		else
		{
			if (armTicksToNs(armGetSystemTick() - lastError) > 1000000000ULL)
			{
				lastError = armGetSystemTick();
				u64 size = 0;
				Result rc = CaptureLastJpegError(&size);
				SendDiag(ERROR_TYPE_JPEG_CAP, rc, size, 0, 0);
			}
			svcSleepThread(1E+8L);
			continue;
		}

		u64 elapsed = armTicksToNs(armGetSystemTick() - start);
		if (elapsed < JpegIntervalNs)
			svcSleepThread(JpegIntervalNs - elapsed);
	}
}

static void USB_VideoStreamThread(void*)
{
	while (IsThreadRunning)
	{
		// Video streaming thread acts as master and receives the initial streaming request
		if (!ClientConnected()) {
			if (!USB_ConnectClient()) {
				svcSleepThread(5E+8L);
				continue;
			}
		}
		else if (VideoConnected && (JpegFlags & ExtraFeatureFlags_JpegOnly))
		{
			// The JPEG thread does all the video work
			svcSleepThread(1E+8L);
		}
		else if (VideoConnected)
		{
			// Do not check for errors here, in case grc failed we will be sending the error packet
			VideoThreadInGrc = true;
			CaptureReadVideo();
			VideoThreadInGrc = false;

			if (!IsThreadRunning)
				continue;

			if (!SendData(&VPkt, VPkt.Header.DataSize + sizeof(PacketHeader)))
			{
				DisconnectClient();
				svcSleepThread(5E+8);
			}
		}
		else if (!VideoConnected)
		{
			svcSleepThread(1E+9);
		}
	}
}

static void USB_AudioStreamThread(void*)
{
	u64 lastAudrecError = 0, lastAudioStatus = 0;

	while (IsThreadRunning)
	{
		if (AudioConnected && UseSystemAudio)
		{
			Result rc = 0;
			if (!CaptureSystemAudioStart(&rc))
			{
				// Report it and fall back to grc for this session
				LOG("System audio unavailable: %x\n", rc);
				UseSystemAudio = false;
				SendDiag(ERROR_TYPE_AUDREC, rc, 0, 0, 0);
				continue;
			}

			if (armTicksToNs(armGetSystemTick() - lastAudioStatus) > 2000000000ULL)
			{
				lastAudioStatus = armGetSystemTick();
				u64 homeAruid = 0;
				Result homeRc = 0;
				int appPeak = 0, homePeak = 0;
				CaptureSystemAudioStatus(&homeAruid, &homeRc, &appPeak, &homePeak);
				SendDiag(ERROR_TYPE_AUDIO_STATUS, homeRc, homeAruid, appPeak, homePeak);
			}

			if (!CaptureReadSystemAudio(&rc))
			{
				if (R_FAILED(rc) && armTicksToNs(armGetSystemTick() - lastAudrecError) > 1000000000ULL)
				{
					lastAudrecError = armGetSystemTick();
					SendDiag(ERROR_TYPE_AUDREC, rc, 1, 0, 0);
				}
				continue;
			}

			if (!IsThreadRunning)
				continue;

			if (!SendData(&APkt, APkt.Header.DataSize + sizeof(PacketHeader)))
			{
				DisconnectClient();
				svcSleepThread(5E+8);
			}
		}
		else if (AudioConnected)
		{
			CaptureSystemAudioStop();

			// Do not check for errors here, in case grc failed we will be sending the error packet
			CaptureReadAudio();

			if (!IsThreadRunning)
				continue;

			if (!SendData(&APkt, APkt.Header.DataSize + sizeof(PacketHeader)))
			{
				DisconnectClient();
				svcSleepThread(5E+8);
			}
		}
		else
		{
			CaptureSystemAudioStop();
			svcSleepThread(1E+9);
		}
	}
}

static void USB_Init()
{
	VideoConnected = false;
	AudioConnected = false;
	JpegFlags = 0;
	VideoThreadInGrc = false;
	mutexInit(&UsbMutex);
	mutexInit(&ConnectMutex);

	Result rc = UsbStreamingInitialize();
	if (R_FAILED(rc))
		fatalThrow(rc);

	// Slightly lower priority than the grc threads, it only matters when they are idle
	JpegThreadRunning = true;
	memset(JpegStackArea, 0, sizeof(JpegStackArea));
	LaunchThread(&JpegThread, USB_JpegStreamThread, NULL, JpegStackArea, sizeof(JpegStackArea), 0x2E);
}

static void USB_Exit()
{
	// Must happen before taking UsbMutex, the JPEG thread may be waiting on it
	JpegThreadRunning = false;
	JoinThread(&JpegThread);

	mutexLock(&UsbMutex);
	VideoConnected = false;
	AudioConnected = false;
	UsbStreamingExit();
	mutexUnlock(&UsbMutex);
}

const StreamMode USB_MODE = {
	USB_Init, USB_Exit,
	USB_VideoStreamThread, USB_AudioStreamThread,
	NULL, NULL,
};
