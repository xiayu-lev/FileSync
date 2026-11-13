package com.filesync.common.sync;

/**
 * 一次同步的变更统计：按「新增 / 更新 / 删除」分类，供两端日志与界面显示。
 */
public class SyncSummary {

    private int added;
    private int updated;
    private int deleted;
    private int directories;
    private int conflicts;

    public void countAdded() {
        added++;
    }

    public void countUpdated() {
        updated++;
    }

    public void countDeleted() {
        deleted++;
    }

    public void countDirectory() {
        directories++;
    }

    public void countConflict() {
        conflicts++;
    }

    public int getAdded() {
        return added;
    }

    public int getUpdated() {
        return updated;
    }

    public int getDeleted() {
        return deleted;
    }

    public int getDirectories() {
        return directories;
    }

    public int getConflicts() {
        return conflicts;
    }

    public int total() {
        return added + updated + deleted + directories + conflicts;
    }

    /** 两端日志统一使用的完成行 */
    public String describe() {
        StringBuilder sb = new StringBuilder("===== 同步完成：新增 ").append(added)
                .append(" 项，更新 ").append(updated)
                .append(" 项，删除 ").append(deleted).append(" 项");
        if (directories > 0) {
            sb.append("，新建目录 ").append(directories).append(" 个");
        }
        if (conflicts > 0) {
            sb.append("，冲突副本 ").append(conflicts).append(" 个");
        }
        sb.append("，共 ").append(total()).append(" 项 =====");
        return sb.toString();
    }
}
