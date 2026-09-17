package com.nailinai.ragent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * 待决审批注册表：把「Agent 正在等一个人类决定」这件事落在服务端。
 *
 * <p>Agent 线程在 {@link #await} 上阻塞等待，而人工决定通过一次独立的 HTTP 请求回来——
 * 两者之间需要一个共享的会合点，本类就是这个会合点。之所以不能把决定直接塞回
 * SSE 连接里，是因为 <b>SSE 是单向的</b>（服务端 → 客户端），客户端无法在同一个连接上回话。</p>
 *
 * <p><b>三种终止方式都必须收敛</b>，否则 Agent 线程会一直挂着：
 * 人工决定、等待超时、连接断开。三者之外的任何情况都返回
 * {@link ApprovalOutcome#UNAVAILABLE}，由策略按未授权处理。</p>
 */
@Component
public class ApprovalRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRegistry.class);

    /** 轮询粒度：既要及时感知连接断开，又不至于空转 */
    private static final long POLL_INTERVAL_MS = 500L;

    private final ConcurrentMap<String, PendingApproval> pending = new ConcurrentHashMap<>();

    /**
     * 登记一次待决审批。
     *
     * @return 待决项，其中的 approvalId 用于下发与回填
     */
    public PendingApproval register(String runId, String toolName, Long ownerUserId) {
        String approvalId = "apv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        PendingApproval approval = new PendingApproval(
                approvalId, runId, toolName, ownerUserId, new CompletableFuture<>());
        pending.put(approvalId, approval);
        log.info("Approval registered: id={}, runId={}, tool={}", approvalId, runId, toolName);
        return approval;
    }

    /**
     * 提交人工决定（仅限发起人）。
     *
     * @return 是否成功命中一个仍在等待、且属于该用户的审批
     */
    public boolean decide(String approvalId, boolean approved, Long userId) {
        PendingApproval approval = pending.get(approvalId);
        if (approval == null) {
            log.info("Approval decision ignored (unknown or expired): id={}", approvalId);
            return false;
        }
        if (approval.ownerUserId() != null && !approval.ownerUserId().equals(userId)) {
            // 不暴露「这个 id 存在但不属于你」的细节，避免被用来探测他人审批
            log.warn("Approval decision rejected (not the requester): id={}, userId={}", approvalId, userId);
            return false;
        }
        return approval.future().complete(approved ? ApprovalOutcome.APPROVED : ApprovalOutcome.REJECTED);
    }

    /**
     * 阻塞等待人工决定。
     *
     * @param aliveCheck 可选的存活探测（例如「SSE 连接是否还在」）；
     *                   返回 false 立刻结束等待——连接断了就没人在看确认界面了
     */
    public ApprovalOutcome await(String approvalId, long timeoutMs, BooleanSupplier aliveCheck) {
        PendingApproval approval = pending.get(approvalId);
        if (approval == null) {
            return ApprovalOutcome.UNAVAILABLE;
        }
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            try {
                return approval.future().get(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillWaiting) {
                if (aliveCheck != null && !aliveCheck.getAsBoolean()) {
                    log.info("Approval abandoned (connection closed): id={}", approvalId);
                    return ApprovalOutcome.UNAVAILABLE;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                log.warn("Approval wait interrupted: id={}", approvalId);
                return ApprovalOutcome.UNAVAILABLE;
            } catch (ExecutionException failed) {
                log.warn("Approval wait failed: id={}, reason={}", approvalId, failed.getCause().toString());
                return ApprovalOutcome.UNAVAILABLE;
            }
        }
        log.info("Approval timed out: id={}", approvalId);
        return ApprovalOutcome.UNAVAILABLE;
    }

    /** 释放待决项（无论结果如何都要调用，避免累积）。 */
    public void remove(String approvalId) {
        pending.remove(approvalId);
    }

    public int pendingCount() {
        return pending.size();
    }

    /**
     * 一次待决审批。
     *
     * @param approvalId   审批标识（下发给客户端、回填时使用）
     * @param runId        所属运行
     * @param toolName     待执行的工具
     * @param ownerUserId  发起该次运行的用户（用于决定接口的归属校验）
     * @param future       结论会合点
     */
    public record PendingApproval(
            String approvalId,
            String runId,
            String toolName,
            Long ownerUserId,
            CompletableFuture<ApprovalOutcome> future
    ) {
    }
}
