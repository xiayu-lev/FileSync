package com.filesync.common.protocol;

/**
 * HTTP API 路径与查询参数常量。两端服务器必须实现同一套契约。
 */
public final class ApiPaths {

    public static final String API_PREFIX = "/api";
    public static final String INFO = "/api/info";
    public static final String MANIFEST = "/api/manifest";
    public static final String FILE = "/api/file";
    public static final String PING = "/api/ping";

    public static final String PARAM_PATH = "path";
    public static final String PARAM_MTIME = "mtime";

    public static final String HEADER_PARTNER_ID = "X-Partner-Id";

    private ApiPaths() {
    }
}
