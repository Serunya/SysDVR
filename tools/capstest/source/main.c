// Measures how fast caps:sc can capture the screen, to decide if it can stream the HOME menu.
#include <stdio.h>
#include <stdlib.h>
#include <switch.h>

#define ITERATIONS 20
#define RAW_ITERATIONS 5
#define RAW_SIZE 0x384000 // 1280 * 720 * 4
#define RAW_CHUNK 0x100000
#define TIMEOUT_NS 100000000LL

static u8* jpegBuf;
static u8* rawBuf;

static double toMs(u64 ticks) { return armTicksToNs(ticks) / 1e6; }

static void printError(const char* what, Result rc)
{
    printf("%s failed: 0x%x (%04d-%04d)\n", what, rc, 2000 + R_MODULE(rc), R_DESCRIPTION(rc));
}

static void testJpeg(ViLayerStack stack, const char* name)
{
    u64 total = 0, min = ~0ULL, max = 0, sizeSum = 0;
    int ok = 0;
    Result last = 0;

    for (int i = 0; i < ITERATIONS; i++) {
        u64 size = 0;
        u64 start = armGetSystemTick();
        Result rc = capsscCaptureJpegScreenShot(&size, jpegBuf, CAPSSC_JPEG_BUFFER_SIZE, stack, TIMEOUT_NS);
        u64 dt = armGetSystemTick() - start;
        if (R_FAILED(rc)) {
            last = rc;
            continue;
        }
        ok++;
        total += dt;
        sizeSum += size;
        if (dt < min) min = dt;
        if (dt > max) max = dt;
    }

    if (ok == 0) {
        printf("JPEG %-11s ", name);
        printError("", last);
    } else {
        double avg = toMs(total) / ok;
        printf("JPEG %-11s %2d/%d ok  avg %6.1f ms  min %6.1f  max %6.1f  = %5.1f fps  %4lu KB\n",
            name, ok, ITERATIONS, avg, toMs(min), toMs(max), 1000.0 / avg, sizeSum / ok / 1024);
    }
    consoleUpdate(NULL);
}

static void testRaw(void)
{
    u64 total = 0;
    int ok = 0;

    for (int i = 0; i < RAW_ITERATIONS; i++) {
        u64 size = 0, width = 0, height = 0;
        u64 start = armGetSystemTick();
        Result rc = capsscOpenRawScreenShotReadStream(&size, &width, &height, ViLayerStack_Default, TIMEOUT_NS);
        if (R_FAILED(rc)) {
            printError("RAW open", rc);
            consoleUpdate(NULL);
            return;
        }

        u64 offset = 0;
        while (offset < size && offset < RAW_SIZE) {
            u64 read = 0;
            rc = capsscReadRawScreenShotReadStream(&read, rawBuf + offset, RAW_CHUNK, offset);
            if (R_FAILED(rc) || read == 0) break;
            offset += read;
        }
        capsscCloseRawScreenShotReadStream();

        if (R_FAILED(rc)) {
            printError("RAW read", rc);
            consoleUpdate(NULL);
            return;
        }
        total += armGetSystemTick() - start;
        ok++;
    }

    double avg = toMs(total) / ok;
    printf("RAW  default     %2d/%d ok  avg %6.1f ms = %5.1f fps (capture only, 3.5 MB/frame)\n",
        ok, RAW_ITERATIONS, avg, 1000.0 / avg);
    consoleUpdate(NULL);
}

static void runAll(void)
{
    printf("\nRunning, %d captures per test...\n", ITERATIONS);
    consoleUpdate(NULL);
    testJpeg(ViLayerStack_Default, "default");
    testJpeg(ViLayerStack_Screenshot, "screenshot");
    testJpeg(ViLayerStack_LastFrame, "lastframe");
    testRaw();
    printf("Done. A = run again, + = exit\n");
    consoleUpdate(NULL);
}

int main(int argc, char* argv[])
{
    consoleInit(NULL);
    padConfigureInput(1, HidNpadStyleSet_NpadStandard);
    PadState pad;
    padInitializeDefault(&pad);

    printf("caps:sc capture benchmark\n");
    printf("Run it once from the album (applet mode) and once by holding R\n");
    printf("while launching a game (application mode).\n");

    jpegBuf = aligned_alloc(0x1000, CAPSSC_JPEG_BUFFER_SIZE);
    rawBuf = aligned_alloc(0x1000, RAW_SIZE);

    Result rc = capsscInitialize();
    if (R_FAILED(rc)) {
        printError("capsscInitialize", rc);
    } else {
        runAll();
    }
    consoleUpdate(NULL);

    while (appletMainLoop()) {
        padUpdate(&pad);
        u64 down = padGetButtonsDown(&pad);
        if (down & HidNpadButton_Plus) break;
        if ((down & HidNpadButton_A) && R_SUCCEEDED(rc)) runAll();
        consoleUpdate(NULL);
    }

    if (R_SUCCEEDED(rc)) capsscExit();
    free(jpegBuf);
    free(rawBuf);
    consoleExit(NULL);
    return 0;
}
