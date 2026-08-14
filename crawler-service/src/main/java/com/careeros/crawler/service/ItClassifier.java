package com.careeros.crawler.service;

import com.careeros.crawler.domain.NormalizedJob.Classification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ItClassifier {
    private static final Map<String, Double> STRONG_WEIGHTS = strongWeights();

    public Classification classify(String title, String responsibilities, String conditions) {
        String body = responsibilities + "\n" + conditions;
        String all = title + "\n" + body;
        Set<String> hits = new LinkedHashSet<>();
        double score = 0.0;

        for (Map.Entry<String, Double> entry : STRONG_WEIGHTS.entrySet()) {
            if (all.contains(entry.getKey())) {
                hits.add(entry.getKey());
                score += entry.getValue();
            }
        }

        if (isStrongTechnicalTitle(title)) {
            hits.add("技术岗位标题");
            score += 0.45;
        } else if (title.contains("信息中心")) {
            hits.add("信息中心");
            score += 0.35;
        }

        if (title.contains("信息化")) {
            hits.add("信息化");
            score += 0.35;
        }
        if (body.contains("信息化")) {
            hits.add("信息化");
            score += 0.15;
        }

        score = Math.min(0.99, round(score));
        boolean related = score >= 0.70;
        List<String> tags = tagsFor(hits);
        String reason;
        if (related) {
            reason = "岗位名称、职责或专业条件包含明确的信息技术开发、数据或运维证据。";
        } else if (score >= 0.40) {
            reason = "存在信息化弱相关证据，但不足以自动确认，进入人工复核。";
        } else {
            reason = "没有足够的信息技术岗位证据；通用办公软件能力不计为 IT 证据。";
        }
        return new Classification(related, score, List.copyOf(hits), tags, reason);
    }

    public boolean isCandidate(Classification classification) {
        return classification.score() >= 0.40;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Map<String, Double> strongWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("数据平台", 0.30);
        weights.put("信息系统", 0.35);
        weights.put("数据中台", 0.20);
        weights.put("AI知识库", 0.15);
        weights.put("数据库", 0.15);
        weights.put("人工智能", 0.15);
        weights.put("新一代信息技术", 0.30);
        weights.put("电子信息", 0.10);
        weights.put("网络安全", 0.15);
        weights.put("大数据", 0.12);
        weights.put("计算机科学与技术", 0.15);
        weights.put("软件工程", 0.15);
        weights.put("开发", 0.12);
        weights.put("运维", 0.12);
        return weights;
    }

    private static boolean isStrongTechnicalTitle(String title) {
        boolean technicalDomain = title.contains("系统") || title.contains("数据") || title.contains("人工智能")
                || title.contains("软件") || title.contains("网络") || title.contains("信息");
        boolean technicalRole = title.contains("工程师") || title.contains("开发") || title.contains("运维");
        return technicalDomain && technicalRole;
    }

    private static List<String> tagsFor(Set<String> hits) {
        List<String> tags = new ArrayList<>();
        if (containsAny(hits, "数据平台", "数据中台", "数据库", "大数据")) tags.add("数据工程");
        if (containsAny(hits, "开发", "软件工程")) tags.add("软件开发");
        if (containsAny(hits, "运维", "信息系统")) tags.add("平台运维");
        if (containsAny(hits, "AI知识库", "人工智能")) tags.add("AI应用");
        if (hits.contains("网络安全")) tags.add("网络安全");
        if (hits.contains("信息化") && tags.isEmpty()) tags.add("信息化管理");
        if (hits.contains("信息中心") && tags.isEmpty()) tags.add("信息技术支持");
        return List.copyOf(tags);
    }

    private static boolean containsAny(Set<String> values, String... candidates) {
        for (String candidate : candidates) {
            if (values.contains(candidate)) return true;
        }
        return false;
    }
}
