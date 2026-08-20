package net.gsantner.markor.activity;

import android.content.Context;
import android.content.Intent;
import android.content.ContentResolver;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Image picker/storage helper for the editor background setting. */
final class EditorBackgroundImagePicker {
    static final int REQUEST_CODE = 48217;

    private EditorBackgroundImagePicker() {
    }

    @NonNull
    static Intent createIntent() {
        final Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        return intent;
    }

    /**
     * Copies the selected media URI into app-private storage and returns a filesystem path.
     * This keeps the background-image renderer compatible with normal File paths even when the
     * gallery returns a content:// URI.
     */
    static String copyToAppStorage(@NonNull Context context, @NonNull Uri source) throws IOException {
        final ContentResolver resolver = context.getContentResolver();
        String extension = extensionFromMime(resolver.getType(source));
        if (TextUtils.isEmpty(extension)) {
            extension = ".jpg";
        }

        final File directory = new File(context.getFilesDir(), "editor_background");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create editor background directory");
        }

        final File target = new File(directory, "background_" + UUID.randomUUID() + extension);
        try (InputStream input = resolver.openInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                throw new IOException("Unable to open selected image");
            }
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (IOException | RuntimeException e) {
            // Do not leave a partial image behind.
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        }
        return target.getAbsolutePath();
    }

    private static String extensionFromMime(String mime) {
        if (mime == null) {
            return null;
        }
        switch (mime.toLowerCase()) {
            case "image/png": return ".png";
            case "image/webp": return ".webp";
            case "image/gif": return ".gif";
            case "image/bmp": return ".bmp";
            case "image/heic": return ".heic";
            case "image/heif": return ".heif";
            case "image/jpeg":
            case "image/jpg": return ".jpg";
            default: return null;
        }
    }
}
