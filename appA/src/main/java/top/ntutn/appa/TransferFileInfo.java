package top.ntutn.appa;

import java.io.File;

public class TransferFileInfo {
    public static final String TAG_FILES_DIR = "files";
    public static final String TAG_OBB_DIR = "obb";
    public static final String TAG_EXTERNAL_FILES_DIR = "external_files";
    public static final String TAG_NO_BACKUP_DIR = "no_backup";
    public static final String TAG_DATA = "data";

    private final String baseDirTag; // base路径名，如files
    private final File baseDir; // base路径
    private final File dir; // 需要加入的文件或文件夹

    public TransferFileInfo(String baseDirTag, File baseDir, File dir) {
        this.baseDirTag = baseDirTag;
        this.baseDir = baseDir;
        this.dir = dir;
    }

    public String getBaseDirTag() {
        return baseDirTag;
    }

    public File getBaseDir() {
        return baseDir;
    }

    public File getDir() {
        return dir;
    }
}
