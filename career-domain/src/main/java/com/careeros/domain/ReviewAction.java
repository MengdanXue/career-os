package com.careeros.domain;

import com.careeros.domain.DomainEnums.ReviewDecision;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次人工复核动作。
 *
 * @param actor 执行该动作的已认证主体。人工复核是这套系统里唯一把模型提案提升为
 *              已核验官方数据的闸门，因此"谁按下的 CONFIRM"必须与动作本身一起
 *              不可变地记录下来，不能缺省。
 */
public record ReviewAction(
    UUID id,
    UUID reviewItemId,
    ReviewDecision decision,
    long expectedVersion,
    ReviewPayload originalPayload,
    ReviewPayload correctedPayload,
    String note,
    String actor,
    Instant actedAt
) {
    public ReviewAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reviewItemId, "reviewItemId");
        Objects.requireNonNull(decision, "decision");
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        Objects.requireNonNull(originalPayload, "originalPayload");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is required");
        Objects.requireNonNull(actedAt, "actedAt");
    }
}
