package top.ntutn.appb;

import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Random;

public class MainActivity extends AppCompatActivity {
    private static final String METHOD_GATHER_FILE_LIST = "gather_file_list";
    private static final String AUTHORITY = "top.ntutn.appa.provider";
    private static final Uri CONTENT_FILE_LIST = Uri.parse("content://" + AUTHORITY + "/list");

    private Button button;
    private TextView resultTextView;
    private String currentRequestId;
    private CalculationResultObserver resultObserver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        button = findViewById(R.id.button);
        resultTextView = findViewById(R.id.resultTextView);

        button.setOnClickListener(v -> {
            // Use call() method to request calculation from appA
            Uri uri = Uri.parse("content://" + AUTHORITY);

            // Prepare input for the calculation
            Bundle args = new Bundle();
            Random random = new Random();
            int input = 5 + random.nextInt(15); // Random input between 5-20
            args.putInt("input", input);

            try {
                // Call the calculation method in the ContentProvider
                Bundle result = getContentResolver().call(
                    uri,
                        METHOD_GATHER_FILE_LIST,
                    null,
                    args
                );

                if (result != null) {
                    String status = result.getString("status");
                    String message = result.getString("message");
                    currentRequestId = result.getString("request_id");

                    resultTextView.setText(message + "\nStatus: " + status +
                        "\nRequest ID: " + currentRequestId +
                        "\nWaiting for result...");

                    // Register ContentObserver to get notified when result is ready
                    registerResultObserver();
                } else {
                    resultTextView.setText("No response from ContentProvider");
                }
            } catch (Exception e) {
                resultTextView.setText("Error calling ContentProvider: " + e.getMessage());
                Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void registerResultObserver() {
        // Unregister any existing observer
        if (resultObserver != null) {
            getContentResolver().unregisterContentObserver(resultObserver);
        }

        // Create and register the ContentObserver
        resultObserver = new CalculationResultObserver(new Handler(Looper.getMainLooper()));
        getContentResolver().registerContentObserver(CONTENT_FILE_LIST, false, resultObserver);

        // Disable the button while waiting for result
        button.setEnabled(false);
        button.setText("Waiting for result...");
    }

    // ContentObserver to listen for changes to the calculation result
    private class CalculationResultObserver extends ContentObserver {
        public CalculationResultObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            resultTextView.append("\n列表计算完毕");
            cleanup();
        }
    }

    private void cleanup() {
        // Unregister the observer
        if (resultObserver != null) {
            getContentResolver().unregisterContentObserver(resultObserver);
            resultObserver = null;
        }

        // Re-enable the button
        button.setEnabled(true);
        button.setText("Access AppA ContentProvider");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up observer when activity is destroyed
        cleanup();
    }
}