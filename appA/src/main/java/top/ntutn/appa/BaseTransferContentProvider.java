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
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class BaseTransferContentProvider extends ContentProvider {
    public static final String AUTHORITY = "top.ntutn.appa.provider";
    private static final Uri CONTENT_FILE_LIST = Uri.parse("content://" + AUTHORITY + "/list");

    public static final String METHOD_GATHER_FILE_LIST = "gather_file_list";

    // URI paths
    public static final String PATH_CALCULATION_RESULTS = "calculation_results";
    private static final String FILE_LIST_FILE_NAME = ".file_list.xml";
    private static final String TAG_FILE = "file";
    private static final String TAG_ROOT = "list";
    private static final String ATTR_BASE_DIR_NAME = "base";
    private static final String ATTR_FILE_PATH = "path";
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

    private List<TransferFileInfo> mTransferFileInfos = new ArrayList<>();
    private Set<String> mValidPathTags = new HashSet<>();

    @Override
    public boolean onCreate() {
        // 配置要同步的路径
        mTransferFileInfos = gatherTransferFileInfos();
        mValidPathTags = new HashSet<>();
        for (TransferFileInfo info : mTransferFileInfos) {
            mValidPathTags.add(info.getBaseDirTag());
        }
        // 清单文件被保存到files文件夹下
        mValidPathTags.add(TransferFileInfo.TAG_FILES_DIR);
        return true;
    }

    protected abstract List<TransferFileInfo> gatherTransferFileInfos();

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
            File filesDir = context.getFilesDir();

            File fileListFile = new File(filesDir, FILE_LIST_FILE_NAME);
            FileOutputStream fos = null;

            XmlSerializer serializer = Xml.newSerializer();

            // 不同路径文件可能重复，不需要重复传输
            Set<String> distinctSet = new HashSet<>();

            try {
                fos = new FileOutputStream(fileListFile);
                serializer.setOutput(fos, "UTF-8");
                // 配置格式化
                serializer.startDocument("UTF-8", true);
                serializer.startTag(null, TAG_ROOT);

                for (TransferFileInfo info : mTransferFileInfos) {
                    walkFilesInfo(info.getBaseDirTag(), info.getBaseDir(), info.getDir(), distinctSet, serializer);
                }

                serializer.endTag(null, TAG_ROOT);
                serializer.endDocument();
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

                mainHandler.post(() -> {
                    context.getContentResolver().notifyChange(CONTENT_FILE_LIST, null);
                });
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
        assert baseDir.isDirectory();
        if (!dir.isDirectory()) {
            // 去重
            if (!distinctSet.add(dir.getAbsolutePath())) {
                return;
            }
            // 无权限
            if (!dir.canRead()) {
                return;
            }
            // 排除列表文件自身
            if (dir.getName().equals(FILE_LIST_FILE_NAME)) {
                return;
            }
            writeFileInfo2xml(baseTag, baseDir, xmlSerializer, dir);
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
            writeFileInfo2xml(baseTag, baseDir, xmlSerializer, file);
        }
    }

    private void writeFileInfo2xml(String baseTag, File baseDir, XmlSerializer xmlSerializer, File file) {
        String relativePath;
        try {
            relativePath = getRelativePath(baseDir, file);
        } catch (IllegalArgumentException e) {
            Log.e("lhx", "path error", e);
            return;
        }

        long fileSize = file.length();
        try {
            xmlSerializer
                    .startTag(null, TAG_FILE) // <file>
                    .attribute(null, ATTR_BASE_DIR_NAME, baseTag) // 安卓中的基础路径名，如files
                    .attribute(null, ATTR_FILE_PATH, relativePath) // 相对路径
                    .attribute(null, ATTR_ALGORITHM, ALGORITHM_SIZE) // 校验算法
                    .attribute(null, ATTR_CHECKSUM, Long.toString(fileSize))
                    .attribute(null, ATTR_RETRY_TIMES, "5")
                    .attribute(null, ATTR_IMPORTANT, "false")
                    .endTag(null, TAG_FILE); // </file>
        } catch (IOException e) {
            Log.w("lhx", "xml write error while processing" + file, e);
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

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        if (mode.contains("w")) {
            throw new SecurityException("Readonly assets.");
        }
        String basePathTag = uri.getQueryParameter("base");
        String relativePath = uri.getQueryParameter("path");

        if (basePathTag == null || relativePath == null) {
            throw new FileNotFoundException("invalid request");
        }

        if (mValidPathTags.stream().noneMatch(basePathTag::equals)) {
            throw new FileNotFoundException("request file base = " + basePathTag + " not in a valid path");
        }

        File dir = TransferFileInfo.getDirViaTag(getContext(), basePathTag);
        if (dir == null) {
            // should not reach here
            throw new FileNotFoundException("request file base = " + basePathTag + " not found");
        }
        return ParcelFileDescriptor.open(new File(dir, relativePath), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}