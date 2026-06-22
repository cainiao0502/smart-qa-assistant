package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.chat.service.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path uploadDir;

    public LocalFileStorageService(@Value("${app.storage.upload-dir}") String uploadDir) {
        this.uploadDir = Path.of(uploadDir);
    }

    @Override
    public String save(MultipartFile file) {
        try {
            Files.createDirectories(uploadDir);
            String filename = UUID.randomUUID() + "-" + Objects.requireNonNullElse(file.getOriginalFilename(), "unknown");
            Path target = uploadDir.resolve(filename);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to save file", ex);
        }
    }

    @Override
    public void delete(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(storagePath));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to delete file", ex);
        }
    }
}
