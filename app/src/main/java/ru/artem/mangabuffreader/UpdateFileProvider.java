package ru.artem.mangabuffreader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateFileProvider extends ContentProvider {
    private static final String UPDATE_APK_FILE = "MangaBuff-Reader-update.apk";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    static Uri getUpdateUri(Context context) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".updates")
                .appendPath(UPDATE_APK_FILE)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return isValidUri(uri) ? APK_MIME_TYPE : null;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        if (!isValidUri(uri)) {
            return null;
        }

        File file = getUpdateFile();
        String[] requested = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(requested, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : requested) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(UPDATE_APK_FILE);
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.exists() ? file.length() : 0L);
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!isValidUri(uri) || !"r".equals(mode)) {
            throw new FileNotFoundException("Недоступный файл обновления");
        }
        File file = getUpdateFile();
        if (!file.isFile()) {
            throw new FileNotFoundException("Файл обновления не найден");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Только чтение");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Только чтение");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Только чтение");
    }

    private boolean isValidUri(Uri uri) {
        Context context = getContext();
        return context != null
                && "content".equals(uri.getScheme())
                && (context.getPackageName() + ".updates").equals(uri.getAuthority())
                && uri.getPathSegments().size() == 1
                && UPDATE_APK_FILE.equals(uri.getLastPathSegment());
    }

    private File getUpdateFile() {
        Context context = getContext();
        if (context == null) {
            return new File("/");
        }
        return new File(context.getCacheDir(), UPDATE_APK_FILE);
    }
}
