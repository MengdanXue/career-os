package com.careeros.application.personal;

import com.careeros.domain.CandidateFacts.CandidateFactKey;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ProfileConfirmationPorts {
    private ProfileConfirmationPorts() {}

    /**
     * 确认回答的台账。它同时承担两件事：幂等与失败恢复。
     *
     * <p>幂等：同一个 idempotencyKey 重放时不能第二次改写资料，也不能第二次推高资料版本。
     *
     * <p>失败恢复：资料已写入但重算失败，是一个必须能恢复的中间态——不能让用户的声明留在库里、
     * 岗位结论却还是旧的。所以台账先记 {@link Stage#WRITTEN}，重算成功后才推进到
     * {@link Stage#RECOMPUTED}；带同一把钥匙重试会从重算那一步接着做，而不是重新写一遍资料。
     */
    public interface ConfirmationLedger {
        Optional<LedgerEntry> find(UUID candidateId, String idempotencyKey);

        /**
         * 写入或推进台账。
         *
         * <p>必须与资料写入处在同一个事务里：先提交资料后提交台账，中间崩溃会让重试误判成
         * 尚未写入，于是重复写一次。
         */
        LedgerEntry save(LedgerEntry entry);
    }

    /** 台账阶段。{@code WRITTEN} 表示资料已落库但结论尚未重算。 */
    public enum Stage { WRITTEN, RECOMPUTED }

    /**
     * @param declaredValue        规范化后的声明值，用于识别"同一把钥匙换了个答案"这种误用
     * @param profileVersionBefore 写入前的资料版本，重算要拿它作对照基线
     */
    public record LedgerEntry(
        UUID candidateId,
        String idempotencyKey,
        CandidateFactKey factKey,
        String declaredValue,
        Stage stage,
        String profileVersionBefore,
        String profileVersionAfter,
        Instant recordedAt
    ) {
        public LedgerEntry {
            Objects.requireNonNull(candidateId, "candidateId");
            require(idempotencyKey, "idempotencyKey");
            Objects.requireNonNull(factKey, "factKey");
            require(declaredValue, "declaredValue");
            Objects.requireNonNull(stage, "stage");
            require(profileVersionBefore, "profileVersionBefore");
            require(profileVersionAfter, "profileVersionAfter");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }

        public LedgerEntry recomputed() {
            return new LedgerEntry(candidateId, idempotencyKey, factKey, declaredValue,
                Stage.RECOMPUTED, profileVersionBefore, profileVersionAfter, recordedAt);
        }

        private static void require(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        }
    }
}
