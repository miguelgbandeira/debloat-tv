package tv.debloat.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;

import java.io.File;
import java.io.IOException;

/** Decoding pictures at the size they're shown (less memory, less work for the TV). */
final class Images {
    private Images() {}

    /**
     * The image in the file, scaled to `width` pixels wide (never larger than it is); null if it can't be read.
     * hardware: kept only in the graphics chip's memory (half the memory, no copy to upload), for pictures that are only
     * shown; not for ones the app paints on (a hardware bitmap can't be drawn into a Canvas of our own).
     */
    static Bitmap decodeToWidth(File file, int width, boolean hardware) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(file);
            return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                if (w > width) decoder.setTargetSize(width, Math.max(1, Math.round(h * (float) width / w)));
                decoder.setAllocator(hardware ? ImageDecoder.ALLOCATOR_DEFAULT : ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (IOException | RuntimeException e) {
            return BitmapFactory.decodeFile(file.getPath());
        }
    }
}
