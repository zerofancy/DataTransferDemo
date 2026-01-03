package top.ntutn.appb;

import android.content.Context;

import java.io.File;

public class TransferFileInfo {
    public static final String TAG_FILES_DIR = "files";
    public static final String TAG_OBB_DIR = "obb";
    public static final String TAG_EXTERNAL_FILES_DIR = "external_files";
    public static final String TAG_NO_BACKUP_DIR = "no_backup";
    public static final String TAG_DATA = "data";

    public static File getDirViaTag(Context context, String tag) {
        if (context == null) {
            return null;
        }

        switch (tag) {
            case TAG_FILES_DIR:
                return context.getFilesDir();
            case TAG_OBB_DIR:
                return context.getObbDir();
            case TAG_EXTERNAL_FILES_DIR:
                return context.getExternalFilesDir("");
            case TAG_NO_BACKUP_DIR:
                return context.getNoBackupFilesDir();
            case TAG_DATA:
                return context.getDataDir();
        }
        return null;
    }


    private final String baseDirTag; // base路径名，如files
    private final String relativePath;
    private final String algorithm;
    private final String checksum;
    private final int retryTimes;
    private final boolean important;

    public TransferFileInfo(String baseDirTag, String relativePath, String algorithm, String checksum, int retryTimes, boolean important) {
        this.baseDirTag = baseDirTag;
        this.relativePath = relativePath;
        this.algorithm = algorithm;
        this.checksum = checksum;
        this.retryTimes = retryTimes;
        this.important = important;
    }

    public String getBaseDirTag() {
        return baseDirTag;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getChecksum() {
        return checksum;
    }

    public int getRetryTimes() {
        return retryTimes;
    }

    public boolean isImportant() {
        return important;
    }
}
