package top.ntutn.appb;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MainActivity extends AppCompatActivity {
    private static final String METHOD_GATHER_FILE_LIST = "gather_file_list";
    private static final String AUTHORITY = "top.ntutn.appa.provider";
    private static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY);
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
            // Prepare input for the calculation
            Bundle args = new Bundle();
            Random random = new Random();
            int input = 5 + random.nextInt(15); // Random input between 5-20
            args.putInt("input", input);

            try {
                // Call the calculation method in the ContentProvider
                Bundle result = getContentResolver().call(
                    PROVIDER_URI, METHOD_GATHER_FILE_LIST,
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

            Context context = MainActivity.this;
            // xml解析在一个单独线程，且会因等待文件传输阻塞
            Thread xmlThread = new Thread(() -> {
                Uri fileListUri = PROVIDER_URI.buildUpon()
                        .appendQueryParameter("base", TransferFileInfo.TAG_FILES_DIR)
                        .appendQueryParameter("path", ".file_list.xml")
                        .build();
                try (InputStream inputStream = getContentResolver().openInputStream(fileListUri)) {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(inputStream, "UTF-8");

                    BlockingQueue<Optional<String>> taskQueue = new ArrayBlockingQueue<>(4);
                    for (int i = 0; i < 4; i++) {
                        // 使用4个线程消费数据
                        Thread thread = new Thread(() -> {
                            while (true) {
                                try {
                                    Optional<String> data = taskQueue.take();
                                    // 收到特殊终止标记
                                    if (data.isEmpty()) {
                                        Log.d("lhx", Thread.currentThread().getName() + " exiting...");
                                        taskQueue.put(data);
                                        break;
                                    }
                                    Log.d("lhx", Thread.currentThread().getName() + " received " + data.get());
                                } catch (InterruptedException ignored) {
                                    break;
                                }
                            }
                        });
                        thread.setName("consumer" + i);
                        thread.start();
                    }

                    int eventType = parser.getEventType();
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        String currentTagName = parser.getName();

                        switch (eventType) {
                            case XmlPullParser.START_TAG:
                                if (currentTagName.equals("file")) {
                                    // 找到一个文件标签
                                    String baseDirTag = parser.getAttributeValue(null, "base");
                                    String path = parser.getAttributeValue(null, "path");


                                    try {
                                        taskQueue.put(Optional.of(path));
                                    } catch (InterruptedException ignored) {
                                    }
                                }
                                break;
                        }

                        eventType = parser.next();
                    }
                    try {
                        taskQueue.put(Optional.empty());
                    } catch (InterruptedException ignored) {
                    }
                } catch (IOException | XmlPullParserException e) {
                    Log.e("lhx", "read file list failed", e);
                }
            });
            xmlThread.setName("producer");
            xmlThread.start();
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