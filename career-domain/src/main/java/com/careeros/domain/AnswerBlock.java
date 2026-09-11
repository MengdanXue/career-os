package com.careeros.domain;

import com.careeros.domain.AnswerFact.FactField;
import com.careeros.domain.AnswerFact.FactUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 回答的核心事实块：由程序从类型化事实确定性渲染，模型不参与。
 *
 * <p>资格、分数、限制条件和证据全部在这里生成。模型拿到的是已经渲染好的文本，
 * 它既不能改写其中的数值，也不能省略其中的限制条件——因为最终回答必须逐字包含本块，
 * 这一点由 {@link AnswerNarrativeValidator} 校验。
 *
 * <p>过期引用不是靠事后检查发现的：构造时就按当前评估器版本标出，渲染时明确写成
 * "基于已过期的评估"，而不是当作当前结论呈现。
 */
public record AnswerBlock(List<JobFacts> jobs, String evaluatorVersion, String disclaimer) {

    /** 覆盖率、适配分、稳定性分都是决策指数，不是录取概率。这句随块一起渲染，不可省略。 */
    public static final String DEFAULT_DISCLAIMER =
        "以上为机会决策指数与证据覆盖率，不是录取概率，也不代表官方核实结论。";

    public AnswerBlock {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
        if (evaluatorVersion == null || evaluatorVersion.isBlank()) {
            throw new IllegalArgumentException("evaluatorVersion is required");
        }
        disclaimer = disclaimer == null || disclaimer.isBlank() ? DEFAULT_DISCLAIMER : disclaimer;
    }

    public static AnswerBlock of(List<JobFacts> jobs, String evaluatorVersion) {
        return new AnswerBlock(jobs, evaluatorVersion, DEFAULT_DISCLAIMER);
    }

    public boolean isEmpty() { return jobs.isEmpty(); }

    /** 块内出现过的全部事实，供校验器判断叙述是否引入了块外的事实。 */
    public List<AnswerFact> facts() {
        return jobs.stream().flatMap(job -> job.facts().stream()).toList();
    }

    /**
     * 确定性渲染。同样的事实永远渲染成同样的文本——校验器据此做逐字包含检查，
     * 所以这里不能有随机顺序、时间戳或任何非确定性内容。
     */
    public String render() {
        if (jobs.isEmpty()) return "当前没有符合条件的岗位。\n\n" + disclaimer;
        var text = new StringBuilder();
        for (int index = 0; index < jobs.size(); index++) {
            if (index > 0) text.append('\n');
            text.append(jobs.get(index).render(index + 1, evaluatorVersion));
        }
        text.append("\n\n").append(disclaimer);
        return text.toString();
    }

    /** 一个岗位的全部事实。 */
    public record JobFacts(UUID jobPostingId, String jobTitle, String organizationName, List<AnswerFact> facts) {
        public JobFacts {
            Objects.requireNonNull(jobPostingId, "jobPostingId");
            if (jobTitle == null || jobTitle.isBlank()) throw new IllegalArgumentException("jobTitle is required");
            facts = facts == null ? List.of() : List.copyOf(facts);
            for (AnswerFact fact : facts) {
                if (!fact.jobPostingId().equals(jobPostingId)) {
                    // 跨岗位错配在这一层就被挡住：一条属于别的岗位的事实进不了这个岗位的块。
                    throw new IllegalArgumentException(
                        "fact belongs to job " + fact.jobPostingId() + " but was grouped under " + jobPostingId);
                }
            }
        }

        String render(int ordinal, String currentEvaluatorVersion) {
            var text = new StringBuilder();
            text.append(ordinal).append(". ").append(jobTitle);
            if (organizationName != null && !organizationName.isBlank()) {
                text.append("（").append(organizationName).append("）");
            }
            var byField = new LinkedHashMap<FactField, AnswerFact>();
            facts.forEach(fact -> byField.putIfAbsent(fact.field(), fact));
            var restrictions = new ArrayList<AnswerFact>();
            boolean stale = false;
            for (Map.Entry<FactField, AnswerFact> entry : byField.entrySet()) {
                AnswerFact fact = entry.getValue();
                if (fact.staleAgainst(currentEvaluatorVersion)) stale = true;
                if (fact.field() == FactField.RESTRICTION) continue;
                text.append('\n').append("   - ").append(fact.field().label()).append("：").append(renderValue(fact));
                if (fact.applicableAsOf() != null) {
                    text.append("（适用至 ").append(fact.applicableAsOf()).append("）");
                }
                if (!fact.evidenceIds().isEmpty()) {
                    text.append("［证据 ").append(fact.evidenceIds().size()).append(" 处］");
                }
            }
            // 限制条件全部列出，一条不省。模型无权删减它们。
            facts.stream().filter(fact -> fact.field() == FactField.RESTRICTION).forEach(restrictions::add);
            for (AnswerFact restriction : restrictions) {
                text.append('\n').append("   - ").append(FactField.RESTRICTION.label()).append("：")
                    .append(restriction.value());
                if (!restriction.evidenceIds().isEmpty()) {
                    text.append("［证据 ").append(restriction.evidenceIds().size()).append(" 处］");
                }
            }
            if (stale) {
                text.append('\n').append("   - 注意：以上基于已过期的评估版本，需重新评估后才能作为当前结论。");
            }
            return text.toString();
        }

        private static String renderValue(AnswerFact fact) {
            return switch (fact.unit()) {
                case POINTS -> fact.value() + " 分";
                case PERCENT -> fact.value() + "%";
                case ENUM -> enumLabel(fact.value());
                case DATE, TEXT -> fact.value();
            };
        }

        private static String enumLabel(String value) {
            return switch (value) {
                case "ELIGIBLE" -> "可报";
                case "CONDITIONAL" -> "条件可报";
                case "NEEDS_CONFIRMATION" -> "待确认";
                case "CONFLICTING_EVIDENCE" -> "证据冲突";
                case "INELIGIBLE" -> "不可报";
                case "RECOMMENDED" -> "建议关注";
                case "REVIEW" -> "需人工复核";
                case "NOT_RECOMMENDED" -> "暂不建议";
                case "EXCLUDED" -> "已排除";
                default -> value;
            };
        }
    }
}
