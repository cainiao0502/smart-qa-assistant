package com.nailinai.ragent.infra.vision;

/**
 * 视觉模型客户端：把图片转成可检索的文本描述。
 *
 * <p><b>为什么需要它</b>：RAG 链路只认文本。图片型文档（扫描件、截图、图表）经 Tika 解析后
 * 正文为空，最终零切片、检索不到——用户传了图却问不出任何东西。
 * 视觉模型负责把图片「翻译」成文本，让既有检索链路无需改动即可支持图片。
 */
public interface VisionClient {

    /** 是否可用。未配置视觉模型时为 false，调用方据此退化为纯文本解析。 */
    boolean available();

    /**
     * 让视觉模型描述一张图片。
     *
     * @param imageBytes 图片字节
     * @param mimeType   图片 MIME 类型（如 {@code image/png}）
     * @param prompt     描述提示词
     * @return 描述文本；不可用或未获得有效内容时返回 null
     */
    String describe(byte[] imageBytes, String mimeType, String prompt);

    /** 未配置视觉模型时的占位实现，保证 bean 始终存在、调用方无需判空 */
    static VisionClient unavailable() {
        return new VisionClient() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String describe(byte[] imageBytes, String mimeType, String prompt) {
                return null;
            }
        };
    }
}
