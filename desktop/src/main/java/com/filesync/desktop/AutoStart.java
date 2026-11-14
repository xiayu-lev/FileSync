package com.filesync.desktop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Windows 开机自启：写入 HKCU\Software\Microsoft\Windows\CurrentVersion\Run。
 * 只有 jpackage 打包后的启动器才能注册，开发运行（java.exe）返回不支持的路径。
 */
final class AutoStart {

    private static final String RUN_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String VALUE_NAME = "FileSync";

    private AutoStart() {
    }

    static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 当前 jpackage 启动器路径；开发运行返回 null */
    static String installedLauncher() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath == null || appPath.isEmpty()) {
            return null;
        }
        File file = new File(appPath);
        return file.isFile() ? file.getAbsolutePath() : null;
    }

    static boolean isEnabled() {
        if (!isSupported()) {
            return false;
        }
        Result result = run("reg", "query", RUN_KEY, "/v", VALUE_NAME);
        return result.exitCode == 0;
    }

    /**
     * 开启时写入当前启动器路径（覆盖旧路径），关闭时删除。
     * 返回 false 表示当前不是安装版或注册表操作失败。
     */
    static boolean setEnabled(boolean enabled) {
        String launcher = installedLauncher();
        if (!isSupported() || launcher == null) {
            return false;
        }
        if (enabled) {
            Result result = run("reg", "add", RUN_KEY,
                    "/v", VALUE_NAME,
                    "/t", "REG_SZ",
                    "/d", "\"" + launcher + "\"",
                    "/f");
            return result.exitCode == 0;
        }
        Result result = run("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f");
        return result.exitCode == 0 || !isEnabled();
    }

    private static Result run(String... command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return new Result(finished ? process.exitValue() : -1, output);
        } catch (Exception e) {
            return new Result(-1, e.getMessage() == null ? "" : e.getMessage());
        }
    }

    private static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
