package top.ntutn.appa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class AppAContentProvider extends ContentProvider {
    public static final String AUTHORITY = "top.ntutn.appa.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/data");

    // Method names for call() API
    public static final String METHOD_GET_DATA = "get_data";
    public static final String METHOD_GET_MESSAGE = "get_message";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (METHOD_GET_DATA.equals(method)) {
            Bundle result = new Bundle();
            result.putString("message", "Hello from AppA ContentProvider via call() method!");
            result.putString("data", "This is additional data from appA!");
            result.putString("status", "success");
            return result;
        } else if (METHOD_GET_MESSAGE.equals(method)) {
            Bundle result = new Bundle();
            result.putString("message", "ContentProvider call() method is working!");
            return result;
        }
        return super.call(method, arg, extras);
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        // Create a simple cursor with some sample data
        MatrixCursor cursor = new MatrixCursor(new String[]{"id", "value"});
        cursor.addRow(new Object[]{1, "Hello from AppA ContentProvider!"});
        cursor.addRow(new Object[]{2, "This is data from appA!"});
        cursor.addRow(new Object[]{3, "ContentProvider is working!"});
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "vnd.android.cursor.dir/vnd.top.ntutn.appa.data";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}