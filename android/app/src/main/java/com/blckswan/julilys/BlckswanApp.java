package com.blckswan.julilys;

import android.app.Application;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Android apps do not inherit Termux' PATH. On rooted devices this can make
 * ProcessBuilder("su", ...) fail even though root works from Termux.
 *
 * Install a tiny private `su` dispatcher and prepend it to PATH before the
 * activity starts. The dispatcher tries the common Magisk locations directly.
 */
public class BlckswanApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            File shim = new File(getFilesDir(), "su");
            String script = "#!/system/bin/sh\n"
                    + "for s in \\\n"
                    + "  /system/bin/su \\\n"
                    + "  /system/xbin/su \\\n"
                    + "  /sbin/su \\\n"
                    + "  /su/bin/su \\\n"
                    + "  /debug_ramdisk/su \\\n"
                    + "  /debug_ramdisk/.magisk/mirror/system/bin/su \\\n"
                    + "  /data/adb/magisk/su\n"
                    + "do\n"
                    + "  if [ -x \"$s\" ]; then exec \"$s\" \"$@\"; fi\n"
                    + "done\n"
                    + "exit 127\n";

            try (FileOutputStream out = new FileOutputStream(shim, false)) {
                out.write(script.getBytes(StandardCharsets.UTF_8));
            }
            Os.chmod(shim.getAbsolutePath(), 0700);

            String oldPath = System.getenv("PATH");
            if (oldPath == null) oldPath = "";
            String path = getFilesDir().getAbsolutePath()
                    + ":/system/bin:/system/xbin:/sbin:/su/bin:/debug_ramdisk"
                    + (oldPath.isEmpty() ? "" : ":" + oldPath);
            Os.setenv("PATH", path, true);
        } catch (Throwable ignored) {
            // MainActivity will show the useful root error if no su is reachable.
        }
    }
}
