#include <string.h>

#include "core.h"
#include "grcd.h"
#include "capture.h"
#include "modes/defines.h"
#include <stdatomic.h>

VideoPacket alignas(0x1000) VPkt;
AudioPacket alignas(0x1000) APkt;
static u8 alignas(0x1000) JpegArea[0x1000 + JbufSz];
JpegPacket* const JPkt = (JpegPacket*)(JpegArea + 0x1000 - sizeof(PacketHeader));

static atomic_ullong lastVideoFrameTick = 0;

#define AREC_BUFFERS 4
#define AREC_BUFFER_SIZE 0x1000 // 1024 frames of s16 stereo, 21.3 ms
#define QLAUNCH_PROGRAM_ID 0x0100000000001000ULL

// The final output recorder only records the mix of one applet resource user:
// aruid 0 is the application, the HOME menu (qlaunch) is recorded with its own aruid (its process id).
// Both are recorded and mixed.
typedef struct {
	u8 alignas(0x1000) Data[AREC_BUFFERS][AREC_BUFFER_SIZE];
	FinalOutputRecorderBuffer Buffers[AREC_BUFFERS];
	AudrecRecorder Recorder;
	Event BufferEvent;
	u64 Released[AREC_BUFFERS];
	u64 ReleasedCount, ReleasedIndex;
	bool Open;
	u64 Aruid;
	Result OpenResult;
	int Peak;
} ArecSource;

enum { ArecApplication, ArecHome, ArecCount };
static ArecSource alignas(0x1000) ArecSources[ArecCount];
static bool audrecInitialized = false;
static bool capsInitialized = false;
static bool capsFailed = false;
static Result lastJpegRc = 0;
static u64 lastJpegSize = 0;

Result CaptureLastJpegError(u64* size)
{
	*size = lastJpegSize;
	return lastJpegRc;
}

static Service grcdVideo;
static Service grcdAudio;

// To support disabling and enabling sysdvr without rebooting the console we now don't call grcdServiceBegin on start.
// This is because this function globally enables a flag in grc, when this is called with the flag already set it will cause the console to crash
// The solution is to attempt to record directory and if we get the error code 0x3E8D4/2212-0500 we know grcdServiceBegin was not called before and it's safe to call
// Note that this same error code is also returned when grcdTransfer is called with an invalid stream argument, but this does not apply to us.
#define GRCD_NOT_INITIALIZED 0x3E8D4
static bool grcdBeginCalled = false;
static Mutex grcdBeginMutex;

static const uint8_t SPS[] = { 0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x0C, 0x20, 0xAC, 0x2B, 0x40, 0x28, 0x02, 0xDD, 0x35, 0x01, 0x0D, 0x01, 0xE0, 0x80 };
static const uint8_t PPS[] = { 0x00, 0x00, 0x00, 0x01, 0x68, 0xEE, 0x3C, 0xB0 };

static bool forceSPSPPS = false;

static atomic_bool capturing = false;

static bool EnsureGrcdInit(Result* rc)
{
	if (grcdBeginCalled) 
		return false;

	mutexLock(&grcdBeginMutex);

	if (grcdBeginCalled)
		return false;

	grcdBeginCalled = true;

	*rc = grcdServiceBegin(&grcdVideo);
	LOG("grcdServiceBegin: %x\n", *rc);

	mutexUnlock(&grcdBeginMutex);

	return true;
}

// User configuration
static int AudioBatching = DefaultABatching;
static bool InjectSpsPps = true;
static bool HashNals = false;
static bool HashOnlyKeyframes = true;

static void PacketMakeReplay(VideoPacket* packet)
{
	packet->Header.MetaData = (packet->Header.MetaData & ~PacketMeta_Content_Mask) | PacketMeta_Content_Replay;
	packet->Header.DataSize = 0;
}

static void PacketMakeData(PacketHeader* packet)
{
	packet->MetaData = (packet->MetaData & ~PacketMeta_Content_Mask) | PacketMeta_Content_Data;
}

