#include <jni/com_android_systemui_statusbar_phone_BarBackgroundUpdaterNative.h>

#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>

using namespace android;

int screenRotation;

void const * shotBase;

uint32_t shotWidth;
uint32_t shotHeight;
uint32_t shotStride;
PixelFormat shotFormat;

uint32_t sampleColors(int n, uint32_t sources[])
{
    float red = 0;
    float green = 0;
    float blue = 0;

    int i;
    for (i = 0; i < n; i++)
    {
        uint32_t color = sources[i];
        red += ((color & 0x00FF0000) >> 16) / n;
        green += ((color & 0x0000FF00) >> 8) / n;
        blue += (color & 0x000000FF) / n;
    }

    return (255 << 24) | (((char) red) << 16) | (((char) green) << 8) | ((char) blue);
}

uint32_t getPixel(int32_t dx, int32_t dy)
{
    uint32_t x = 0;
    uint32_t y = 0;

    switch (screenRotation)
    {
    case 1: // ROTATION_90
        // turned counter-clockwise;  invert some of the things
        x = (dy >= 0) ? (shotWidth - 1 - dy) : -dy;
        y = (dx >= 0) ? dx : (shotHeight - 1 + dx);
        break;
    case 2: // ROTATION_180
        // turned upside down; invert all the things
        x = (dx >= 0) ? (shotWidth - 1 - dx) : -dx;
        y = (dy >= 0) ? (shotHeight - 1 - dy) : -dy;
        break;
    case 3: // ROTATION_270
        // turned clockwise; invert some of the things
        x = (dy >= 0) ? dy : (shotWidth - 1 + dy);
        y = (dx >= 0) ? (shotHeight - 1 - dx) : -dx;
        break;
    case 0: // ROTATION_0
    default: // Just smile and wave, boys. Smile and wave.
        // natural orientation; don't invert anything
        x = (dx >= 0) ? dx : (shotWidth - 1 + dx);
        y = (dy >= 0) ? dy : (shotHeight - 1 + dy);
        break;
    }

    if (shotFormat == PIXEL_FORMAT_RGBA_8888)
    {
        return * (uint32_t *) (((char *) shotBase) + y * shotStride * 4 + x * 4);
    }
    else if (shotFormat == PIXEL_FORMAT_RGB_565)
    {
        uint16_t color = * (uint16_t *) (((char *) shotBase) + y * shotStride * 2 + x * 2);

        uint32_t red = ((color & 0xF800) >> 11) * 255 / 31;
        uint32_t green = ((color & 0x07E0) >> 5) * 255 / 63;
        uint32_t blue = (color & 0x001F) * 255 / 31;

        return (255 << 24) | (red << 16) | (green << 8) | blue;
    }

    return 0;
}

JNIEXPORT jintArray JNICALL Java_com_android_systemui_statusbar_phone_BarBackgroundUpdaterNative_getColors
        (JNIEnv * je, jclass jc, jint rotation, jint statusBarHeight, jint navigationBarHeight, jint xFromRightSide)
{
    jint response[4] = { 0, 0, 0, 0 };
    screenRotation = rotation;

    sp<IBinder> display = SurfaceComposerClient::getBuiltInDisplay(ISurfaceComposer::eDisplayIdMain);
    ScreenshotClient screenshot;

    if (display == NULL)
    {
        jintArray arr = je->NewIntArray(4);
        je->SetIntArrayRegion(arr, 0, 4, response);
        return arr;
    }

    if (screenshot.update(display) != NO_ERROR)
    {
        jintArray arr = je->NewIntArray(4);
        je->SetIntArrayRegion(arr, 0, 4, response);
        return arr;
    }

    shotBase = screenshot.getPixels();

    if (shotBase == NULL)
    {
        jintArray arr = je->NewIntArray(4);
        je->SetIntArrayRegion(arr, 0, 4, response);
        return arr;
    }

    shotWidth = screenshot.getWidth();
    shotHeight = screenshot.getHeight();
    shotStride = screenshot.getStride();
    shotFormat = screenshot.getFormat();

    uint32_t colorSbOne = getPixel(1, 1);
    uint32_t colorSbTwo = getPixel(1, 5);

    uint32_t colorTopLeft = getPixel(1, 2 + statusBarHeight);
    uint32_t colorTopRight = getPixel(-1 - xFromRightSide, 2 + statusBarHeight);
    uint32_t colorTopCenter = getPixel(-1 - (xFromRightSide / 2), 2 + statusBarHeight);

    if (colorTopLeft == colorTopRight)
    {
        // status bar appears to be completely uniform
        response[0] = colorTopLeft;
    }
    else if (colorTopLeft == colorTopCenter || colorTopRight == colorTopCenter)
    {
        // a side of the status bar appears to be uniform
        response[0] = colorTopCenter;
    }
    else
    {
        // status bar does not appear to be uniform at all
        uint32_t colorsTop[3] = { colorTopLeft, colorTopCenter, colorTopRight };
        response[0] = sampleColors(3, colorsTop);
    }

    response[1] = colorSbOne == colorSbTwo ? 1 : 0;

    uint32_t colorNbOne = getPixel(-1, -1);
    uint32_t colorNbTwo = getPixel(-1, -5);

    uint32_t colorBotLeft = getPixel(1, -2 - navigationBarHeight);
    uint32_t colorBotRight = getPixel(-1 - xFromRightSide, -2 - navigationBarHeight);
    uint32_t colorBotCenter = getPixel(-1 - (xFromRightSide / 2), -2 - navigationBarHeight);

    if (colorBotLeft == colorBotRight)
    {
        // navigation bar appears to be completely uniform
        response[2] = colorBotLeft;
    }
    else if (colorBotLeft == colorBotCenter || colorBotRight == colorBotCenter)
    {
        // a side of the navigation bar appears to be uniform
        response[2] = colorBotCenter;
    }
    else
    {
        // navigation bar does not appear to be uniform at all
        uint32_t colorsBot[3] = { colorBotLeft, colorBotCenter, colorBotRight };
        response[2] = sampleColors(3, colorsBot);
    }

    response[3] = colorNbOne == colorNbTwo ? 1 : 0;

    jintArray arr = je->NewIntArray(4);
    je->SetIntArrayRegion(arr, 0, 4, response);
    return arr;
}
