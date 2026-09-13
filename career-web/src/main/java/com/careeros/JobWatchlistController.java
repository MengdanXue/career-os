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

    @PutMapping("/{jobId}")
    @Transactional
    WatchResponse watch(@PathVariable("candidateId") UUID candidateId, @PathVariable("jobId") UUID jobId) {
        var watched = watchlist.watch(candidateId, jobId);
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

    record WatchResponse(UUID jobId, String at) {}
}