static void PacketMakeError(u32 type, PacketHeader* packet, void* packedBody, u32 code, u64 context1, u64 context2)
{
	ErrorPacket error = {
		.ErrorType = type,
		.ErrorCode = code,
		.Context1 = context1,
		.Context2 = context2,
	};

	packet->MetaData = (packet->MetaData & ~PacketMeta_Content_Mask) | PacketMeta_Content_Error;
	packet->DataSize = sizeof(error);
	
	memcpy(packedBody, &error, sizeof(error));
}

void CaptureAudioConnected()
{
	APkt.Header.Magic = STREAM_PACKET_HEADER;
	APkt.Header.MetaData = PacketMeta_Type_Audio | PacketMeta_Content_Data;
	APkt.Header.ReplaySlot = 0xFF;
}

bool CaptureReadAudio()
{
	u32 dataSize = 0; u64 timestamp = 0;

	Result rc = grcdServiceTransfer(
		&grcdAudio, GrcStream_Audio,
		APkt.Data, AbufSz,
		NULL,
		&dataSize,
		&timestamp);

	if (rc == GRCD_NOT_INITIALIZED && EnsureGrcdInit(&rc))
	{
		if (R_FAILED(rc))
		{
			PacketMakeError(ERROR_TYPE_AUDIO_INIT, &APkt.Header, APkt.Data, rc, 0, 0);
			return false;
		}
		else return CaptureReadAudio();
	}

	APkt.Header.DataSize = dataSize;
	APkt.Header.Timestamp = timestamp;

	if (!R_SUCCEEDED(rc))
	{
		LOG("Audio capture failed: %x size: %x\n", rc, dataSize);
		PacketMakeError(ERROR_TYPE_AUDIO_CAP, &APkt.Header, APkt.Data, rc, dataSize, 0);
		return false;
	}

	for (int i = 0; i < AudioBatching; i++)
	{
		u32 tmpSize = 0;

		rc = grcdServiceTransfer(
			&grcdAudio, GrcStream_Audio,
			APkt.Data + AbufSz + (AbufSz * i), AbufSz,
			NULL,
			&tmpSize,
			NULL);

		if (!R_SUCCEEDED(rc))
		{
			LOG("Audio capture failed: %x size: %x (loop %d)\n", rc, tmpSize, i);
			PacketMakeError(ERROR_TYPE_AUDIO_CAP_BATCH, &APkt.Header, APkt.Data, rc, tmpSize, i + 1);
			return false;
		}

		APkt.Header.DataSize += tmpSize;
	}

	return true;
}

#define CRC32X(crc, value) __asm__("crc32x %w[c], %w[c], %x[v]":[c]"+r"(crc):[v]"r"(value))
#define CRC32W(crc, value) __asm__("crc32w %w[c], %w[c], %w[v]":[c]"+r"(crc):[v]"r"(value))
#define CRC32H(crc, value) __asm__("crc32h %w[c], %w[c], %w[v]":[c]"+r"(crc):[v]"r"(value))
#define CRC32B(crc, value) __asm__("crc32b %w[c], %w[c], %w[v]":[c]"+r"(crc):[v]"r"(value))

#define DEFINE_UNALIGNED_ACCESSOR(type) static type get_unaligned_##type(const u8 *p) { type v; memcpy(&v, p, sizeof(v)); return v; }

DEFINE_UNALIGNED_ACCESSOR(u64);
DEFINE_UNALIGNED_ACCESSOR(u32);
DEFINE_UNALIGNED_ACCESSOR(u16);

