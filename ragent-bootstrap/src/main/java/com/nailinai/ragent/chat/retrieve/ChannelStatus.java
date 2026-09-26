package com.nailinai.ragent.chat.retrieve;

/**
 * 单个检索通道在本次检索中的参与状态。
 *
 * <p>通道抛异常时会被检索器隔离（其余通道继续），但「某个通道静默退出」本身是
 * 检索质量劣化的信号——纯向量检索替代混合检索而无人察觉，比慢更危险。
 * 该状态随检索结果上报，最终进入 retrievalConfig 供前端与调试端点展示。</p>
 *
 * @param channel  通道名（vector / keyword / ...）
 * @param degraded 是否因异常退出本次检索
 * @param error    降级时的异常摘要（非降级为 null）
 */
public record ChannelStatus(String channel, boolean degraded, String error) {

    public static ChannelStatus ok(String channel) {
        return new ChannelStatus(channel, false, null);
    }

    public static ChannelStatus degraded(String channel, String error) {
        return new ChannelStatus(channel, true, error);
    }
}
