package com.nailinai.ragent.user.context;

/**
 * 跨线程传递「当前登录用户 ID」。
 *
 * <p><b>为什么需要它：</b>{@link UserContext} 底层是 Sa-Token 的 {@code StpUtil}，
 * 登录态存在 ThreadLocal + 请求对象里。一旦请求被投递到异步线程
 * （例如 {@code /api/chat/stream} 用 {@code CompletableFuture.runAsync} 丢进
 * ForkJoinPool），上下文即丢失，{@code currentUserId()} 抛异常。
 * 后果是落库时 {@code chat_message.owner_user_id} 被写为 NULL，
 * 而会话列表按 {@code WHERE owner_user_id = ?} 查询 —— NULL 参与等值比较恒为
 * false，于是「历史记录」永远查不出来。</p>
 *
 * <p><b>调用约定（三步，缺一不可）：</b></p>
 * <ol>
 *   <li>在<b>请求线程</b>中调用 {@link #capture()} 取到 userId（此时上下文还在）；</li>
 *   <li>异步任务<b>开头</b>调用 {@link #set(Long)}；</li>
 *   <li>异步任务 <b>finally</b> 中调用 {@link #clear()}，避免线程池复用导致的串号。</li>
 * </ol>
 *
 * <p>{@link #get()} 会优先返回显式传递的值，未设置时回退到 Sa-Token 上下文，
 * 因此同步请求（{@code /api/chat}）无需任何改动即可继续工作。</p>
 */
public final class UserIdHolder {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private UserIdHolder() {
    }

    /** 在请求线程中捕获当前登录用户 ID；未登录或上下文缺失时返回 {@code null}。 */
    public static Long capture() {
        try {
            return UserContext.currentUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 在异步线程中绑定用户 ID；传 {@code null} 等同于 {@link #clear()}。 */
    public static void set(Long userId) {
        if (userId == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(userId);
        }
    }

    /** 取当前用户 ID：优先显式绑定值，回退到 Sa-Token 上下文。 */
    public static Long get() {
        Long explicit = HOLDER.get();
        return explicit != null ? explicit : capture();
    }

    /** 清理绑定，必须在异步任务结束时调用。 */
    public static void clear() {
        HOLDER.remove();
    }
}
