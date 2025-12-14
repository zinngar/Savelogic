package com.example.mod;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface CloudStorageProvider {
    CompletableFuture<Void> uploadSave(File zipFile);
    CompletableFuture<File> downloadSave(String saveName);
    CompletableFuture<List<String>> listSaves();
    String getProviderName();
}
