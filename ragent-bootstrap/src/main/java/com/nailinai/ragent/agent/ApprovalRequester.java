package com.nailinai.ragent.agent;

import com.nailinai.ragent.agent.dto.ApprovalRequest;

/**
 * 审批通道：策略用它把「这次调用需要人工确认」这件事交出去，并等待结果。
 *
 * <p>由运行时按当前请求构造（通常包装 SSE 事件通道）。策略只依赖这个函数式接口，
 * 不关心审批是怎么问出来的——同步 HTTP 请求下没有可用的交互通道，实现会返回
 * {@link ApprovalOutcome#UNAVAILABLE}，策略据此降级。</p>
 *
 * <p><b>实现必须是阻塞的</b>：策略需要拿到明确结论才能决定放行还是拦截，
 * 因此实现内部要自行处理超时与断线（超时按未授权处理）。</p>
 */
@FunctionalInterface
public interface ApprovalRequester {

    ApprovalOutcome request(ApprovalRequest request);
}
