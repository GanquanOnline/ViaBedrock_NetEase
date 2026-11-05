/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.resourcepack.content;

import com.viaversion.viaversion.libs.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 对一棵有效资源包文件树的只读访问。
 *
 * <p>该视图刻意不提供修改方法。可变的 {@link Content} 仍是兼容性与转换 API，
 * 而共享缓存条目只暴露这一接口。</p>
 */
public interface PackContentView {

    List<String> getFilesShallow(String path, String extension);

    List<String> getFilesDeep(String path, String extension);

    String getFullPath(String shortNamePath, String... extensions);

    boolean contains(String path);

    InputStream open(String path) throws IOException;

    long size(String path) throws IOException;

    void visitFiles(List<String> paths, Content.FileVisitor visitor) throws IOException;

    <T> T withReadSession(Supplier<T> action);

    String getString(String path);

    List<String> getLines(String path);

    Map<String, String> getLang(String path);

    JsonObject getJson(String path);

    JsonObject getSortedJson(String path);

    void writeZip(Path target) throws IOException;

    void writeZip(OutputStream output) throws IOException;

    void releaseTransientCaches();

}
