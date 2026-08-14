package com.careeros.crawler.parser;

import java.nio.file.Path;
import java.util.List;

public final class SpreadsheetCatalog {
    private SpreadsheetCatalog() {}

    public static List<Entry> defaultEntries(Path samplesDirectory) {
        return List.of(
                new Entry(samplesDirectory.resolve("01-zj-dev-reform-2025-plan.xls"), new SpreadsheetSourceConfig(
                        "S01", "浙江省发展和改革委员会关于下属事业单位2025年公开招聘人员公告",
                        "https://rlsbt.zj.gov.cn/art/2025/5/23/art_1229743683_58941468.html",
                        "https://rlsbt.zj.gov.cn/api-gateway/jpaas-web-server/front/document/file-download?fileUrl=/cms_files/jcms1/web2758/site/attach/0/1a44c3c13347400196e0a9d9d2ac958d.xls",
                        "2025-05-23", "浙江省发展和改革委员会下属事业单位", "public_institution", "事业编制"
                )),
                new Entry(samplesDirectory.resolve("02-zj-rongjun-2025-plan.xls"), new SpreadsheetSourceConfig(
                        "S02", "浙江省荣军医院公开招聘人员公告（2025年第二批）",
                        "https://rlsbt.zj.gov.cn/art/2025/7/15/art_1229743683_58942062.html",
                        "https://rlsbt.zj.gov.cn/api-gateway/jpaas-web-server/front/document/file-download?fileUrl=/cms_files/jcms1/web2758/site/attach/0/3f37b1205442453d90f522063f2a4ada.xls",
                        "2025-07-15", "浙江省荣军医院", "hospital", "事业编制"
                )),
                new Entry(samplesDirectory.resolve("03-hz-unified-2025-plan.xls"), new SpreadsheetSourceConfig(
                        "S03", "2025年杭州市市属事业单位统一公开招聘工作人员公告",
                        "https://hrss.hangzhou.gov.cn/art/2025/3/18/art_1229782005_4338158.html",
                        "https://hrss.hangzhou.gov.cn/api-gateway/jpaas-web-server/front/document/file-download?fileUrl=/cms_files/jcms1/web3163/site/attach/0/2eea0b6af31f4e519fcf8b4c7c95e8ca.xls",
                        "2025-03-18", "杭州市市属事业单位", "public_institution", "事业编制"
                )),
                new Entry(samplesDirectory.resolve("04-hznu-2024-technical-plan.xls"), new SpreadsheetSourceConfig(
                        "S04", "杭州师范大学2024年公开招聘专业技术人员公告",
                        "https://hrss.hangzhou.gov.cn/art/2024/9/10/art_1229782005_4297785.html",
                        "https://hrss.hangzhou.gov.cn/api-gateway/jpaas-web-server/front/document/file-download?fileUrl=/cms_files/jcms1/web3163/site/attach/0/a4db6621742e4f9397441c40b2227149.xls",
                        "2024-09-10", "杭州师范大学", "university", "事业编制"
                )),
                new Entry(samplesDirectory.resolve("05-yuhang-health-2022-plan.xlsx"), new SpreadsheetSourceConfig(
                        "S05", "2022年杭州市余杭区卫生健康局部分事业单位第二批公开招聘编外工作人员公告",
                        "https://www.yuhang.gov.cn/art/2022/4/3/art_1229177228_4028645.html",
                        "https://www.yuhang.gov.cn/api-gateway/jpaas-web-server/front/document/file-download?fileUrl=/cms_files/jcms1/web3095/site/attach/0/4844327980e448aeba03568df62fb923.xlsx",
                        "2022-04-03", "杭州市余杭区卫生健康局所属事业单位", "hospital", "合同制/编外"
                )),
                new Entry(samplesDirectory.resolve("06-hdu-2026-second-plan.xlsx"), new SpreadsheetSourceConfig(
                        "S06", "杭州电子科技大学公开招聘人员公告（2026年第二批）",
                        "https://renshi.hdu.edu.cn/2026/0408/c13762a291030/page.htm",
                        "https://renshi.hdu.edu.cn/_upload/article/files/8d/92/dd7d93864e539409d45a5c0bd8e1/b5ecac07-8cd2-42fc-8005-ea50980852f2.xlsx",
                        "2026-04-08", "杭州电子科技大学", "university", "unknown"
                ))
        );
    }

    public record Entry(Path file, SpreadsheetSourceConfig config) {}
}