static u32 crc32_arm64_hw(const u8* p, unsigned int len)
{
	u32 crc = 0xffffffff;

	s64 length = len;
	while (length >= sizeof(u64)) {
		CRC32X(crc, get_unaligned_u64(p));
		p += sizeof(u64);
		length -= sizeof(u64);
	}
	
	if (length >= sizeof(u32)) {
		CRC32W(crc, get_unaligned_u32(p));
		p += sizeof(u32);
		length -= sizeof(u32);
	}

	if (length >= sizeof(u16)) {
		CRC32H(crc, get_unaligned_u16(p));
		p += sizeof(u16);
		length -= sizeof(u16);
	}

	if (length)
		CRC32B(crc, *p);
	
	return ~crc;
}

#define HASH_BITS 6
static u32 NalHashes[1 << HASH_BITS];

bool HashVideoPacket(const u8* data, size_t len, u8* outSlot)
{
	u32 hash = crc32_arm64_hw(data, len);

	// We use the lower HASH_BITS bits of the hash as the index
	int index = hash & ((1 << HASH_BITS) - 1);
	*outSlot = (u8)index;

	// If the hash is already in the table we do not need to send the packet again
	if (NalHashes[index] == hash)
		return true;

	// Otherwise we update the hash table and send the packet
	NalHashes[index] = hash;

	return false;
}

static Result ArecOpen(ArecSource* src, u64 aruid)
{
	FinalOutputRecorderParameter param = { .sample_rate = 48000, .channel_count = 2 };
	FinalOutputRecorderParameterInternal info = {0};
	src->Aruid = aruid;

	Result rc = audrecOpenFinalOutputRecorder(&src->Recorder, &param, aruid, &info);
	LOG("audrecOpenFinalOutputRecorder(%lx): %x rate %d ch %d fmt %d\n", aruid, rc, info.sample_rate, info.channel_count, info.sample_format);
	if (R_FAILED(rc))
		return rc;

	rc = audrecRecorderRegisterBufferEvent(&src->Recorder, &src->BufferEvent);
	if (R_FAILED(rc))
		goto fail_close;

	for (int i = 0; i < AREC_BUFFERS; i++)
	{
		src->Buffers[i] = (FinalOutputRecorderBuffer){
			.sample_buffer_ptr = (u64)src->Data[i],
			.sample_buffer_capacity = AREC_BUFFER_SIZE,
			.data_size = AREC_BUFFER_SIZE,
		};
		rc = audrecRecorderAppendFinalOutputRecorderBuffer(&src->Recorder, (u64)&src->Buffers[i], &src->Buffers[i]);
		if (R_FAILED(rc))
			goto fail_event;
	}

	rc = audrecRecorderStart(&src->Recorder);
	if (R_FAILED(rc))
		goto fail_event;

	src->ReleasedCount = src->ReleasedIndex = 0;
	src->Peak = 0;
	src->Open = true;
	return 0;

fail_event:
	eventClose(&src->BufferEvent);
fail_close:
	audrecRecorderClose(&src->Recorder);
	return rc;
}

static void ArecClose(ArecSource* src)
{
	if (!src->Open)
		return;

	audrecRecorderStop(&src->Recorder);
	audrecRecorderClose(&src->Recorder);
	eventClose(&src->BufferEvent);
	src->Open = false;
}

// Index of the next recorded buffer, -1 if none arrived within the timeout
static int ArecPop(ArecSource* src, u64 timeout_ns, Result* rc)
{
	if (src->ReleasedIndex >= src->ReleasedCount)
	{
		src->ReleasedIndex = src->ReleasedCount = 0;

		if (R_FAILED(eventWait(&src->BufferEvent, timeout_ns)))
			return -1;
		eventClear(&src->BufferEvent);

		u64 count = AREC_BUFFERS, releasedNs = 0;
		*rc = audrecRecorderGetReleasedFinalOutputRecorderBuffers(&src->Recorder, src->Released, &count, &releasedNs);
		if (R_FAILED(*rc))
			return -1;

		src->ReleasedCount = count;
		if (count == 0)
			return -1;
	}

	FinalOutputRecorderBuffer* buffer = (FinalOutputRecorderBuffer*)src->Released[src->ReleasedIndex++];
	int i = buffer - src->Buffers;
	return (i >= 0 && i < AREC_BUFFERS) ? i : -1;
}

