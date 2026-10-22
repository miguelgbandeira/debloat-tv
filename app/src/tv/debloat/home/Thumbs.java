package tv.debloat.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Images for the home screen's rows and backdrop: downloaded once to the cache folder, decoded at the size they are
 * shown, and kept in memory while they fit (the least recently used go first).
 */
final class Thumbs {
    interface Done { void on(Bitmap b); }

    private static Thumbs instance;

    static synchronized Thumbs get(Context c) {
        if (instance == null) instance = new Thumbs(c.getApplicationContext());
        return instance;
    }

    private final File dir;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory;

    private Thumbs(Context c) {
        dir = new File(c.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 6, 48L * 1024 * 1024);
        memory = new LruCache<String, Bitmap>(max) {
            @Override protected int sizeOf(String key, Bitmap b) { return b.getAllocationByteCount(); }
        };
    }

    /** Shows the image in the view (once loaded); a view reused for another image meanwhile is left alone. */
    void into(ImageView view, String url, int width) {
        view.setTag(url);
        Bitmap ready = memory.get(key(url, width));
        if (ready != null) {
            view.setImageBitmap(ready);
            return;
        }
        view.setImageDrawable(null);
        load(url, width, b -> {
            if (url.equals(view.getTag())) view.setImageBitmap(b);
        });
    }

    /** Loads the image decoded to about `width` pixels wide and calls back on the main thread (not on failure). */
    void load(String url, int width, Done done) {
        String key = key(url, width);
        Bitmap ready = memory.get(key);
        if (ready != null) {
            done.on(ready);
            return;
        }
        io.execute(() -> {
            try {
                File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
                if (!f.exists()) download(url, f);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                while (bounds.outWidth / (opts.inSampleSize * 2) >= width) opts.inSampleSize *= 2;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
                if (b == null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    return;
                }
                if (b.getWidth() > width * 1.3) {
                    Bitmap scaled = Bitmap.createScaledBitmap(b, width, Math.round(b.getHeight() * (float) width / b.getWidth()), true);
                    if (scaled != b) b.recycle();
                    b = scaled;
                }
                memory.put(key, b);
                Bitmap result = b;
                main.post(() -> done.on(result));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Logs.w("image " + url + ": " + e.getMessage());
            }
        });
    }

    private static String key(String url, int width) {
        return width + "|" + url;
    }

    private void download(String url, File f) throws IOException {
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune();
    }

    /** Keeps the newest 150 images on disk (a few rows' worth, twice over). */
    private void prune() {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".img"));
        if (files == null || files.length <= 150) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 150; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
