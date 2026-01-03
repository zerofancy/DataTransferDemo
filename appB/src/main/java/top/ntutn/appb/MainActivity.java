package top.ntutn.appb;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

public class MainActivity extends AppCompatActivity {
    private static final String TAG_FILE = "file";
    private static final String TAG_ROOT = "list";
    private static final String ATTR_BASE_DIR_NAME = "base";
    private static final String ATTR_FILE_PATH = "path";
    private static final String ATTR_ALGORITHM = "algorithm";
    private static final String ATTR_CHECKSUM = "checksum";
    private static final String ATTR_RETRY_TIMES = "retry_times";
    private static final String ATTR_IMPORTANT = "important";
    private static final String METHOD_GATHER_FILE_LIST = "gather_file_list";
    private static final String AUTHORITY = "top.ntutn.appa.provider";
    private static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY);
    private static final Uri CONTENT_FILE_LIST = Uri.parse("content://" + AUTHORITY + "/list");

    private Button button;
    private TextView resultTextView;
    private ScrollView scrollView;
    private String currentRequestId;
    private CalculationResultObserver resultObserver;
    private long startTime;

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
        scrollView = findViewById(R.id.scrollView);

        button.setOnClickListener(v -> {
            // Delete all files in the app's files directory
            File filesDir = getFilesDir();
            deleteAllFiles(filesDir);

            // Start timing
            startTime = System.currentTimeMillis();

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
                        "\nWaiting for result...\nTransfer started at: " + new java.util.Date());
                    // Scroll to the end of the text view
                    resultTextView.post(() -> {
                        scrollView.fullScroll(android.view.View.FOCUS_DOWN);
                    });

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
        private volatile int totalFiles = 0;
        private volatile int processedFiles = 0;

        public CalculationResultObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            resultTextView.append("\n列表计算完毕");
            // Scroll to the end of the text view
            resultTextView.post(() -> {
                scrollView.fullScroll(android.view.View.FOCUS_DOWN);
            });
            cleanup();

            Context context = MainActivity.this;
            // xml解析在一个单独线程，且会因等待文件传输阻塞
            Thread xmlThread = new Thread(() -> {
                Uri fileListUri = PROVIDER_URI.buildUpon()
                        .appendQueryParameter("base", TransferFileInfo.TAG_FILES_DIR)
                        .appendQueryParameter("path", ".file_list.xml")
                        .build();
                // First, count total files to transfer by parsing the XML once
                int totalFileCount = 0;
                try (InputStream countStream = getContentResolver().openInputStream(fileListUri)) {
                    XmlPullParser countParser = Xml.newPullParser();
                    countParser.setInput(countStream, "UTF-8");
                    int eventType = countParser.getEventType();
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG && countParser.getName().equals("file")) {
                            totalFileCount++;
                        }
                        eventType = countParser.next();
                    }
                } catch (XmlPullParserException | IOException e) {
                    Log.e("lhx", "Failed to count files", e);
                    totalFileCount = 0; // fallback
                }

                CountDownLatch latch = new CountDownLatch(totalFileCount);

                try (InputStream inputStream = getContentResolver().openInputStream(fileListUri)) {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(inputStream, "UTF-8");

                    BlockingQueue<Optional<TransferFileInfo>> taskQueue = new ArrayBlockingQueue<>(4);
                    for (int i = 0; i < 4; i++) {
                        // 使用4个线程消费数据
                        Thread thread = new Thread(() -> {
                            while (true) {
                                try {
                                    Optional<TransferFileInfo> data = taskQueue.take();
                                    // 收到终止标记
                                    if (data.isEmpty()) {
                                        Log.d("lhx", Thread.currentThread().getName() + " exiting...");
                                        taskQueue.put(data);
                                        break;
                                    }
                                    Log.d("lhx", Thread.currentThread().getName() + " received " + data);

                                    Uri fileUri = PROVIDER_URI.buildUpon()
                                            .appendQueryParameter("base", data.get().getBaseDirTag())
                                            .appendQueryParameter("path", data.get().getRelativePath())
                                            .build();
                                    try (InputStream ins = context.getContentResolver().openInputStream(fileUri)) {
                                        File targetDir = TransferFileInfo.getDirViaTag(context, data.get().getBaseDirTag());
                                        String relativePath = data.get().getRelativePath();
                                        if (ins == null || targetDir == null || relativePath == null) {
                                            throw new IOException("Illegal params");
                                        }
                                        File targetFile = new File(targetDir, relativePath);
                                        File parentDir = targetFile.getParentFile();
                                        // fixme 多线程创建文件夹可能导致失败
                                        if (parentDir == null || (!parentDir.exists() && !parentDir.mkdirs())) {
                                            throw new IOException("Create dir error " + parentDir);
                                        }
                                        targetFile.createNewFile();

                                        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                                            transferTo(ins, fos);
                                        }

                                    } catch (IOException e) {
                                        Log.e("lhx", data + " transfer error", e);
                                        // todo 如果重试，可以再次塞到队列里
                                        continue;
                                    } finally {
                                        // Count down latch and update progress
                                        latch.countDown();
                                        processedFiles++;
                                        int currentProgress = processedFiles;

                                        // Update UI with progress
                                        runOnUiThread(() -> {
                                            resultTextView.append("\nTransferred: " + currentProgress + " files");
                                            // Scroll to the end of the text view
                                            resultTextView.post(() -> {
                                                scrollView.fullScroll(android.view.View.FOCUS_DOWN);
                                            });
                                        });
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
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
                                    String baseDirTag = parser.getAttributeValue(null, ATTR_BASE_DIR_NAME);
                                    String path = parser.getAttributeValue(null, ATTR_FILE_PATH);
                                    String algorithm = parser.getAttributeValue(null, ATTR_ALGORITHM);
                                    String checksum = parser.getAttributeValue(null, ATTR_CHECKSUM);
                                    String retryTimesString = parser.getAttributeValue(null, ATTR_RETRY_TIMES);
                                    int retryTimes = 0;
                                    try {
                                        retryTimes = Integer.parseInt(retryTimesString);
                                    } catch (NumberFormatException ignored) {
                                    }
                                    String importantString = parser.getAttributeValue(null, ATTR_IMPORTANT);
                                    boolean important = Boolean.parseBoolean(importantString);

                                    TransferFileInfo info = new TransferFileInfo(baseDirTag, path, algorithm, checksum, retryTimes, important);

                                    try {
                                        taskQueue.put(Optional.of(info));
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                                break;
                        }

                        eventType = parser.next();
                    }

                    try {
                        taskQueue.put(Optional.empty());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    // Wait for all files to be transferred
                    latch.await();

                    // All files transferred, calculate and display elapsed time
                    long endTime = System.currentTimeMillis();
                    long elapsedTime = endTime - startTime;
                    double seconds = elapsedTime / 1000.0;
                    int finalTotalFileCount = totalFileCount;
                    runOnUiThread(() -> {
                        resultTextView.append("\nFile transfer completed!");
                        resultTextView.append("\nTotal files: " + finalTotalFileCount);
                        resultTextView.append("\nTotal time: " + seconds + " seconds");
                        // Scroll to the end of the text view after adding completion message
                        resultTextView.post(() -> {
                            scrollView.fullScroll(android.view.View.FOCUS_DOWN);
                        });
                        finalCleanup(); // Use final cleanup to re-enable the button
                    });

                } catch (IOException | XmlPullParserException | InterruptedException e) {
                    Log.e("lhx", "read file list failed", e);
                }
            });
            xmlThread.setName("producer");
            xmlThread.start();
        }

        private long transferTo(InputStream in, OutputStream out) throws IOException {
            Objects.requireNonNull(out, "out");
            long transferred = 0;
            int DEFAULT_BUFFER_SIZE = 8192;
            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer, 0, DEFAULT_BUFFER_SIZE)) >= 0) {
                out.write(buffer, 0, read);
                transferred += read;
            }
            return transferred;
        }
    }

    private void cleanup() {
        // Unregister the observer
        if (resultObserver != null) {
            getContentResolver().unregisterContentObserver(resultObserver);
            resultObserver = null;
        }

        // Note: Don't re-enable the button here since file transfer might still be ongoing
        // The button gets re-enabled after file transfer completes in the UI thread
    }

    private void finalCleanup() {
        // Unregister the observer
        if (resultObserver != null) {
            getContentResolver().unregisterContentObserver(resultObserver);
            resultObserver = null;
        }

        // Re-enable the button after transfer completes
        button.setEnabled(true);
        button.setText("Access AppA ContentProvider");
    }

    private void deleteAllFiles(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteAllFiles(file); // Recursive call for subdirectories
                } else {
                    file.delete(); // Delete the file
                }
            }
            // Optionally clear the directory itself if needed
            // Note: The directory itself is not deleted, just its contents
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up observer when activity is destroyed
        cleanup();
    }
}