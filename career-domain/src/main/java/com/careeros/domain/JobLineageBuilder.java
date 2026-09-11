package com.careeros.domain;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 把跨年度的岗位归并成岗位族（产品需求 §5：同一单位或同类岗位跨年度出现记录）。
 *
 * <p>归并键 = 单位名 + 技术族 + 岗位名称，全部归一化后哈希。这个键**刻意保守**：岗位名称在
 * 年度之间稍有改动就会分成两条族谱。方向是有意选的——归并不足只会让再现信号偏弱（落到
 * SINGLE_OCCURRENCE 或 INSUFFICIENT_HISTORY），归并过度则会凭空造出"连续招聘"的假趋势。
 * 决策系统宁可少报机会，不可多报。
 */
public final class JobLineageBuilder {
    public static final String VERSION = "phase2-lineage-v1";

    /** 建族所需的一条输入：岗位本身，加上它所属招聘事件的年度与单位名。 */
    public record LineageInput(JobPosting job, int recruitmentYear, String organizationName) {
        public LineageInput {
            Objects.requireNonNull(job, "job");
            if (recruitmentYear < 2000 || recruitmentYear > 2100) {
                throw new IllegalArgumentException("recruitmentYear is invalid: " + recruitmentYear);
            }
            if (organizationName == null || organizationName.isBlank()) {
                throw new IllegalArgumentException("organizationName is required");
            }
        }
    }

    public List<JobFamilyLineage> build(List<LineageInput> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        Map<String, List<LineageInput>> grouped = new LinkedHashMap<>();
        for (LineageInput input : inputs) {
            grouped.computeIfAbsent(lineageKey(input), key -> new ArrayList<>()).add(input);
        }
        return grouped.entrySet().stream()
            .map(entry -> toLineage(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(JobFamilyLineage::lineageKey))
            .toList();
    }

    public String lineageKey(LineageInput input) {
        Objects.requireNonNull(input, "input");
        return sha256(String.join("|",
            normalize(input.organizationName()),
            input.job().jobFamily().name(),
            normalize(input.job().title())));
    }

    private static JobFamilyLineage toLineage(String lineageKey, List<LineageInput> members) {
        // 用最近一年的岗位作为代表，名称与技术族以它为准。
        LineageInput representative = members.stream()
            .max(Comparator.comparingInt(LineageInput::recruitmentYear))
            .orElseThrow();
        var years = new TreeSet<Integer>();
        var jobIds = new ArrayList<UUID>();
        var evidence = new ArrayList<UUID>();
        for (LineageInput member : members) {
            years.add(member.recruitmentYear());
            jobIds.add(member.job().id());
            for (UUID evidenceId : member.job().evidenceIds()) {
                if (!evidence.contains(evidenceId)) evidence.add(evidenceId);
            }
        }
        return new JobFamilyLineage(
            lineageKey, representative.job().organizationId(), representative.organizationName(),
            representative.job().title(), representative.job().jobFamily(), years, jobIds, evidence);
    }

    /** 与资格判定同一套归一化口径：去空白、去常见标点、小写。 */
    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s·（）()_\\-—、,，.。]", "");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
