package top.ntutn.appa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppAContentProvider extends ContentProvider {
    public static final String AUTHORITY = "top.ntutn.appa.provider";
    private static final Uri CONTENT_FILE_LIST = Uri.parse("content://" + AUTHORITY + "/list");

    public static final String METHOD_GATHER_FILE_LIST = "gather_file_list";

    // URI paths
    public static final String PATH_CALCULATION_RESULTS = "calculation_results";
    private static final String FILE_LIST_FILE_NAME = ".file_list.xml";
    private static final String TAG_FILE = "file";
    private static final String TAG_ROOT = "list";
    private static final String TAG_EXTERNAL_FILES = "external_files";
    private static final String TAG_OBB = "obb";
    private static final String TAG_FILES = "files";
    private static final String TAG_NO_BACKUP = "no_backup";
    private static final String TAG_DATA = "data";
    private static final String ATTR_BASE_DIR_NAME = "base";
    private static final String ATTR_FILE_PATH = "path";
    private static final String ATTR_PERMISSION = "permission";
    private static final String ATTR_ALGORITHM = "algorithm";
    private static final String ATTR_CHECKSUM = "checksum";
    private static final String ATTR_RETRY_TIMES = "retry_times";
    private static final String ATTR_IMPORTANT = "important";
    // 只检查文件大小，不计算校验和
    private static final String ALGORITHM_SIZE = "size";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // Store calculation results (in a real app, use a database)
    private Map<String, Integer> calculationResults = new HashMap<>();
    private Map<String, Boolean> calculationStatus = new HashMap<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (METHOD_GATHER_FILE_LIST.equals(method)) {
            // Simulate a complex calculation with uncertain delay
            int input = extras != null ? extras.getInt("input", 10) : 10;

            // Generate a unique request ID
            String requestId = "req_" + System.currentTimeMillis();

            // Return immediately to acknowledge the request
            Bundle ackResult = new Bundle();
            ackResult.putString("status", "processing");
            ackResult.putString("message", "Calculation started with input: " + input);
            ackResult.putString("request_id", requestId);

            generateFileListAsync();

            return ackResult;
        }

        return super.call(method, arg, extras);
    }

    private void generateFileListAsync() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        executorService.execute(() -> {
            File externalFilesDir = context.getExternalFilesDir(null);  // 先处理外部独立目录1
            File obbDir = context.getObbDir();                          // 再处理外部独立目录2（与上一步同级，顺序可互换）
            File filesDir = context.getFilesDir();                      // 处理内部子目录1（与下一步同级，顺序可互换）
            File noBackupFilesDir = context.getNoBackupFilesDir();      // 处理内部子目录2
            // fixme 这样会把cache和code_cache之类的包含进来
            File dataDir = context.getDataDir();                        // 最后处理父目录（内部存储根目录）

            File fileListFile = new File(filesDir, FILE_LIST_FILE_NAME);
            FileOutputStream fos = null;

            XmlSerializer serializer = Xml.newSerializer();

            // 不同路径文件可能重复，不需要重复传输
            Set<String> distinctSet = new HashSet<>();

            try {
                fos = new FileOutputStream(fileListFile);
                serializer.setOutput(fos, "UTF-8");
                serializer.startTag(null, TAG_ROOT);

                walkFilesInfo(TAG_EXTERNAL_FILES, externalFilesDir, externalFilesDir, distinctSet, serializer);
                walkFilesInfo(TAG_OBB, obbDir, obbDir, distinctSet, serializer);
                walkFilesInfo(TAG_FILES, filesDir, filesDir, distinctSet, serializer);
                walkFilesInfo(TAG_NO_BACKUP, noBackupFilesDir, noBackupFilesDir, distinctSet, serializer);
                walkFilesInfo(TAG_DATA, dataDir, dataDir, distinctSet, serializer);

                serializer.endTag(null, TAG_ROOT);

                serializer.endDocument();
                serializer.flush();
            } catch (IOException e) {
                Log.e("lhx", "Writing filelist error", e);
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        Log.e("lhx", "file close exception", e);
                    }
                }

                context.getContentResolver().notifyChange(CONTENT_FILE_LIST, null);
            }
        });
    }

    /**
     * 递归列出文件夹下的所有文件写入xml
     * @param baseTag base目录的名称
     * @param baseDir base目录
     * @param dir 当前目录
     * @param distinctSet 用于去重的集合，其中保存的是每个文件的绝对路径
     * @param xmlSerializer xml写入工具
     */
    private void walkFilesInfo(String baseTag, File baseDir, File dir, Set<String> distinctSet, XmlSerializer xmlSerializer) {
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            // 去重
            if (!distinctSet.add(file.getAbsolutePath())) {
                continue;
            }
            // 无权限
            if (!file.canRead()) {
                continue;
            }
            // 递归处理文件夹
            if (file.isDirectory()) {
                walkFilesInfo(baseTag, baseDir, file, distinctSet, xmlSerializer);
                continue;
            }
            // 排除列表文件自身
            if (baseDir == dir && file.getName().equals(FILE_LIST_FILE_NAME)) {
                continue;
            }
            String relativePath;
            try {
                relativePath = getRelativePath(baseDir, file);
            } catch (IllegalArgumentException e) {
                Log.e("lhx", "path error", e);
                continue;
            }
            int permission = 0;

            try {
                permission = Os.stat(file.getAbsolutePath()).st_mode;
            } catch (ErrnoException e) {
                Log.w("lhx", "get permission error", e);
            }

            long fileSize = file.length();
            try {
                xmlSerializer
                        .startTag(null, TAG_FILE) // <file>
                        .attribute(null, ATTR_BASE_DIR_NAME, baseTag) // 安卓中的基础路径名，如files
                        .attribute(null, ATTR_FILE_PATH, relativePath) // 相对路径
                        .attribute(null, ATTR_PERMISSION, Integer.toString(permission)) // 文件权限
                        .attribute(null, ATTR_ALGORITHM, ALGORITHM_SIZE) // 校验算法
                        .attribute(null, ATTR_CHECKSUM, Long.toString(fileSize))
                        .attribute(null, ATTR_RETRY_TIMES, "5")
                        .attribute(null, ATTR_IMPORTANT, "false")
                        .endTag(null, TAG_FILE); // </file>
            } catch (IOException e) {
                Log.w("lhx", "xml write error while processing" + file, e);
            }
        }
    }

    private String getRelativePath(File base, File current) throws IllegalArgumentException {
        Path basePath = Paths.get(base.toURI());
        Path currentPath = Paths.get(current.toURI());
        return basePath.relativize(currentPath).toString();
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