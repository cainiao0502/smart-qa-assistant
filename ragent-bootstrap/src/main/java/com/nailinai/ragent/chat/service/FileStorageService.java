package com.nailinai.ragent.chat.service;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

    String save(MultipartFile file);

    void delete(String storagePath);
}
