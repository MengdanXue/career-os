package com.careeros;

import com.careeros.application.personal.JobWatchlistService;
import com.careeros.domain.DomainEnums.EligibilityStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/**
 * 关注清单。
 *
 * <p>读与写分开：GET 只读，从不推进"上次看到的结论"——光是刷新一下页面就把变化标记清掉，
 * 等于没有变化提示。推进要由用户显式确认，走 POST acknowledgements。
 *
 * <p>这里没有报名相关的动作。关注只是"我在盯着它"，系统不代替用户报名。
 */
@RestController
@RequestMapping("/api/v1/candidates/{candidateId}/watched-jobs")
class JobWatchlistController {
    private final JobWatchlistService watchlist;

    JobWatchlistController(JobWatchlistService watchlist) { this.watchlist = watchlist; }

    @GetMapping
    JobWatchlistService.Watchlist list(
        @PathVariable("candidateId") UUID candidateId,
        @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        return watchlist.list(candidateId, asOf);
    }

    /**
     * 关注一个岗位，并把用户此刻看到的结论记为比对基准。
     *
     * <p>不带基准也能关注（页面上看不到结论时），但那种情况下清单会标成"尚未建立基准"——
     * 没有基准就发现不了变化，而静默显示"无变化"是在替一个从没比对过的岗位下结论。
     */
    @PutMapping("/{jobId}")
    @Transactional
    WatchResponse watch(
        @PathVariable("candidateId") UUID candidateId,
        @PathVariable("jobId") UUID jobId,
        @RequestBody(required = false) WatchBody body
    ) {
        var watched = body == null
            ? watchlist.watch(candidateId, jobId)
            : watchlist.watch(candidateId, jobId, body.seenStatus(), body.seenEvaluatorVersion());
        return new WatchResponse(watched.jobPostingId(), watched.watchedAt().toString());
    }

    @DeleteMapping("/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void unwatch(@PathVariable("candidateId") UUID candidateId, @PathVariable("jobId") UUID jobId) {
        watchlist.unwatch(candidateId, jobId);
    }

    /**
     * 把某个岗位标记为已看过。
     *
     * <p>要带上用户看到的那个结论，而不是让服务端重新算一遍：中间若又变了，
     * 用当前值推进会把那次变化一起吞掉。
     */
    @PostMapping("/{jobId}/acknowledgements")
    @Transactional
    WatchResponse acknowledge(
        @PathVariable("candidateId") UUID candidateId,
        @PathVariable("jobId") UUID jobId,
        @RequestBody AcknowledgementBody body
    ) {
        var watched = watchlist.acknowledge(candidateId, jobId, body.seenStatus(), body.seenEvaluatorVersion());
        return new WatchResponse(watched.jobPostingId(),
            watched.lastSeenAt() == null ? null : watched.lastSeenAt().toString());
    }

    /** @param seenStatus 用户屏幕上看到的那个结论，不是此刻的当前值 */
    record AcknowledgementBody(EligibilityStatus seenStatus, String seenEvaluatorVersion) {}

    /** @param seenStatus 关注那一刻用户屏幕上的结论；为空表示该处看不到结论 */
    record WatchBody(EligibilityStatus seenStatus, String seenEvaluatorVersion) {}

    record WatchResponse(UUID jobId, String at) {}
}
