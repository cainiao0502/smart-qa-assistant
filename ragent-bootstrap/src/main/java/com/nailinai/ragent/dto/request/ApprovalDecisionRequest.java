package com.nailinai.ragent.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提交一次工具审批决定的请求体。
 *
 * <p>只带一个布尔字段而不是「命令码」：审批的语义是二元的（执行 / 不执行），
 * 让它保持二元可以避免前端造出「部分批准」这类服务端无法兑现的表述。</p>
 */
@Data
public class ApprovalDecisionRequest {

    /** true 表示授权执行，false 表示拒绝 */
    @NotNull(message = "approved is required")
    private Boolean approved;
}
