package com.nailinai.ragent.mcp;

/**
 * MCP 工具的风险标注（对应规范 {@code tools/list} 返回值里的 {@code annotations} 字段）。
 *
 * <p>这组元数据是 MCP 协议自带的：工具提供方可以声明「我是只读的」「我会破坏数据」
 * 「我会和外部世界交互」。客户端本应据此判断一个工具能不能被随意调用。</p>
 *
 * <p><b>本项目此前在解析工具定义时把整个 annotations 节点丢掉了</b>，导致只读查询工具
 * 与破坏性工具在系统内部毫无区别——审批策略也就无从判起。本类补上这一层。</p>
 *
 * <p><b>默认值遵循 MCP 规范并采取 fail-safe</b>：{@code readOnlyHint} 未声明按 {@code false}、
 * {@code destructiveHint} 未声明按 {@code true}。也就是说「对方没声明」不等于「安全」，
 * 服务端不表态时客户端必须按最坏情况处理。</p>
 *
 * @param declared        服务端是否真的提供了 annotations（区分「未声明」与「声明为 false」）
 * @param readOnlyHint    是否为只读工具（不修改任何状态）
 * @param destructiveHint 是否具有破坏性（可能造成不可逆变更）
 * @param openWorldHint   是否会与外部世界交互（发起网络请求、访问第三方系统）
 */
public record ToolAnnotations(
        boolean declared,
        boolean readOnlyHint,
        boolean destructiveHint,
        boolean openWorldHint
) {

    /** 服务端未提供 annotations——按最保守档位处理 */
    public static final ToolAnnotations UNKNOWN = new ToolAnnotations(false, false, true, true);

    public static ToolAnnotations of(boolean readOnlyHint, boolean destructiveHint, boolean openWorldHint) {
        return new ToolAnnotations(true, readOnlyHint, destructiveHint, openWorldHint);
    }

    /**
     * 是否可以直接执行、无需人工确认。
     *
     * <p>判定刻意只认「只读且不接触外部世界」这一档，理由：
     * <ul>
     *   <li><b>只读不等于安全</b>——抓取任意 URL 的 fetch 类工具也是只读的，
     *       但它可以探测内网、把上下文里的内容带到第三方，属外联风险；</li>
     *   <li>因此把 {@code openWorldHint} 作为独立判据，而非并入 destructive。</li>
     * </ul>
     */
    public boolean safeToAutoRun() {
        return readOnlyHint && !openWorldHint;
    }

    /** 供日志与前端展示的风险摘要 */
    public String describeRisk() {
        if (!declared) {
            return "未声明的工具（提供方未标注风险等级）";
        }
        if (safeToAutoRun()) {
            return "只读工具";
        }
        if (!readOnlyHint) {
            return destructiveHint ? "可能修改或破坏数据的工具" : "会修改状态的工具";
        }
        return "会与外部系统交互的只读工具";
    }
}
