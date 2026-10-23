// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.dolphin;

import android.content.res.AssetManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual ARM64 CPU/video/state/lifecycle tests using original legal PPC programs.
 * Run only on a user-authorized device: :core-dolphin:connectedDebugAndroidTest.
 */
public final class DolphinRuntimeTest extends InstrumentationTestCase {
    public void testGameCubeVulkan() throws Exception { exercise(false, "vulkan"); }
    public void testWiiVulkan() throws Exception { exercise(true, "vulkan"); }
    public void testGameCubeOpenGL() throws Exception { exercise(false, "opengl"); }
    public void testWiiOpenGL() throws Exception { exercise(true, "opengl"); }

    private void exercise(boolean wii, String backend) throws Exception {
        File base = new File(getInstrumentation().getTargetContext().getFilesDir(), "runtime-" + wii + "-" + backend + "-" + System.nanoTime());
        assertTrue(base.mkdirs() || base.isDirectory());
        File logger = new File(base, "Config/Logger.ini");
        assertTrue(logger.getParentFile().mkdirs());
        try (FileOutputStream out = new FileOutputStream(logger)) {
            out.write("[Options]\nWriteToFile=True\nWriteToConsole=False\nVerbosity=3\n[Logs]\nBOOT=True\nCORE=True\nVideo=True\nHost GPU=True\nCOMMON=True\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        File sys = new File(base.getParentFile(), "runtime-sys");
        copyAsset("Sys", sys);
        File dol = new File(base, "video.dol");
        try (FileOutputStream out = new FileOutputStream(dol)) { out.write(homebrew(wii)); }
        Intent intent = new Intent(getInstrumentation().getTargetContext(), RuntimeSurfaceActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        RuntimeSurfaceActivity activity = (RuntimeSurfaceActivity)getInstrumentation().startActivitySync(intent);
        assertTrue(activity.ready.await(10, TimeUnit.SECONDS));
        try {
            DolphinNative.surface(activity.view.getHolder().getSurface(), 640, 480);
            assertEquals(0, DolphinNative.start(dol.getAbsolutePath(), base.getAbsolutePath(), sys.getAbsolutePath(), wii, true,
                new String[]{"graphics_backend"}, new String[]{backend}));
            assertEquals(2, DolphinNative.status());
            assertTrue(DolphinNative.pause(false));
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (DolphinNative.frames() < 1 && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue("PPC program must produce real video frames", DolphinNative.frames() >= 1);
            Bitmap pixels = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
            CountDownLatch copied = new CountDownLatch(1);
            AtomicInteger copyResult = new AtomicInteger(-1);
            PixelCopy.request(activity.view, pixels, result -> { copyResult.set(result); copied.countDown(); }, new Handler(Looper.getMainLooper()));
            assertTrue(copied.await(5, TimeUnit.SECONDS));
            assertEquals(PixelCopy.SUCCESS, copyResult.get());
            int red = pixels.getPixel(100, 240), green = pixels.getPixel(320, 240), blue = pixels.getPixel(540, 240);
            assertTrue("Left YUYV bar must be red", ((red >> 16) & 255) > ((red >> 8) & 255) + 70);
            assertTrue("Middle YUYV bar must be green", ((green >> 8) & 255) > ((green >> 16) & 255) + 70);
            assertTrue("Right YUYV bar must be blue", (blue & 255) > ((blue >> 8) & 255) + 70);
            pixels.recycle();
            assertTrue(DolphinNative.pause(true));
            assertEquals(2, DolphinNative.status());
            assertFalse(DolphinNative.speed(Float.NaN));
            assertFalse(DolphinNative.option("unsupported", "true"));
            assertTrue(DolphinNative.option("resolution", "1"));
            assertTrue(DolphinNative.speed(1.5f));
            DolphinNative.control(0, wii ? 18 : 0, 1);
            DolphinNative.control(0, wii ? 18 : 0, 0);
            File state = new File(base, "completed.sav");
            assertTrue("State save must acknowledge completed file I/O", DolphinNative.state(state.getAbsolutePath(), true));
            assertTrue(state.isFile() && state.length() > 24);
            assertTrue(DolphinNative.state(state.getAbsolutePath(), false));
            File invalid = new File(base, "invalid.sav");
            try (FileOutputStream out = new FileOutputStream(invalid)) { out.write(new byte[128]); }
            assertFalse("Invalid state must report actual load failure", DolphinNative.state(invalid.getAbsolutePath(), false));
            DolphinNative.surface(null, 0, 0);
            assertTrue(DolphinNative.pause(false));
            assertEquals(2, DolphinNative.status());
            DolphinNative.surface(activity.view.getHolder().getSurface(), 640, 480);
            assertTrue(DolphinNative.pause(false));
            assertEquals(1, DolphinNative.status());
        } finally {
            DolphinNative.requestStop();
            DolphinNative.stop();
            assertEquals(0, DolphinNative.status());
            getInstrumentation().runOnMainSync(activity::finish);
        }
    }
    private void copyAsset(String path, File target) throws Exception {
        AssetManager assets = getInstrumentation().getTargetContext().getAssets();
        String[] children = assets.list(path);
        if (children != null && children.length > 0) {
            assertTrue(target.mkdirs() || target.isDirectory());
            for (String child : children) copyAsset(path + "/" + child, new File(target, child));
        } else {
            if (target.isFile()) return;
            try (InputStream in = assets.open(path); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[16384]; int count;
                while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            }
        }
    }
    // Original CC0 program, identical to bridge/tests/generate_homebrew.py.
    private static byte[] homebrew(boolean wii) {
        ArrayList<Integer> code = new ArrayList<>();
        code.add(0x3C60CC00); code.add(0x60632000);
        int[][] values = {{0, (240 << 4) | 6}, {2, 5}, {12, 5}, {14, 22}, {16, 4}, {18, 23}, {72, 0x1414}};
        for (int[] value : values) { code.add(0x38800000 | value[1]); code.add(0xB0830000 | value[0]); }
        code.add(0x3C800010); code.add(0x9083001C); code.add(0x90830024); code.add(0x48000000);
        if (wii) code.add(0x7C13FBA6);
        while (code.size() % 8 != 0) code.add(0x60000000);
        int textSize = code.size() * 4, pixels = 320 * 240 * 2;
        ByteBuffer output = ByteBuffer.allocate(256 + textSize + pixels).order(ByteOrder.BIG_ENDIAN);
        output.putInt(0, 256); output.putInt(0x48, 0x80004000); output.putInt(0x90, textSize);
        output.putInt(0x1C, 256 + textSize); output.putInt(0x64, 0x80100000); output.putInt(0xAC, pixels);
        output.putInt(0xE0, 0x80004000); output.position(256);
        for (int word : code) output.putInt(word);
        int[] colors = {0x5A5A5AF0, 0x91369122, 0x29F0296E};
        for (int y = 0; y < 240; y++) for (int x = 0; x < 160; x++) output.putInt(colors[Math.min(2, x * 3 / 160)]);
        return output.array();
    }
}
