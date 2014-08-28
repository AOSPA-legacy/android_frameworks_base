#include <jni/com_android_systemui_statusbar_phone_BarBackgroundUpdaterNative.h>

#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>

using namespace android;

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

uint32_t getPixel(void const * base, PixelFormat format, uint32_t stride, uint32_t x, uint32_t y)
{
    if (format == PIXEL_FORMAT_RGBA_8888)
    {
        return * (uint32_t *) (((char *) base) + y * stride * 4 + x * 4);
    }
    else if (format == PIXEL_FORMAT_RGB_565)
    {
        uint16_t color = * (uint16_t *) (((char *) base) + y * stride * 2 + x * 2);

        uint32_t red = ((color & 0xF800) >> 11) * 255 / 31;
        uint32_t green = ((color & 0x07E0) >> 5) * 255 / 63;
        uint32_t blue = (color & 0x001F) * 255 / 31;

        return (255 << 24) | (red << 16) | (green << 8) | blue;
    }

    return 0;
}

JNIEXPORT jintArray JNICALL Java_com_android_systemui_statusbar_phone_BarBackgroundUpdaterNative_getColors
        (JNIEnv * je, jclass jc, jint statusBarHeight, jint navigationBarHeight, jint xFromRightSide)
{
    jint response[4];

    sp<IBinder> display = SurfaceComposerClient::getBuiltInDisplay(ISurfaceComposer::eDisplayIdMain);
    ScreenshotClient screenshot;

    if (display == NULL)
    {
        return NULL;
    }

    if (screenshot.update(display) != NO_ERROR)
    {
        return NULL;
    }

    void const * base = screenshot.getPixels();

    if (base == NULL)
    {
        return NULL;
    }

    uint32_t width = screenshot.getWidth();
    uint32_t height = screenshot.getHeight();
    uint32_t stride = screenshot.getStride();
    PixelFormat format = screenshot.getFormat();

    uint32_t colorSbOne = getPixel(base, format, stride, 1, 1);
    uint32_t colorSbTwo = getPixel(base, format, stride, 1, 5);

    uint32_t colorTopLeft = getPixel(base, format, stride, 1, statusBarHeight + 2);
    uint32_t colorTopRight = getPixel(base, format, stride, width - 1 - xFromRightSide, statusBarHeight + 2);
    uint32_t colorTopCenter = getPixel(base, format, stride, width - 1 - (xFromRightSide / 2), statusBarHeight + 2);

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

    uint32_t colorNbOne = getPixel(base, format, stride, width - 2, height - 2);
    uint32_t colorNbTwo = getPixel(base, format, stride, width - 2, height - 6);

    uint32_t colorBotLeft = getPixel(base, format, stride, 1, height - navigationBarHeight - 3);
    uint32_t colorBotRight = getPixel(base, format, stride, width - 1 - xFromRightSide, height - navigationBarHeight - 3);
    uint32_t colorBotCenter = getPixel(base, format, stride, width - 1 - (xFromRightSide / 2), height - navigationBarHeight - 3);

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
