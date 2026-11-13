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

    /**
     * 两端日志统一使用的完成行：第一行标题，第二行只列本轮流真实发生的类别，
     * 避免手机窄屏日志区折行。
     */
    public String describe() {
        StringBuilder body = new StringBuilder();
        appendCount(body, "新增", added, " 项");
        appendCount(body, "更新", updated, " 项");
        appendCount(body, "删除", deleted, " 项");
        appendCount(body, "新建目录", directories, " 个");
        appendCount(body, "冲突副本", conflicts, " 个");
        if (body.length() == 0) {
            return "===== 同步完成 =====\n没有变化";
        }
        body.append(" · 共 ").append(total()).append(" 项");
        return "===== 同步完成 =====\n" + body;
    }

    private static void appendCount(StringBuilder sb, String label, int count, String unit) {
        if (count <= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(label).append(' ').append(count).append(unit);
    }
}
