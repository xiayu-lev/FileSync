package com.filesync.common.util;

import com.filesync.common.model.FileMeta;
import com.filesync.common.protocol.SyncConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 递归扫描同步根目录，生成文件元数据清单。内部状态目录会被跳过。
 */
public final class FileWalker {

    private FileWalker() {
    }

    public static List<FileMeta> walk(File root) {
        List<FileMeta> result = new ArrayList<>();
        if (root == null || !root.exists()) {
            return result;
        }
        walkRecursive(root, root, result);
        return result;
    }

    private static void walkRecursive(File root, File current, List<FileMeta> out) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String rel = PathUtils.toRelative(root, child);
            if (rel.equals(SyncConstants.INTERNAL_DIR)
                    || rel.startsWith(SyncConstants.INTERNAL_DIR + "/")) {
                continue;
            }
            // 传输中间文件不入清单，避免把未完成的 .part 当成正式文件同步
            if (rel.contains(SyncConstants.TEMP_SUFFIX)) {
                continue;
            }
            out.add(new FileMeta(rel, child.isDirectory() ? 0L : child.length(),
                    child.lastModified(), child.isDirectory()));
            if (child.isDirectory()) {
                walkRecursive(root, child, out);
            }
        }
    }
}
