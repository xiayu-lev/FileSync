package com.filesync.desktop;

/**
 * 打包启动壳。
 *
 * <p>Java 启动器在 main class 直接继承 {@code javafx.application.Application} 时，
 * 会强制检查 javafx.graphics 模块是否在模块路径上；jpackage 的 app-image 把 JavaFX
 * 以 classpath 形式随包分发，会因此报“缺少 JavaFX 运行时组件”。用一个不继承
 * Application 的入口转调 MainApp.main，即可按普通 classpath 应用启动。</p>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        MainApp.main(args);
    }
}