static void ArecRequeue(ArecSource* src, int i)
{
	audrecRecorderAppendFinalOutputRecorderBuffer(&src->Recorder, (u64)&src->Buffers[i], &src->Buffers[i]);
}

// Peak of a recorded buffer, also tracked for the diagnostics
static int ArecPeak(ArecSource* src, int i)
{
	const s16* in = (const s16*)src->Data[i];
	int peak = 0;
	for (int k = 0; k < AREC_BUFFER_SIZE / 2; k++)
	{
		int a = in[k] < 0 ? -in[k] : in[k];
		if (a > peak) peak = a;
	}
	if (peak > src->Peak)
		src->Peak = peak;
	return peak;
}

bool CaptureSystemAudioStart(Result* out_rc)
{
	if (ArecSources[ArecApplication].Open)
		return true;

	Result rc = 0;
	if (!audrecInitialized)
	{
		rc = audrecInitialize();
		LOG("audrecInitialize: %x\n", rc);
		if (R_FAILED(rc))
		{
			*out_rc = rc;
			return false;
		}
		audrecInitialized = true;
	}

	rc = ArecOpen(&ArecSources[ArecApplication], 0);
	ArecSources[ArecApplication].OpenResult = rc;
	if (R_FAILED(rc))
	{
		*out_rc = rc;
		return false;
	}

	// The HOME menu is optional, the application audio works without it
	u64 pid = 0;
	rc = pmdmntInitialize();
	if (R_SUCCEEDED(rc))
	{
		rc = pmdmntGetProcessId(&pid, QLAUNCH_PROGRAM_ID);
		pmdmntExit();
	}
	if (R_SUCCEEDED(rc))
		rc = ArecOpen(&ArecSources[ArecHome], pid);
	ArecSources[ArecHome].Aruid = pid;
	ArecSources[ArecHome].OpenResult = rc;
	return true;
}

void CaptureSystemAudioStop()
{
	for (int i = 0; i < ArecCount; i++)
		ArecClose(&ArecSources[i]);
}

void CaptureSystemAudioStatus(u64* homeAruid, Result* homeResult, int* applicationPeak, int* homePeak)
{
	*homeAruid = ArecSources[ArecHome].Aruid;
	*homeResult = ArecSources[ArecHome].OpenResult;
	*applicationPeak = ArecSources[ArecApplication].Peak;
	*homePeak = ArecSources[ArecHome].Peak;
	ArecSources[ArecApplication].Peak = 0;
	ArecSources[ArecHome].Peak = 0;
}

bool CaptureReadSystemAudio(Result* out_rc)
{
	*out_rc = 0;
	ArecSource* app = &ArecSources[ArecApplication];
	ArecSource* home = &ArecSources[ArecHome];
	s16* out = (s16*)APkt.Data;

	// The application recorder is the clock. If it doesn't deliver (no game ever started?) the HOME one takes over.
	ArecSource* chosen = NULL;
	int chosenIndex = -1, chosenPeak = -1;

	int i = ArecPop(app, home->Open ? 30000000ULL : 100000000ULL, out_rc);
	if (i >= 0)
	{
		chosen = app;
		chosenIndex = i;
		chosenPeak = ArecPeak(app, i);
	}
	else if (R_FAILED(*out_rc))
		return false;

	if (home->Open)
	{
		Result rc = 0;
		// Use the newest buffer, drop older ones so the HOME recorder can't lag behind
		int latest = -1, next;
		while ((next = ArecPop(home, chosen ? 0 : 70000000ULL, &rc)) >= 0)
		{
			if (latest >= 0)
				ArecRequeue(home, latest);
			latest = next;
			if (!chosen)
				break;
		}

		// Both recorders turned out to deliver the same final mix most of the time, summing them
		// doubles the volume and clips. Keep the louder one: it covers one of them being silent.
		if (latest >= 0)
		{
			int peak = ArecPeak(home, latest);
			if (peak > chosenPeak)
			{
				if (chosen)
					ArecRequeue(chosen, chosenIndex);
				chosen = home;
				chosenIndex = latest;
				chosenPeak = peak;
			}
			else
				ArecRequeue(home, latest);
		}
	}

	if (!chosen)
		return false;

	memcpy(out, chosen->Data[chosenIndex], AREC_BUFFER_SIZE);
	ArecRequeue(chosen, chosenIndex);

	APkt.Header.Magic = STREAM_PACKET_HEADER;
	APkt.Header.MetaData = PacketMeta_Type_Audio | PacketMeta_Content_Data;
	APkt.Header.ReplaySlot = 0xFF;
	APkt.Header.DataSize = AREC_BUFFER_SIZE;
	APkt.Header.Timestamp = armTicksToNs(armGetSystemTick()) / 1000;
	return true;
}

