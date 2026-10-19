// Checks if the FinalOutputRecorder (audrec:u) can record the system audio output, for HOME menu sound.
// Records 2 s of whatever plays, then plays a 440 Hz tone with audout and checks it shows up in the recording.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <switch.h>

#define REC_BUFFERS 4
#define REC_BUFFER_SIZE 0x1000 // 1024 frames of s16 stereo, 21.3 ms
#define TONE_SIZE 0x2F000      // 1 s of s16 stereo at 48 kHz, page aligned
#define TEST_SECONDS 6
#define TONE_AT_SECOND 2

static FinalOutputRecorderBuffer recBuffers[REC_BUFFERS];
static u8* recData[REC_BUFFERS];
static AudioOutBuffer toneBuffer;

static void printResult(const char* what, Result rc)
{
    if (R_SUCCEEDED(rc)) printf("%-28s ok\n", what);
    else printf("%-28s FAILED 0x%x (%04d-%04d)\n", what, rc, 2000 + R_MODULE(rc), R_DESCRIPTION(rc));
    consoleUpdate(NULL);
}

static double ms(u64 ticks) { return armTicksToNs(ticks) / 1e6; }

static int peakOf(const u8* data, size_t size)
{
    int peak = 0;
    const s16* samples = (const s16*)data;
    for (size_t i = 0; i < size / 2; i++) {
        int v = samples[i] < 0 ? -samples[i] : samples[i];
        if (v > peak) peak = v;
    }
    return peak;
}

static int indexOf(u64 clientPtr)
{
    for (int i = 0; i < REC_BUFFERS; i++)
        if ((u64)&recBuffers[i] == clientPtr) return i;
    return -1;
}

