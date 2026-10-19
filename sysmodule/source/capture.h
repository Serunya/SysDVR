#pragma once
#include <switch.h>

#define STREAM_PACKET_HEADER 0xCCCCCCCC

/*
	This is higher than what suggested on switchbrw to fix https://github.com/exelix11/SysDVR/issues/91,
	See the comment in ReadVideoStream()
*/
#define VbufSz 0x54000

/*
	Audio is 16bit pcm at 48000hz stereo. In official software it's read in 0x1000 chunks
	that's 1024 samples per chunk (2 bytes per sample and stereo so divided by 4)
	(1 / 48000) * 1024 is 0,02133333 seconds per chunk.
	Smaller buffer sizes don't seem to work, only tested 0x400 and grc fails with 2212-0006
*/
#define AbufSz 0x1000

/*
	Audio batching adds some delay to the audio streaming in exchange for less pressure on
	the USB and network protocols. A batching of 1 halves the number of audio transfers while
	adding about a frame of delay.
	This is acceptable as grc:d already doesn't provide real time audio.
	To disable support set the following to 0
*/
#define MaxABatching 5

#define DefaultABatching 3

enum PacketMeta 
{
	PacketMeta_Type_Mask = BIT(0) | BIT(1),
	PacketMeta_Type_Video = BIT(0),
	PacketMeta_Type_Audio = BIT(1),

	PacketMeta_Content_Mask = BIT(2) | BIT(3) | BIT(4) | BIT(5),
	PacketMeta_Content_Data = BIT(2),
	PacketMeta_Content_Replay = BIT(3),	// Only if PacketMeta_Type_Video
	PacketMeta_Content_MultiNal = BIT(4), // Only if PacketMeta_Type_Video
	PacketMeta_Content_Error = BIT(5),

	// LiteDock extension: the payload is a JPEG screenshot from caps:sc instead of H264.
	// Only sent to clients that request it with ExtraFeatureFlags_Jpeg*
	PacketMeta_Content_Jpeg = BIT(6)

	// Bit 7 is reserved for future use
};

typedef struct {
	u32 Magic;
	u32 DataSize;
	u64 Timestamp; //timestamps are in usecs
	u8 MetaData;
	// Used by packet Replaying to indicate which slot identifies this packet
	// When MetaData & Data the client should cache this packet with this ID
	// When MetaData & Replay the client should replay the cached packet with this ID
	// 0xFF indicates no hash, for example when streaming with audio or with hashes disabled
	u8 ReplaySlot; 
} __attribute__((packed)) PacketHeader;

_Static_assert(sizeof(PacketHeader) == 18);

typedef struct {
	PacketHeader Header;
	u8 Data[VbufSz];
} VideoPacket;

_Static_assert(sizeof(VideoPacket) == sizeof(PacketHeader) + VbufSz);

typedef struct {
	PacketHeader Header;
	u8 Data[AbufSz * (1 + MaxABatching)];
} AudioPacket;

_Static_assert(sizeof(AudioPacket) == sizeof(PacketHeader) + AbufSz * (1 + MaxABatching));

/*
	caps:sc JPEG screenshots of the whole screen (HOME menu, overlays, games without video capture).
	Frames are 120-140 KB at 720p, but caps:sc wants the official 0x80000 buffer and a page aligned
	output address (it fails with 2206-0820 otherwise), see JPkt in capture.c.
*/
#define JbufSz CAPSSC_JPEG_BUFFER_SIZE

typedef struct {
	PacketHeader Header;
	u8 Data[JbufSz];
} JpegPacket;

_Static_assert(sizeof(JpegPacket) == sizeof(PacketHeader) + JbufSz);

// Error values
#define ERROR_TYPE_VIDEO_CAP 1
#define ERROR_TYPE_AUDIO_CAP 2
#define ERROR_TYPE_AUDIO_CAP_BATCH 3

#define ERROR_TYPE_VIDEO_INIT 4
#define ERROR_TYPE_AUDIO_INIT 5

// LiteDock diagnostics, sent as error packets so upstream clients just log them
#define ERROR_TYPE_JPEG_CAP 0x10   // ErrorCode = caps:sc result, Context1 = jpeg size
#define ERROR_TYPE_AUDREC 0x12      // ErrorCode = audrec result, system audio fell back to grc
#define ERROR_TYPE_AUDIO_STATUS 0x13 // ErrorCode = HOME recorder open result, Context1 = HOME aruid, Context2 = app peak, Context3 = HOME peak
#define ERROR_TYPE_DOCK_STATUS 0x14 // ErrorCode = speaker mute result, Context1 = sleep plan result, Context2 = feature flags
#define ERROR_TYPE_JPEG_STATUS 0x11 // Context1 = grc idle ms, Context2 = jpeg flags | in grc << 8 | active << 9, Context3 = jpeg frames sent

typedef struct {
	u32 ErrorType;
	u32 ErrorCode;
	u64 Context1;
	u64 Context2;
	u64 Context3;
} __attribute__((packed)) ErrorPacket;

extern VideoPacket VPkt;
extern AudioPacket APkt;
// Not page aligned itself: its Data field is, the header sits right before the page boundary
extern JpegPacket* const JPkt;

Result CaptureInitialize();
void CaptureFinalize();

// Captures video with grc:d, if no game is running this blocks and there's no way to terminate the call
bool CaptureReadVideo();

// Captures audio with grc:d, if no game is running this blocks and there's no way to terminate the call
bool CaptureReadAudio();

// Captures a JPEG screenshot of the whole screen with caps:sc into JPkt, never blocks for long
bool CaptureReadJpeg();

// Result and size of the last failed caps:sc capture
Result CaptureLastJpegError(u64* size);

// LiteDock: system audio from the final output recorder (audrec:u). Unlike grc it includes the
// HOME menu and system sounds, records before the console volume and has ~20 ms of latency.
bool CaptureSystemAudioStart(Result* rc);
void CaptureSystemAudioStop();
// Fills APkt with the next 1024 frames chunk, false on timeout (~100 ms) or error
bool CaptureReadSystemAudio(Result* rc);
// Diagnostics, resets the peak meters
void CaptureSystemAudioStatus(u64* homeAruid, Result* homeResult, int* applicationPeak, int* homePeak);

// Nanoseconds since grc last produced a video frame (or since the client connected)
u64 CaptureVideoIdleNs();

// When a client first connects clear old data structures and prepare hashing modes
void CaptureVideoConnected();
void CaptureAudioConnected();

// Configurable options
void CaptureConfigResetDefault();
int CaptureSetAudioBatching(int batch);
void CaptureSetPPSSPSInject(bool value);
void CaptureSetNalHashing(bool enabled, bool onlyKeyframes);