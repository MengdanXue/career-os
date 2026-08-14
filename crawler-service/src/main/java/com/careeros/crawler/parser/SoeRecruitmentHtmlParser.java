package com.careeros.crawler.parser;

import com.careeros.crawler.domain.RecruitmentSourceScan;
import com.careeros.crawler.domain.RecruitmentSourceScan.Attachment;
import com.careeros.crawler.domain.RecruitmentSourceScan.Evidence;
import com.careeros.crawler.domain.RecruitmentSourceScan.Item;
import com.careeros.crawler.domain.RecruitmentSourceScan.Metrics;
import com.careeros.crawler.domain.RecruitmentSourceScan.OrganizationSection;
import com.careeros.crawler.domain.RecruitmentSourceScan.State;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SoeRecruitmentHtmlParser {
    public static final String SCHEMA_VERSION = "1.0.0";
    public static final String HZ_CAPITAL_CANONICAL = "https://hzzbco.com/joinRecruiting?current=1";
    public static final String HZFI_CANONICAL = "https://hr.hzfi.cn/jsp/recruit/socialRecruit.jsp";

    private static final Pattern HZ_CAPITAL_ID = Pattern.compile("newDet_(\\d+)_8");
    private static final Pattern ANNOUNCED_COUNTS = Pattern.compile("本次共推出\\s*(\\d+)\\s*个岗位[，,]?\\s*共计\\s*(\\d+)\\s*人");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:0\\d{2,3}[-—]?\\d{7,8}|1\\d{10})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final DateTimeFormatter CHINESE_DATE = DateTimeFormatter.ofPattern("yyyy年MM月dd日");

    public RecruitmentSourceScan parseHzCapital(
            String html, String requestedUrl, String effectiveUrl, OffsetDateTime scannedAt, String sha256
    ) {
        Document document = Jsoup.parse(html, effectiveUrl);
        List<Item> items = new ArrayList<>();
        for (Element card : document.select("#pd1Data .li")) {
            Element link = card.selectFirst("a[href*='newDet_']");
            Element title = card.selectFirst(".ar span");
            if (link == null || title == null || title.text().isBlank()) continue;
            String detailUrl = resolve(effectiveUrl, link.attr("href"));
            items.add(new Item(
                    extractId(detailUrl), "announcement", title.text().trim(), cardDate(card), detailUrl,
                    null, null, List.of()
            ));
        }

        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("page_heading", ".tt", document.select(".tt:containsOwn(招聘公示公告)").size(),
                "招聘公示公告栏目存在"));
        evidence.add(new Evidence("announcement_card", "#pd1Data .li .ar span", items.size(),
                "仅统计标题非空且具有详情链接的公告卡片"));

        List<String> warnings = new ArrayList<>();
        if (!samePageOne(requestedUrl)) {
            warnings.add("请求 URL 不是规范第一页；分页页码可能过期，不能据此判断整个来源为空");
        }
        State state = items.isEmpty() ? State.EMPTY_INFERRED : State.CONTENT_FOUND;
        if (state == State.EMPTY_INFERRED) {
            warnings.add("页面没有明确空状态文案；结论仅表示本次请求未发现有效公告卡片");
        }
        return new RecruitmentSourceScan(
                SCHEMA_VERSION, "S09", "杭州市国有资本投资运营有限公司", requestedUrl,
                HZ_CAPITAL_CANONICAL, effectiveUrl, scannedAt, state, document.title(), sha256,
                new Metrics(items.size(), 0, 0, 0, 0), items, List.of(), evidence, warnings
        );
    }

    public RecruitmentSourceScan enrichHzCapitalDetails(
            RecruitmentSourceScan scan, List<AnnouncementDetail> details
    ) {
        List<Item> enriched = scan.items().stream().map(item -> details.stream()
                .filter(detail -> detail.itemId().equals(item.itemId()))
                .findFirst()
                .map(detail -> new Item(
                        item.itemId(), item.itemType(), detail.title(), detail.publishedAt(), item.detailUrl(),
                        detail.announcedPositionCount(), detail.announcedHeadcount(), detail.attachments()
                ))
                .orElse(item)).toList();
        int attachments = enriched.stream().mapToInt(item -> item.attachments().size()).sum();
        Metrics metrics = new Metrics(enriched.size(), scan.metrics().organizationCount(),
                scan.metrics().jobSectionCount(), scan.metrics().explicitEmptySectionCount(), attachments);
        return new RecruitmentSourceScan(
                scan.schemaVersion(), scan.sourceId(), scan.sourceName(), scan.requestedUrl(), scan.canonicalUrl(),
                scan.effectiveUrl(), scan.scannedAt(), scan.state(), scan.pageTitle(), scan.sourceSha256(), metrics,
                enriched, scan.organizations(), scan.evidence(), scan.warnings()
        );
    }

    public AnnouncementDetail parseHzCapitalDetail(String html, String detailUrl) {
        Document document = Jsoup.parse(html, detailUrl);
        String title = text(document.selectFirst(".new_det .con1"));
        String publishedAt = parseChineseDate(text(document.selectFirst(".new_det .con2 .arial")));
        String content = Optional.ofNullable(document.selectFirst(".new_det2 .nd2_con > .con1"))
                .map(Element::text).orElse("");
        Matcher countMatcher = ANNOUNCED_COUNTS.matcher(content);
        Integer positions = null;
        Integer headcount = null;
        if (countMatcher.find()) {
            positions = Integer.valueOf(countMatcher.group(1));
            headcount = Integer.valueOf(countMatcher.group(2));
        }
        List<Attachment> attachments = document.select(".new_det2 a[download][href]").stream()
                .map(link -> new Attachment(link.text().trim(), resolve(detailUrl, link.attr("href")), extension(link.attr("href"))))
                .toList();
        return new AnnouncementDetail(extractId(detailUrl), title, publishedAt, positions, headcount, attachments);
    }

    public RecruitmentSourceScan parseHzfi(
            String html, String requestedUrl, String effectiveUrl, OffsetDateTime scannedAt, String sha256
    ) {
        Document document = Jsoup.parse(html, effectiveUrl);
        List<OrganizationSection> organizations = new ArrayList<>();
        int emptySections = 0;
        int jobSections = 0;

        for (Element company : document.select(".remodel")) {
            String name = text(company.selectFirst(".title"));
            if (name.isBlank()) continue;
            Element post = nextSiblingWithClass(company, "postDiv");
            if (post == null) continue;
            jobSections++;
            boolean empty = post.text().trim().equals("暂无岗位");
            if (empty) emptySections++;
            String companyText = company.text();
            organizations.add(new OrganizationSection(
                    name, company.id().isBlank() ? null : company.id(),
                    empty ? State.EMPTY_CONFIRMED : State.CONTENT_FOUND,
                    labelledValue(companyText, "公司地址：", "工作地点："),
                    firstMatch(PHONE, companyText), firstMatch(EMAIL, companyText)
            ));
        }

        int contentSections = jobSections - emptySections;
        State state;
        if (jobSections > 0 && emptySections == jobSections) state = State.EMPTY_CONFIRMED;
        else if (contentSections > 0) state = State.CONTENT_FOUND;
        else state = State.EMPTY_INFERRED;

        List<Evidence> evidence = List.of(
                new Evidence("job_section", ".colre .recruit", document.select(".colre .recruit:containsOwn(招聘岗位)").size(),
                        "集团页面中的招聘岗位区块"),
                new Evidence("explicit_empty", ".postDiv", emptySections,
                        "岗位容器的规范化文本严格等于“暂无岗位”")
        );
        List<String> warnings = new ArrayList<>();
        if (state == State.EMPTY_CONFIRMED) {
            warnings.add("页面脚本错误不覆盖业务空状态：所有岗位区块均明确显示暂无岗位");
        } else if (state == State.EMPTY_INFERRED) {
            warnings.add("没有识别到完整的企业/岗位区块，需浏览器复核 DOM 是否变化");
        }
        return new RecruitmentSourceScan(
                SCHEMA_VERSION, "S10", "杭州市金融投资集团有限公司", requestedUrl, HZFI_CANONICAL,
                effectiveUrl, scannedAt, state, document.title(), sha256,
                new Metrics(0, organizations.size(), jobSections, emptySections, 0),
                List.of(), organizations, evidence, warnings
        );
    }

    private static Element nextSiblingWithClass(Element start, String className) {
        Element cursor = start.nextElementSibling();
        while (cursor != null) {
            if (cursor.hasClass(className)) return cursor;
            if (cursor.hasClass("remodel")) return null;
            cursor = cursor.nextElementSibling();
        }
        return null;
    }

    private static String cardDate(Element card) {
        String yearMonth = text(card.selectFirst(".l2"));
        String day = text(card.selectFirst(".l1"));
        if (yearMonth.matches("\\d{4}-\\d{2}") && day.matches("\\d{1,2}")) {
            return yearMonth + "-" + String.format(Locale.ROOT, "%02d", Integer.parseInt(day));
        }
        return null;
    }

    private static String parseChineseDate(String value) {
        if (value.isBlank()) return null;
        return LocalDate.parse(value, CHINESE_DATE).toString();
    }

    private static String extractId(String url) {
        Matcher matcher = HZ_CAPITAL_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : url;
    }

    private static String extension(String url) {
        String path = URI.create(url).getPath();
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "unknown" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean samePageOne(String url) {
        return url.matches(".*(?:[?&]current=1(?:[&#].*)?|/joinRecruiting/?(?:#.*)?)$");
    }

    private static String resolve(String baseUrl, String href) {
        return URI.create(baseUrl).resolve(href).toASCIIString();
    }

    private static String text(Element element) {
        return element == null ? "" : element.text().trim();
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static String labelledValue(String text, String... labels) {
        for (String label : labels) {
            int start = text.indexOf(label);
            if (start < 0) continue;
            String rest = text.substring(start + label.length()).trim();
            int stop = rest.length();
            for (String next : List.of("联系电话：", "联系方式：", "联系邮箱：", "公司邮箱：", "公司网址：")) {
                int index = rest.indexOf(next);
                if (index >= 0) stop = Math.min(stop, index);
            }
            return rest.substring(0, stop).trim();
        }
        return null;
    }

    public record AnnouncementDetail(
            String itemId,
            String title,
            String publishedAt,
            Integer announcedPositionCount,
            Integer announcedHeadcount,
            List<Attachment> attachments
    ) {}
}
