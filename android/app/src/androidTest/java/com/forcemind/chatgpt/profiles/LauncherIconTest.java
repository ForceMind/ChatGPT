package com.forcemind.chatgpt.profiles;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual installed-package resources, independent of WebView and account data. */
@RunWith(AndroidJUnit4.class)
public class LauncherIconTest {
    private Bitmap render(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 256, 256);
        drawable.draw(new Canvas(bitmap));
        return bitmap;
    }

    private void save(Context context, String name, String mime, byte[] bytes) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ProfilesIconEvidence");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri destination = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull(destination);
        try (OutputStream output = context.getContentResolver().openOutputStream(destination)) {
            assertNotNull(output);
            output.write(bytes);
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        context.getContentResolver().update(destination, values, null, null);
    }

    private void savePng(Context context, String name, Bitmap image) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        save(context, name, "image/png", bytes.toByteArray());
    }

    @Test public void systemLoadsAdaptiveBlackIconAndUnclippedMonochromeLogomark() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Only fresh CI debug packages are accepted", context.getPackageName().endsWith(".debug"));
        ApplicationInfo application = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
        Drawable loaded = context.getPackageManager().getApplicationIcon(application);
        assertTrue("PackageManager must load the adaptive icon", loaded instanceof AdaptiveIconDrawable);
        AdaptiveIconDrawable icon = (AdaptiveIconDrawable) loaded;
        Bitmap background = render(icon.getBackground());
        assertEquals(Color.rgb(23, 23, 23), background.getPixel(0, 0));
        assertEquals(Color.rgb(23, 23, 23), background.getPixel(255, 255));
        background.recycle();

        Bitmap normal = render(icon);
        int white = 0, dark = 0;
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            int color = normal.getPixel(x, y);
            if (Color.alpha(color) < 250) continue;
            if (Color.red(color) > 240 && Color.green(color) > 240 && Color.blue(color) > 240) white++;
            if (Color.red(color) < 35 && Color.green(color) < 35 && Color.blue(color) < 35) dark++;
        }
        assertTrue("Rendered logomark must be visible", white > 2000);
        assertTrue("Rendered black background must remain visible", dark > 20000);
        savePng(context, "android-adaptive-icon.png", normal);
        normal.recycle();

        int roundId = context.getResources().getIdentifier("ic_launcher_round", "mipmap", context.getPackageName());
        assertTrue(roundId != 0);
        Drawable round = context.getDrawable(roundId);
        assertTrue(round instanceof AdaptiveIconDrawable);
        Bitmap roundBitmap = render(round);
        savePng(context, "android-round-icon.png", roundBitmap);
        roundBitmap.recycle();

        boolean monochrome = false;
        if (Build.VERSION.SDK_INT >= 33) {
            Drawable layer = icon.getMonochrome();
            assertNotNull("Android 13+ must receive a themeable logomark layer", layer);
            Bitmap mono = render(layer);
            int minX = 256, minY = 256, maxX = -1, maxY = -1;
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
                if (Color.alpha(mono.getPixel(x, y)) > 0) {
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
            }
            assertTrue("Logomark must fit within Android's centered 66/108 safe zone",
                    minX >= 49 && minY >= 49 && maxX <= 207 && maxY <= 207 && maxX > minX && maxY > minY);
            savePng(context, "android-monochrome-layer.png", mono);
            mono.recycle();
            monochrome = true;
        }
        PackageInfo pkg = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        JSONObject report = new JSONObject().put("package", pkg.packageName).put("version", pkg.versionName)
                .put("api", Build.VERSION.SDK_INT).put("drawableClass", loaded.getClass().getName())
                .put("background", "#171717").put("whitePixels", white).put("darkPixels", dark)
                .put("roundAdaptive", true).put("monochromeVerified", monochrome)
                .put("scope", "Actual installed debug-package resources rendered by Android; no website or personal account acceptance");
        save(context, "icon-verification.json", "application/json", report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
