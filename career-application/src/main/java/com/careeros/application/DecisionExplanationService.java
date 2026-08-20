package com.careeros.application;

import static com.careeros.application.DecisionPorts.DecisionBundle;
import static com.careeros.domain.DomainEnums.AssessmentFactStatus;

import com.careeros.domain.AssessmentDimension;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class DecisionExplanationService {
    public Explanation explain(DecisionBundle bundle) {
        var decision = bundle.decision();
        var warnings = new java.util.ArrayList<String>();
        if (decision.coveragePercent() < 100) warnings.add("证据覆盖率仅为 " + decision.coveragePercent() + "%：未知维度未计正向分");
        if (Stream.concat(bundle.fit().dimensions().stream(), bundle.stability().dimensions().stream()).anyMatch(d -> d.factStatus() == AssessmentFactStatus.UNKNOWN)) {
            warnings.add("存在未知评估维度，请核对公告附件或单位证据");
        }
        String text = "资格结论：" + decision.eligibilityStatus()
            + "；岗位层级：" + decision.tier()
            + "；匹配度 " + decision.fitScore() + "/100"
            + "；稳定性 " + decision.stabilityScore() + "/100。"
            + "该排序是机会决策指数，不是概率。";
        var evidence = new LinkedHashSet<UUID>();
        evidence.addAll(bundle.eligibility().evidenceIds());
        Stream.concat(bundle.fit().dimensions().stream(), bundle.stability().dimensions().stream())
            .map(AssessmentDimension::evidenceIds).forEach(evidence::addAll);
        return new Explanation(text, warnings, List.copyOf(evidence));
    }

    public record Explanation(String text, List<String> warnings, List<UUID> evidenceIds) {
        public Explanation { warnings = List.copyOf(warnings); evidenceIds = List.copyOf(evidenceIds); }
    }
}