static void runTest(void)
{
    Result rc = audrecInitialize();
    printResult("audrecInitialize", rc);
    if (R_FAILED(rc)) return;

    u64 aruid = appletGetAppletResourceUserId();
    printf("aruid 0x%lx\n", aruid);

    FinalOutputRecorderParameter param = { .sample_rate = 48000, .channel_count = 2 };
    FinalOutputRecorderParameterInternal info = {0};
    AudrecRecorder recorder;
    rc = audrecOpenFinalOutputRecorder(&recorder, &param, aruid, &info);
    printResult("OpenFinalOutputRecorder", rc);
    if (R_FAILED(rc)) { audrecExit(); return; }
    printf("  rate %u, channels %u, format %u (2 = s16), state %u\n", info.sample_rate, info.channel_count, info.sample_format, info.state);

    Event event;
    rc = audrecRecorderRegisterBufferEvent(&recorder, &event);
    printResult("RegisterBufferEvent", rc);

    for (int i = 0; i < REC_BUFFERS; i++) {
        recData[i] = aligned_alloc(0x1000, REC_BUFFER_SIZE);
        memset(recData[i], 0, REC_BUFFER_SIZE);
        recBuffers[i] = (FinalOutputRecorderBuffer){
            .sample_buffer_ptr = (u64)recData[i],
            .sample_buffer_capacity = REC_BUFFER_SIZE,
            .data_size = REC_BUFFER_SIZE,
        };
        rc = audrecRecorderAppendFinalOutputRecorderBuffer(&recorder, (u64)&recBuffers[i], &recBuffers[i]);
        if (R_FAILED(rc)) break;
    }
    printResult("AppendBuffers", rc);

    rc = audrecRecorderStart(&recorder);
    printResult("RecorderStart", rc);

    // Tone source
    Result toneRc = audoutInitialize();
    if (R_SUCCEEDED(toneRc)) toneRc = audoutStartAudioOut();
    printResult("audout (tone source)", toneRc);
    void* tone = aligned_alloc(0x1000, TONE_SIZE);
    s16* t = tone;
    for (int f = 0; f < TONE_SIZE / 4; f++) {
        s16 v = (s16)((f / 54) % 2 ? 6000 : -6000); // ~440 Hz square wave
        t[f * 2] = v;
        t[f * 2 + 1] = v;
    }
    toneBuffer = (AudioOutBuffer){ .buffer = tone, .buffer_size = TONE_SIZE, .data_size = TONE_SIZE };

    u64 start = armGetSystemTick(), secondStart = start, toneStart = 0;
    int second = 0, buffersThisSecond = 0, peakThisSecond = 0, timeouts = 0;
    u64 bytesThisSecond = 0;
    double toneLatency = -1;

    while (second < TEST_SECONDS) {
        if (R_FAILED(eventWait(&event, 100000000ULL))) timeouts++;
        eventClear(&event);

        u64 released[REC_BUFFERS] = {0};
        u64 count = REC_BUFFERS, releasedNs = 0;
        rc = audrecRecorderGetReleasedFinalOutputRecorderBuffers(&recorder, released, &count, &releasedNs);
        if (R_FAILED(rc)) { printResult("GetReleasedBuffers", rc); break; }

        for (u64 k = 0; k < count; k++) {
            int i = indexOf(released[k]);
            if (i < 0) { printf("  unknown released buffer 0x%lx\n", released[k]); continue; }
            int peak = peakOf(recData[i], REC_BUFFER_SIZE);
            if (peak > peakThisSecond) peakThisSecond = peak;
            if (toneStart && toneLatency < 0 && peak > 3000)
                toneLatency = ms(armGetSystemTick() - toneStart);
            buffersThisSecond++;
            bytesThisSecond += REC_BUFFER_SIZE;
            memset(recData[i], 0, REC_BUFFER_SIZE);
            audrecRecorderAppendFinalOutputRecorderBuffer(&recorder, (u64)&recBuffers[i], &recBuffers[i]);
        }

        if (ms(armGetSystemTick() - secondStart) >= 1000) {
            printf("  s%d: %2d buffers, %6lu B/s (expect 192000), peak %5d, timeouts %d%s\n", second,
                buffersThisSecond, bytesThisSecond, peakThisSecond, timeouts, toneStart ? "  [tone]" : "");
            consoleUpdate(NULL);
            second++;
            secondStart = armGetSystemTick();
            buffersThisSecond = 0; bytesThisSecond = 0; peakThisSecond = 0; timeouts = 0;

            if (second == TONE_AT_SECOND && R_SUCCEEDED(toneRc)) {
                audoutAppendAudioOutBuffer(&toneBuffer);
                toneStart = armGetSystemTick();
            }
        }
    }

    if (toneLatency >= 0)
        printf("Tone heard in the recording after %.1f ms (includes up to ~21 ms of buffer)\n", toneLatency);
    else
        printf("Tone NOT found in the recording\n");

    audrecRecorderStop(&recorder);
    audrecRecorderClose(&recorder);
    eventClose(&event);
    audrecExit();
    if (R_SUCCEEDED(toneRc)) { audoutStopAudioOut(); audoutExit(); }
    for (int i = 0; i < REC_BUFFERS; i++) free(recData[i]);
    free(tone);
}

int main(int argc, char* argv[])
{
    consoleInit(NULL);
    padConfigureInput(1, HidNpadStyleSet_NpadStandard);
    PadState pad;
    padInitializeDefault(&pad);

    printf("audrec FinalOutputRecorder test\n");
    printf("Try once with normal volume and once with the console volume at 0\n\n");
    consoleUpdate(NULL);
    runTest();
    printf("\nA = run again, + = exit\n");
    consoleUpdate(NULL);

    while (appletMainLoop()) {
        padUpdate(&pad);
        u64 down = padGetButtonsDown(&pad);
        if (down & HidNpadButton_Plus) break;
        if (down & HidNpadButton_A) { printf("\n"); runTest(); printf("\nA = run again, + = exit\n"); }
        consoleUpdate(NULL);
    }

    consoleExit(NULL);
    return 0;
}