u64 CaptureVideoIdleNs()
{
	return armTicksToNs(armGetSystemTick() - lastVideoFrameTick);
}

bool CaptureReadJpeg()
{
	if (capsFailed)
		return false;

	if (!capsInitialized)
	{
		Result rc = capsscInitialize();
		LOG("capsscInitialize: %x\n", rc);
		if (R_FAILED(rc))
		{
			capsFailed = true;
			lastJpegRc = rc;
			lastJpegSize = 0;
			return false;
		}
		capsInitialized = true;
	}

	u64 size = 0;
	Result rc = capsscCaptureJpegScreenShot(&size, JPkt->Data, JbufSz, ViLayerStack_Default, 100000000LL);
	if (R_FAILED(rc) || size == 0 || size > JbufSz)
	{
		LOG("capsscCaptureJpegScreenShot failed: %x size: %lx\n", rc, size);
		lastJpegRc = rc;
		lastJpegSize = size;
		return false;
	}

	JPkt->Header.Magic = STREAM_PACKET_HEADER;
	JPkt->Header.DataSize = (u32)size;
	JPkt->Header.Timestamp = armTicksToNs(armGetSystemTick()) / 1000;
	JPkt->Header.MetaData = PacketMeta_Type_Video | PacketMeta_Content_Data | PacketMeta_Content_Jpeg;
	JPkt->Header.ReplaySlot = 0xFF;
	return true;
}

void CaptureVideoConnected()
{
	lastVideoFrameTick = armGetSystemTick();
	memset(NalHashes, 0, sizeof(NalHashes));
	VPkt.Header.Magic = STREAM_PACKET_HEADER;
	VPkt.Header.MetaData = PacketMeta_Type_Video;
	VPkt.Header.ReplaySlot = 0xFF;
	forceSPSPPS = true;
}

