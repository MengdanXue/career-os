package com.careeros.agent.service;

import com.careeros.agent.domain.CandidateProfile;
import com.careeros.agent.domain.EligibilityAssessment;
import com.careeros.agent.domain.IncrementalWatchlist;
import com.careeros.agent.domain.IncrementalWatchlist.Action;
import com.careeros.agent.domain.IncrementalWatchlist.DeltaType;
import com.careeros.agent.domain.IncrementalWatchlist.Item;
import com.careeros.agent.domain.OpportunityTierAssessment;
import com.careeros.agent.domain.OpportunityTierAssessment.OpportunityTier;
import com.careeros.crawler.domain.JobDelta;
import com.careeros.crawler.domain.NormalizedJob;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class IncrementalWatchlistService {
    private final EligibilityEngine eligibility;
    private final OpportunityTierClassifier tiers;

    public IncrementalWatchlistService(EligibilityEngine eligibility, OpportunityTierClassifier tiers) {
        this.eligibility = eligibility;
        this.tiers = tiers;
    }

    public IncrementalWatchlist generate(JobDelta delta, List<NormalizedJob> jobs, CandidateProfile profile) {
        Map<String, NormalizedJob> byId = jobs.stream()
                .collect(Collectors.toMap(NormalizedJob::jobId, Function.identity()));
        List<Item> items = new ArrayList<>();
        delta.added().forEach(change -> items.add(currentItem(DeltaType.ADDED, change, byId, profile)));
        delta.changed().forEach(change -> items.add(currentItem(DeltaType.CHANGED, change, byId, profile)));
        delta.removed().forEach(change -> items.add(new Item(
                DeltaType.REMOVED, change.previousJobId(), change.employer(), change.positionTitle(),
                false, OpportunityTier.UNKNOWN, null, Action.REMOVED,
                "岗位已从当前来源下线；保留历史记录，不再作为新增机会推送"
        )));
        return new IncrementalWatchlist(
                delta.generatedAt(), delta.added().size(), delta.changed().size(), delta.removed().size(),
                delta.unchangedCount(), items
        );
    }

    private Item currentItem(
            DeltaType type, JobDelta.Change change, Map<String, NormalizedJob> byId, CandidateProfile profile
    ) {
        NormalizedJob job = byId.get(change.currentJobId());
        if (job == null) {
            return new Item(type, change.currentJobId(), change.employer(), change.positionTitle(),
                    false, OpportunityTier.UNKNOWN, null, Action.REVIEW,
                    "增量记录找不到对应的当前岗位，需要检查状态一致性");
        }
        OpportunityTierAssessment tier = tiers.classify(job);
        EligibilityAssessment assessment = eligibility.assess(profile, job);
        Action action = action(job, tier, assessment);
        String reason = tier.reason() + "；资格=" + assessment.status();
        return new Item(type, job.jobId(), job.employer().name(), job.position().title(),
                job.classification().isItRelated(), tier.tier(), assessment.status(), action, reason);
    }

    private Action action(
            NormalizedJob job, OpportunityTierAssessment tier, EligibilityAssessment assessment
    ) {
        if (!job.classification().isItRelated() || tier.tier() == OpportunityTier.EXCLUDED
                || assessment.status() == EligibilityAssessment.OverallStatus.INELIGIBLE) {
            return Action.REJECT;
        }
        if (tier.tier() == OpportunityTier.T1_ESTABLISHMENT_TARGET) {
            return assessment.status() == EligibilityAssessment.OverallStatus.ELIGIBLE
                    ? Action.APPLY : Action.VERIFY_FIRST;
        }
        if (tier.tier() == OpportunityTier.T3_STABLE_SOE_BACKUP) {
            return assessment.status() == EligibilityAssessment.OverallStatus.ELIGIBLE
                    ? Action.BACKUP : Action.VERIFY_FIRST;
        }
        if (tier.tier() == OpportunityTier.T2_IDENTITY_REVIEW) return Action.VERIFY_FIRST;
        return Action.REVIEW;
    }
}
