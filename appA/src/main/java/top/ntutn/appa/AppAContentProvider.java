package top.ntutn.appa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppAContentProvider extends ContentProvider {
    public static final String AUTHORITY = "top.ntutn.appa.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/data");

    // Method names for call() API
    public static final String METHOD_GET_DATA = "get_data";
    public static final String METHOD_GET_MESSAGE = "get_message";
    public static final String METHOD_START_CALCULATION = "start_calculation";

    // URI paths
    public static final String PATH_CALCULATION_RESULTS = "calculation_results";

    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // Store calculation results (in a real app, use a database)
    private Map<String, Integer> calculationResults = new HashMap<>();
    private Map<String, Boolean> calculationStatus = new HashMap<>();

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
        } else if (METHOD_START_CALCULATION.equals(method)) {
            // Simulate a complex calculation with uncertain delay
            int input = extras != null ? extras.getInt("input", 10) : 10;

            // Generate a unique request ID
            String requestId = "req_" + System.currentTimeMillis();

            // Return immediately to acknowledge the request
            Bundle ackResult = new Bundle();
            ackResult.putString("status", "processing");
            ackResult.putString("message", "Calculation started with input: " + input);
            ackResult.putString("request_id", requestId);

            // Start the background calculation with result storage
            performCalculation(input, requestId);

            return ackResult;
        }

        return super.call(method, arg, extras);
    }

    private void performCalculation(int input, String requestId) {
        // Mark calculation as in-progress
        calculationStatus.put(requestId, false);

        executorService.execute(() -> {
            // Simulate uncertain delay (between 2-5 seconds)
            Random random = new Random();
            int delay = 2000 + random.nextInt(3000); // 2-5 seconds

            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Perform the computation
            int result = input * input * 2 + 42; // Example calculation

            // Store the result
            calculationResults.put(requestId, result);
            calculationStatus.put(requestId, true); // Mark as completed

            // Notify all observers that the data has changed
            // This will trigger ContentObservers in appB
            Uri resultUri = Uri.parse("content://" + AUTHORITY + "/" + PATH_CALCULATION_RESULTS + "/" + requestId);
            getContext().getContentResolver().notifyChange(resultUri, null);

            android.util.Log.d("AppAContentProvider",
                "Calculation completed: " + input + " -> " + result +
                " after " + delay + "ms. Request ID: " + requestId +
                " and notification sent.");
        });
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        String path = uri.getPath();

        if (path != null && path.contains(PATH_CALCULATION_RESULTS)) {
            // Handle query for calculation results
            String lastPathSegment = uri.getLastPathSegment();
            if (lastPathSegment != null && calculationResults.containsKey(lastPathSegment)) {
                int result = calculationResults.get(lastPathSegment);
                boolean isComplete = calculationStatus.get(lastPathSegment);

                MatrixCursor cursor = new MatrixCursor(new String[]{"id", "result", "status"});
                cursor.addRow(new Object[]{lastPathSegment, result, isComplete ? "completed" : "processing"});
                return cursor;
            } else if (selection != null && calculationResults.containsKey(selection)) {
                // Fallback to selection-based query
                int result = calculationResults.get(selection);
                boolean isComplete = calculationStatus.get(selection);

                MatrixCursor cursor = new MatrixCursor(new String[]{"id", "result", "status"});
                cursor.addRow(new Object[]{selection, result, isComplete ? "completed" : "processing"});
                return cursor;
            } else {
                // Return all results or empty cursor
                MatrixCursor cursor = new MatrixCursor(new String[]{"id", "result", "status"});
                for (Map.Entry<String, Integer> entry : calculationResults.entrySet()) {
                    String id = entry.getKey();
                    int result = entry.getValue();
                    boolean isComplete = calculationStatus.get(id);
                    cursor.addRow(new Object[]{id, result, isComplete ? "completed" : "processing"});
                }
                return cursor;
            }
        } else {
            // Default data query
            MatrixCursor cursor = new MatrixCursor(new String[]{"id", "value"});
            cursor.addRow(new Object[]{1, "Hello from AppA ContentProvider!"});
            cursor.addRow(new Object[]{2, "This is data from appA!"});
            cursor.addRow(new Object[]{3, "ContentProvider is working!"});
            return cursor;
        }
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        String path = uri.getPath();
        if (path != null && path.contains(PATH_CALCULATION_RESULTS)) {
            return "vnd.android.cursor.dir/vnd.top.ntutn.appa.calculation_results";
        }
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