package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.chat.service.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class LocalFileStorageService implements FileStorageService {

    /** 统一使用绝对路径做根目录，避免配置为相对路径（默认 uploads）时守卫失效 */
    private final Path uploadRoot;

    public LocalFileStorageService(@Value("${app.storage.upload-dir}") String uploadDir) {
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public String save(MultipartFile file) {
        try {
            Files.createDirectories(uploadRoot);
            // 安全：原始文件名来自客户端，可能包含 ../ 等路径穿越序列。
            // 存储名只用 UUID + 白名单化的扩展名，原始文件名不参与路径构造。
            // 一律以绝对根目录 resolve，保证返回的存储路径是绝对路径，
            // 与 delete() 的目录内校验保持同一基准（旧实现相对/绝对混比恒 false）。
            String ext = sanitizeExtension(file.getOriginalFilename());
            Path target = uploadRoot.resolve(UUID.randomUUID() + ext).normalize();
            if (!target.startsWith(uploadRoot)) {
                throw new IllegalStateException("resolved path escapes upload dir");
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to save file", ex);
        }
    }

    /** 提取扩展名并只保留字母数字，最长 16 字符；无扩展名返回空串。 */
    private String sanitizeExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        String ext = originalFilename.substring(dot + 1).replaceAll("[^a-zA-Z0-9]", "");
        if (ext.isEmpty()) {
            return "";
        }
        return "." + ext.substring(0, Math.min(ext.length(), 16)).toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void delete(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        // 安全：只允许删除上传目录内的文件，防止误删或被构造路径删除任意文件。
        // 存储路径可能来自历史数据的相对路径，先按工作目录绝对化再比较。
        Path target = Path.of(storagePath).toAbsolutePath().normalize();
        if (!target.startsWith(uploadRoot)) {
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException ex) {
            throw new IllegalStateException("failed to delete file", ex);
        }
    }
}
