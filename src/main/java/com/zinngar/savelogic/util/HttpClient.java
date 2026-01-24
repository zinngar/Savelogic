package com.zinngar.savelogic.util;

import okhttp3.OkHttpClient;

public class HttpClient {

    private static final OkHttpClient client = new OkHttpClient();

    public static OkHttpClient getClient() {
        return client;
    }
}
