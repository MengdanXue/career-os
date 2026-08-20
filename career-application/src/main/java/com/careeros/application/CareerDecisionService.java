package com.careeros.application;

import com.careeros.domain.*;
import java.util.List;
import java.util.UUID;
import com.careeros.application.JobAdmissionPorts.JobAdmissions;

public final class CareerDecisionService {
    private final RepositoryPorts.JobPostings jobs;
    private final RepositoryPorts.Opportunities opportunities;
    private final JobAdmissions admissions;

    public CareerDecisionService(RepositoryPorts.JobPostings jobs, RepositoryPorts.Opportunities opportunities, JobAdmissions admissions) {
        this.jobs=jobs; this.opportunities=opportunities; this.admissions=admissions;
    }

    public List<Opportunity> visibleOpportunities() {
        return opportunities.findAll().stream().filter(value -> jobs.findById(value.jobPostingId())
            .map(this::isDecisionReady).orElse(false)).toList();
    }

    public void requireDecisionReady(UUID jobId) {
        var job=jobs.findById(jobId).orElseThrow(() -> new IllegalArgumentException("Job not found: "+jobId));
        requireDecisionReady(job);
    }

    private void requireDecisionReady(JobPosting job) {
        if (isDecisionReady(job)) return;
        throw new DecisionExceptions.JobNotAdmittedException(
            "Job has not passed evidence and employment identity admission: " + job.id());
    }

    private boolean isDecisionReady(JobPosting job) {
        return admissions.findByJobId(job.id()).map(value -> value.admits(job)).orElse(false);
    }

}