bool CaptureReadVideo()
{
	u32 dataSize = 0; u64 timestamp = 0;

	Result res = grcdServiceTransfer(
		&grcdVideo, GrcStream_Video,
		VPkt.Data, VbufSz,
		NULL,
		&dataSize,
		&timestamp);

	if (res == GRCD_NOT_INITIALIZED && EnsureGrcdInit(&res))
	{
		if (R_FAILED(res))
		{
			PacketMakeError(ERROR_TYPE_VIDEO_INIT, &VPkt.Header, VPkt.Data, res, 0, 0);
			return false;
		}
		else return CaptureReadVideo();
	}

	VPkt.Header.DataSize = dataSize;
	VPkt.Header.Timestamp = timestamp;

	if (R_SUCCEEDED(res))
		lastVideoFrameTick = armGetSystemTick();
	VPkt.Header.ReplaySlot = 0xFF;

	bool result = R_SUCCEEDED(res) && VPkt.Header.DataSize > 4;

	// Sometimes the buffer is too small for IDR frames causing this https://github.com/exelix11/SysDVR/issues/91 
	// These big NALs are not common and even if they're missed they only cause graphical glitches
	// Error code should be 0xcd4/2212-0006
	if (!result) 
	{
		LOG("Video capture failed: %x size: %x\n", res, dataSize);
		// In these cases, set the meta error flag and the payload is just the size 
		PacketMakeError(ERROR_TYPE_VIDEO_CAP, &VPkt.Header, VPkt.Data, res, dataSize, 0);
	}
	else
	{
		// GRC only produces a single nal per packet so this is always correct
		const bool isIDRFrame = (VPkt.Data[4] & 0x1F) == 5;

		// Static images are particularly bad for sysdvr cause they cause the video encoder
		// to emit really big keyframes, all to produce a static image. So here's a trick:
		// We hash these big blocks and keep the last 10 or so, if they keep repeating we know
		// this is a static image so we can just not send it with no consequences to free
		// up bandwidth for audio and reduce delay once the image changes
		const bool UseHash = HashNals && (isIDRFrame || !HashOnlyKeyframes);

		if (UseHash && HashVideoPacket(VPkt.Data, VPkt.Header.DataSize, &VPkt.Header.ReplaySlot)) {
			LOG("Sending packet hash instead\n");
			PacketMakeReplay(&VPkt);
			return true;
		}

		PacketMakeData(&VPkt.Header);

		/*
			GRC only emits SPS and PPS once when a game is started,
			this is not good as without those it's not possible to play the stream If there's space add SPS and PPS to IDR frames every once in a while
		*/
		if (InjectSpsPps) {
			static int IDRCount = 0;

			// if this is an IDR frame and we haven't added SPS/PPS in the last 5 or forceSPSPPS is set
			bool EmitMeta = forceSPSPPS || (isIDRFrame && ++IDRCount >= 5);

			// Only if there's enough space
			if (EmitMeta && (VbufSz - VPkt.Header.DataSize) >= (sizeof(PPS) + sizeof(SPS)))
			{
				IDRCount = 0;
				forceSPSPPS = false;
				memmove(VPkt.Data + sizeof(PPS) + sizeof(SPS), VPkt.Data, VPkt.Header.DataSize);
				memcpy(VPkt.Data, SPS, sizeof(SPS));
				memcpy(VPkt.Data + sizeof(SPS), PPS, sizeof(PPS));
				VPkt.Header.DataSize += sizeof(SPS) + sizeof(PPS);
				VPkt.Header.MetaData |= PacketMeta_Content_MultiNal;
			}
		}
	}

	return result;
}

// Configurable options
int CaptureSetAudioBatching(int batch)
{
	if (batch < 0)
		batch = 0;

	if (batch > MaxABatching)
		batch = MaxABatching;

	LOG("CaptureSetAudioBatching(%d)\n", batch);
	AudioBatching = batch;
	return AudioBatching;
}

void CaptureSetPPSSPSInject(bool value)
{
	LOG("CaptureSetPPSSPSInject(%d)\n", value);
	InjectSpsPps = value;
}

void CaptureSetNalHashing(bool enabled, bool onlyKeyframes)
{
	LOG("CaptureSetNalHashing(%d, %d)\n", enabled, onlyKeyframes);
	HashNals = enabled;
	HashOnlyKeyframes = onlyKeyframes;
}

void CaptureConfigResetDefault()
{
	CaptureSetNalHashing(true, true);
	CaptureSetPPSSPSInject(true);
	CaptureSetAudioBatching(DefaultABatching);
}

Result CaptureInitialize()
{
	capturing = true;

	R_RET_ON_FAIL(grcdServiceOpen(&grcdVideo));
	R_RET_ON_FAIL(grcdServiceOpen(&grcdAudio));
	return 0;
}

void CaptureFinalize()
{
	CaptureSystemAudioStop();
	if (audrecInitialized)
	{
		audrecExit();
		audrecInitialized = false;
	}

	if (capsInitialized)
	{
		capsscExit();
		capsInitialized = false;
	}

	grcdServiceClose(&grcdVideo);
	grcdServiceClose(&grcdAudio);
}
