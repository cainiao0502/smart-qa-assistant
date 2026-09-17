package com.nailinai.ragent.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审批会合点：三种终止方式（人工决定 / 超时 / 连接断开）都必须收敛，
 * 否则 Agent 线程会一直挂在那里。
 */
class ApprovalRegistryTest {

    private final ApprovalRegistry registry = new ApprovalRegistry();

    @Test
    @DisplayName("批准后等待方拿到 APPROVED")
    void approvedOutcomeIsDelivered() throws Exception {
        ApprovalRegistry.PendingApproval pending = registry.register("run-1", "toolA", 100L);
        Thread approver = new Thread(() -> registry.decide(pending.approvalId(), true, 100L));
        approver.start();

        ApprovalOutcome outcome = registry.await(pending.approvalId(), 5000, () -> true);

        assertThat(outcome).isEqualTo(ApprovalOutcome.APPROVED);
        approver.join();
    }

    @Test
    @DisplayName("拒绝后等待方拿到 REJECTED")
    void rejectedOutcomeIsDelivered() throws Exception {
        ApprovalRegistry.PendingApproval pending = registry.register("run-1", "toolA", 100L);
        Thread approver = new Thread(() -> registry.decide(pending.approvalId(), false, 100L));
        approver.start();

        ApprovalOutcome outcome = registry.await(pending.approvalId(), 5000, () -> true);

        assertThat(outcome).isEqualTo(ApprovalOutcome.REJECTED);
        approver.join();
    }

    @Test
    @DisplayName("无人回应则超时：返回 UNAVAILABLE 而不是放行")
    void timeoutYieldsUnavailable() {
        ApprovalRegistry.PendingApproval pending = registry.register("run-1", "toolA", 100L);

        ApprovalOutcome outcome = registry.await(pending.approvalId(), 1200, () -> true);

        assertThat(outcome).isEqualTo(ApprovalOutcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("连接断开时立即结束等待，不必耗到超时")
    void disconnectEndsWaitImmediately() {
        ApprovalRegistry.PendingApproval pending = registry.register("run-1", "toolA", 100L);

        long started = System.currentTimeMillis();
        ApprovalOutcome outcome = registry.await(pending.approvalId(), 30000, () -> false);
        long elapsed = System.currentTimeMillis() - started;

        assertThat(outcome).isEqualTo(ApprovalOutcome.UNAVAILABLE);
        assertThat(elapsed).isLessThan(5000L);
    }

    @Test
    @DisplayName("决定只能由发起人提交——否则任何人都能凭 id 批准他人的操作")
    void onlyOwnerCanDecide() {
        ApprovalRegistry.PendingApproval pending = registry.register("run-1", "toolA", 100L);

        assertThat(registry.decide(pending.approvalId(), true, 999L)).isFalse();
        assertThat(registry.decide(pending.approvalId(), true, 100L)).isTrue();
    }

    @Test
    @DisplayName("未知或已过期的审批不接受决定")
    void unknownApprovalIsRejected() {
        assertThat(registry.decide("apv_not_exist", true, 100L)).isFalse();
        assertThat(registry.await("apv_not_exist", 1000, () -> true))
                .isEqualTo(ApprovalOutcome.UNAVAILABLE);
    }
}
