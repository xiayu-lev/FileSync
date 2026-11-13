package com.filesync.common.util;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 相对路径规范化与安全校验，防止路径穿越（../）访问同步根目录之外。
 */
public final class PathUtils {

    private PathUtils() {
    }

    /**
     * 规范化相对路径：统一 '/' 分隔符、去除首部斜杠、解析 . 与 ..。
     * 任何试图逃逸根目录的写法都会抛出异常。
     */
    public static String normalize(String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return "";
        }
        String p = relPath.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        String[] parts = p.split("/");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (out.isEmpty()) {
                    throw new IllegalArgumentException("路径越界: " + relPath);
                }
                out.remove(out.size() - 1);
            } else {
                out.add(part);
            }
        }
        return String.join("/", out);
    }

    /**
     * 在同步根目录下安全解析相对路径，并校验结果确实位于根目录之内。
     */
    public static File resolveSafe(File root, String relPath) {
        String normalized = normalize(relPath);
        Path base = root.getAbsoluteFile().toPath().normalize();
        Path target = normalized.isEmpty() ? base : base.resolve(normalized).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("非法路径访问: " + relPath);
        }
        return target.toFile();
    }

    /**
     * 计算 file 相对 root 的路径，使用 '/' 分隔。
     */
    public static String toRelative(File root, File file) {
        Path base = root.getAbsoluteFile().toPath().normalize();
        Path target = file.getAbsoluteFile().toPath().normalize();
        String rel = base.relativize(target).toString().replace('\\', '/');
        return normalize(rel);
    }

    /**
     * 判断相对路径是否位于内部状态目录中。
     */
    public static boolean isInternal(String relPath) {
        String n = normalize(relPath);
        return n.equals(".filesync") || n.startsWith(".filesync/");
    }
}
