package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.chat.service.DocumentParser;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 基于 Apache Tika 的文档解析器。
 *
 * <p><b>长度上限</b>：过去直接调用 {@code Tika#parseToString}，对输出长度没有任何约束——
 * 一份数百页的 PDF 会把整份文本读进内存，随后才在切分阶段被拆开。现在通过
 * {@link BodyContentHandler} 的 writeLimit 在<b>解析阶段</b>就封顶；超限时保留已提取的前 N 个字符
 * 并记录告警，遵循 pi 的原则：截断必须是显式的，调用方要能知道。
 */
@Service
public class TikaDocumentParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentParser.class);

    private final AutoDetectParser parser = new AutoDetectParser();
    private final int maxParseChars;

    public TikaDocumentParser(@Value("${app.document.max-parse-chars:200000}") int maxParseChars) {
        this.maxParseChars = Math.max(1_000, maxParseChars);
    }

    @Override
    public String parse(File file) {
        BodyContentHandler handler = new BodyContentHandler(maxParseChars);
        Metadata metadata = new Metadata();
        try (InputStream stream = new FileInputStream(file)) {
            parser.parse(stream, handler, metadata, new ParseContext());
            return handler.toString();
        } catch (SAXException ex) {
            // Tika 用 SAXException 表示「已提取文本超过 writeLimit」；
            // 此时 handler 中已保留了前 maxParseChars 个字符，直接采用即可
            String partial = handler.toString();
            log.warn("Document {} exceeded the {}-char parse limit; only the first {} chars were indexed. "
                            + "Raise app.document.max-parse-chars if this document must be fully indexed.",
                    file.getName(), maxParseChars, partial.length());
            return partial;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse document", ex);
        }
    }
}
