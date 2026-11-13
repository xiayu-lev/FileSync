package com.filesync.common.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * 全局 JSON 序列化入口，两端共用同一 Gson 配置。
 */
public final class JsonCodec {

    private static final Gson GSON = new GsonBuilder()
            .setLenient()
            .disableHtmlEscaping()
            .create();

    private JsonCodec() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }
}
