package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.chat.service.DocumentParser;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

@Service
public class TikaDocumentParser implements DocumentParser {

    private final Tika tika = new Tika();

    @Override
    public String parse(File file) {
        try {
            return tika.parseToString(file);
        } catch (IOException | TikaException ex) {
            throw new IllegalStateException("failed to parse document", ex);
        }
    }
}
