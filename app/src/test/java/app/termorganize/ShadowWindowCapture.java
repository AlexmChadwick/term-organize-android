// Copyright (C) 2026 Term Organize contributors.
// SPDX-License-Identifier: AGPL-3.0-only

package app.termorganize;

import android.view.View;
import androidx.compose.ui.test.TestContext;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Compose 1.9 captureToImage waits on the calling thread for a redraw. On Robolectric that wait
 * never finishes, so this shadow skips it and lets PixelCopy read the frame setContent already drew.
 */
@Implements(className = "androidx.compose.ui.test.android.WindowCapture_androidKt", isInAndroidSdk = false)
public class ShadowWindowCapture {
    @Implementation
    public static void forceRedraw(View view, TestContext testContext) {
    }
}
