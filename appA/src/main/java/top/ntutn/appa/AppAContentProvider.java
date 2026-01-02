package top.ntutn.appa;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

public class AppAContentProvider extends BaseTransferContentProvider {
    @Override
    protected List<TransferFileInfo> gatherTransferFileInfos() {
        List<TransferFileInfo> infos = new ArrayList<>();

        Context context = getContext();
        if (context == null) {
            // should not reach here
            return infos;
        }

        // 只同步files文件夹
        infos.add(new TransferFileInfo(TransferFileInfo.TAG_FILES_DIR, context.getFilesDir(), context.getFilesDir()));

        return infos;
    }
}
