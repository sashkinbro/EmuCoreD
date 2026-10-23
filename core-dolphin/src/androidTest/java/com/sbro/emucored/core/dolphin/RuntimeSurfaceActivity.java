// SPDX-License-Identifier: GPL-3.0-or-later
package com.sbro.emucored.core.dolphin;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import java.util.concurrent.CountDownLatch;

/** Real Android window, including the driver's selected SDR/HDR buffer format. */
public final class RuntimeSurfaceActivity extends Activity {
    final CountDownLatch ready = new CountDownLatch(1);
    SurfaceView view;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        view = new SurfaceView(this);
        view.getHolder().setFixedSize(640, 480);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder holder) {}
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { ready.countDown(); }
            public void surfaceDestroyed(SurfaceHolder holder) {}
        });
        setContentView(view);
    }
}
