package com.careeros.infrastructure.persistence;

import com.careeros.application.JobUpsertService;
import com.careeros.application.JobUpsertService.JobUpsertBatch;
import com.careeros.application.JobUpsertService.NormalizedJob;
import com.careeros.domain.DomainEnums.*;
import java.io.InputStream;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficialExcelImportService {
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern YEAR = Pattern.compile("(20\\d{2})");
    /** 出生日期/公告日期片段。先剔除，避免把 1990、2025 当成年龄或工作年限。 */
    private static final Pattern DATE_FRAGMENT = Pattern.compile("\\d{4}\\s*年(\\s*\\d{1,2}\\s*月)?(\\s*\\d{1,2}\\s*日)?");
    private static final Pattern BIRTH_DATE_LIMIT = Pattern.compile("\\d{4}\\s*年[^，。;；]{0,12}(出生|以后出生|之后出生)");
    private static final Pattern AGE_UPPER_PREFIXED = Pattern.compile("(?:不超过|不得超过|未满|不满|最高|最大|放宽至|放宽到)\\s*(\\d{1,3})\\s*(?:周)?岁");
    private static final Pattern AGE_UPPER_SUFFIXED = Pattern.compile("(\\d{1,3})\\s*(?:周)?岁\\s*(?:及)?(?:以下|以内)");
    private static final Pattern AGE_LOWER_SUFFIXED = Pattern.compile("(\\d{1,3})\\s*(?:周)?岁\\s*(?:及)?以上");
    private static final Pattern AGE_RANGE = Pattern.compile("(\\d{1,3})\\s*(?:周)?岁?\\s*(?:至|到|-|—|~|－)\\s*(\\d{1,3})\\s*(?:周)?岁");
    private static final Pattern AGE_ANY = Pattern.compile("(\\d{1,3})\\s*(?:周)?岁");
    private static final Pattern EXPERIENCE_YEARS = Pattern.compile("(?<![\\d])(\\d{1,2})\\s*(?:周)?年");
    private static final Pattern NO_EXPERIENCE_REQUIRED = Pattern.compile("不限|无要求|无限制|不作要求|不做要求|应届|无经验|经历不限");
    private static final int MINIMUM_PLAUSIBLE_AGE = 16;
    private static final int MAXIMUM_PLAUSIBLE_AGE = 70;
    private static final int MAXIMUM_PLAUSIBLE_EXPERIENCE = 50;
    private final RecruitmentEventJpaRepository events;
    private final OrganizationJpaRepository organizations;
    private final JobUpsertService upserts;

    public OfficialExcelImportService(RecruitmentEventJpaRepository events,OrganizationJpaRepository organizations,JobUpsertService upserts){this.events=events;this.organizations=organizations;this.upserts=upserts;}

    @Transactional
    public ImportResult importWorkbook(InputStream input,ImportCommand command) throws Exception {
        var event=events.findFirstBySourceUrl(command.sourceUrl()).orElseGet(()->createEvent(command));
        int recognizedSheets=0; var errors=new ArrayList<RowError>(); var warnings=new ArrayList<RowWarning>(); var seen=new HashSet<String>(); var normalizedJobs=new ArrayList<NormalizedJob>();
        try(var workbook=WorkbookFactory.create(input)){
            var formatter=new DataFormatter(Locale.ROOT);
            for(int sheetIndex=0;sheetIndex<workbook.getNumberOfSheets();sheetIndex++){
                var sheet=workbook.getSheetAt(sheetIndex); var header=findHeader(sheet,formatter,!blank(command.defaultOrganizationName()));
                if(header==null) continue;
                recognizedSheets++;
                String previousOrganization=null;
                for(int rowIndex=header.rowIndex()+1;rowIndex<=sheet.getLastRowNum();rowIndex++){
                    var row=sheet.getRow(rowIndex); if(row==null) continue;
                    try{
                        String title=value(row,header.columns(),formatter,"岗位名称","招聘岗位","岗位");
                        if(blank(title)) continue;
                        String organizationName=value(row,header.columns(),formatter,"招聘单位","单位名称","用人单位","招聘主体");
                        if(blank(organizationName)) organizationName=previousOrganization; else previousOrganization=organizationName;
                        if(blank(organizationName)) organizationName=command.defaultOrganizationName();
                        if(blank(organizationName)) throw new IllegalArgumentException("招聘单位为空");
                        var organization=findOrCreateOrganization(organizationName,command.defaultLocation(),command.eventType());
                        String code=value(row,header.columns(),formatter,"岗位代码","岗位编号","职位代码","岗位序号","序号");
                        String educationText=value(row,header.columns(),formatter,"学历","学历要求","最低学历");
                        String majorText=value(row,header.columns(),formatter,"专业","专业要求","所学专业");
                        String ageText=value(row,header.columns(),formatter,"年龄","年龄要求");
                        String experienceText=value(row,header.columns(),formatter,"工作经历","工作经验","工作年限","相关经历");
                        String applicantText=value(row,header.columns(),formatter,"招聘对象","人员范围","对象范围");
                        String employmentText=value(row,header.columns(),formatter,"用工性质","编制性质","岗位性质","聘用形式");
                        String headcountText=value(row,header.columns(),formatter,"招聘人数","人数","计划人数");
                        String duties=value(row,header.columns(),formatter,"岗位职责","主要职责","工作内容","其他条件");
                        String location=value(row,header.columns(),formatter,"工作地点","地区","所在地"); if(blank(location)) location=command.defaultLocation();
                        var age=ageLimit(ageText); var experience=experienceYears(experienceText);
                        int reportedRow=rowIndex+1;
                        if(age.warning()!=null) warnings.add(new RowWarning(sheet.getSheetName(),reportedRow,"年龄",ageText,age.warning()));
                        if(experience.warning()!=null) warnings.add(new RowWarning(sheet.getSheetName(),reportedRow,"工作经历",experienceText,experience.warning()));
                        var normalized=new NormalizedJob(
                            event.id,organization.id,organizationName,emptyToNull(code),title,jobFamily(title,duties),
                            employmentType(employmentText),location,Math.max(1,integer(headcountText,1)),education(educationText),
                            splitMajors(majorText),graduationYears(applicantText),age.value(),command.ageReferenceDate(),
                            experience.value(),new LinkedHashSet<>(),duties,command.sourceUrl(),List.of());
                        String stableKey=upserts.stableKey(normalized);
                        if(!seen.add(stableKey)) throw new IllegalArgumentException("同一文件出现重复稳定岗位键");
                        normalizedJobs.add(normalized);
                    }catch(Exception exception){errors.add(new RowError(sheet.getSheetName(),rowIndex+1,exception.getMessage()));}
                }
            }
        }
        if(recognizedSheets==0) throw new IllegalArgumentException("未找到同时包含招聘单位和岗位名称的表头，已拒绝导入");
        var result=upserts.upsert(new JobUpsertBatch(
            event.id,command.sourceUrl(),normalizedJobs,true,
            errors.stream().map(error->error.sheet()+":"+error.row()+":"+error.message()).toList()));
        return new ImportResult(event.id,result.inserted(),result.updated(),result.unchanged(),result.deactivated(),List.copyOf(errors),List.copyOf(warnings));
    }

    private JpaModels.RecruitmentEventEntity createEvent(ImportCommand c){var e=new JpaModels.RecruitmentEventEntity();e.id=UUID.randomUUID();e.title=c.announcementTitle();e.recruitmentYear=c.recruitmentYear();e.eventType=c.eventType();e.publishedOn=c.publishedOn();e.sourceUrl=c.sourceUrl();e.defaultEmploymentType=EmploymentType.UNKNOWN;e.evidenceIds=new ArrayList<>();return events.save(e);}
    private JpaModels.OrganizationEntity findOrCreateOrganization(String name,String location,EventType eventType){return organizations.findFirstByName(name).orElseGet(()->{var e=new JpaModels.OrganizationEntity();e.id=UUID.randomUUID();e.name=name;e.organizationType=organizationType(name,eventType);e.province=location!=null&&location.contains("浙江")?"浙江":null;e.city=location!=null&&location.contains("杭州")?"杭州":null;return organizations.save(e);});}

    private Header findHeader(Sheet sheet,DataFormatter formatter,boolean allowsDefaultOrganization){for(int r=sheet.getFirstRowNum();r<=Math.min(sheet.getLastRowNum(),30);r++){var row=sheet.getRow(r);if(row==null)continue;var map=new HashMap<String,Integer>();for(Cell cell:row){String text=normalizeHeader(formatter.formatCellValue(cell));if(!text.isBlank())map.put(text,cell.getColumnIndex());}boolean hasJob=containsAny(map,"岗位名称","招聘岗位","岗位");boolean hasOrganization=containsAny(map,"招聘单位","单位名称","用人单位","招聘主体");if(hasJob&&(hasOrganization||allowsDefaultOrganization))return new Header(r,map);}return null;}
    private boolean containsAny(Map<String,Integer> map,String...aliases){return Arrays.stream(aliases).map(OfficialExcelImportService::normalizeHeader).anyMatch(map::containsKey);}
    private String value(Row row,Map<String,Integer> columns,DataFormatter f,String...aliases){for(String alias:aliases){var column=columns.get(normalizeHeader(alias));if(column!=null){var cell=row.getCell(column,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);if(cell!=null){var value=f.formatCellValue(cell).trim();if(!value.isBlank())return value;}}}return null;}
    private static String normalizeHeader(String value){return value==null?"":value.replaceAll("[\\s\\n\\r：:（）()]","").trim();}
    private static Set<String> splitMajors(String text){if(blank(text))return new LinkedHashSet<>();var result=new LinkedHashSet<String>();for(String part:text.split("[、,，;；/\\n]")){var value=part.trim();if(!value.isBlank())result.add(value);}return result;}
    private static Set<Integer> graduationYears(String text){var result=new LinkedHashSet<Integer>();if(text!=null){var m=YEAR.matcher(text);while(m.find())result.add(Integer.parseInt(m.group(1)));}return result;}
    private static Integer integerOrNull(String text){if(blank(text))return null;var m=NUMBER.matcher(text);return m.find()?Integer.valueOf(m.group(1)):null;}

    /**
     * 解析岗位年龄上限。只接受带明确上限语义的表述，识别不了就返回空值并附带警告，
     * 绝不退化成"取第一个数字"——否则"1990年1月1日以后出生，年龄不超过35周岁"会得到 1990。
     */
    static FieldParse<Integer> ageLimit(String text) {
        if (blank(text)) return FieldParse.empty();
        String scrubbed = DATE_FRAGMENT.matcher(text).replaceAll(" ");
        if (!scrubbed.contains("岁")) {
            return BIRTH_DATE_LIMIT.matcher(text).find()
                ? FieldParse.unparsed("年龄以出生日期表述，无法确定年龄上限")
                : FieldParse.empty();
        }
        Integer prefixed = firstPlausibleAge(AGE_UPPER_PREFIXED, scrubbed, 1);
        if (prefixed != null) return FieldParse.of(prefixed);
        Integer suffixed = lastPlausibleAge(AGE_UPPER_SUFFIXED, scrubbed, 1);
        if (suffixed != null) return FieldParse.of(suffixed);
        Integer rangeUpper = lastPlausibleAge(AGE_RANGE, scrubbed, 2);
        if (rangeUpper != null) return FieldParse.of(rangeUpper);
        List<Integer> ages = plausibleAges(AGE_ANY, scrubbed, 1);
        boolean statesLowerBoundOnly = AGE_LOWER_SUFFIXED.matcher(scrubbed).find();
        if (ages.size() == 1 && !statesLowerBoundOnly) return FieldParse.of(ages.getFirst());
        return FieldParse.unparsed("无法确定年龄上限，识别到年龄候选值 " + ages);
    }

    /**
     * 解析最低工作年限。先剔除日期片段，再要求数字紧邻"年"，
     * 否则"2025年应届毕业生"会得到 2025 并把所有候选人判成不合格。
     */
    static FieldParse<Integer> experienceYears(String text) {
        if (blank(text)) return FieldParse.empty();
        String scrubbed = DATE_FRAGMENT.matcher(text).replaceAll(" ");
        if (NO_EXPERIENCE_REQUIRED.matcher(scrubbed).find()) return FieldParse.empty();
        List<Integer> values = new ArrayList<>();
        var matcher = EXPERIENCE_YEARS.matcher(scrubbed);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value >= 1 && value <= MAXIMUM_PLAUSIBLE_EXPERIENCE && !values.contains(value)) values.add(value);
        }
        if (values.size() == 1) return FieldParse.of(values.getFirst());
        if (values.isEmpty()) {
            return scrubbed.contains("年")
                ? FieldParse.unparsed("提及年份但无法确定最低工作年限")
                : FieldParse.empty();
        }
        return FieldParse.unparsed("识别到多个工作年限候选值 " + values);
    }

    private static List<Integer> plausibleAges(Pattern pattern, String text, int group) {
        List<Integer> ages = new ArrayList<>();
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            int age = Integer.parseInt(matcher.group(group));
            if (age >= MINIMUM_PLAUSIBLE_AGE && age <= MAXIMUM_PLAUSIBLE_AGE && !ages.contains(age)) ages.add(age);
        }
        return ages;
    }

    private static Integer firstPlausibleAge(Pattern pattern, String text, int group) {
        var ages = plausibleAges(pattern, text, group);
        return ages.isEmpty() ? null : ages.getFirst();
    }

    private static Integer lastPlausibleAge(Pattern pattern, String text, int group) {
        var ages = plausibleAges(pattern, text, group);
        return ages.isEmpty() ? null : ages.getLast();
    }
    private static int integer(String text,int fallback){var value=integerOrNull(text);return value==null?fallback:value;}
    private static EducationLevel education(String text){if(text!=null&&(text.contains("博士")))return EducationLevel.DOCTORATE;if(text!=null&&(text.contains("硕士")||text.contains("研究生")))return EducationLevel.MASTER;if(text!=null&&text.contains("本科"))return EducationLevel.BACHELOR;if(text!=null&&(text.contains("专科")||text.contains("大专")))return EducationLevel.ASSOCIATE;return EducationLevel.UNKNOWN;}
    private static EmploymentType employmentType(String text){if(blank(text))return EmploymentType.UNKNOWN;if(text.contains("劳务派遣"))return EmploymentType.LABOR_DISPATCH;if(text.contains("人事代理"))return EmploymentType.PERSONNEL_AGENCY;if(text.contains("项目"))return EmploymentType.PROJECT_BASED;if(text.contains("合同"))return EmploymentType.CONTRACT;if(text.contains("事业编")||text.contains("编制内"))return EmploymentType.ESTABLISHMENT;return EmploymentType.UNKNOWN;}
    private static JobFamily jobFamily(String title,String duties){String text=nvl(title)+nvl(duties);if(text.contains("人工智能")||text.contains("算法"))return JobFamily.AI;if(text.contains("数据"))return JobFamily.DATA;if(text.contains("安全")||text.contains("网络安全"))return JobFamily.CYBERSECURITY;if(text.contains("软件")||text.contains("开发"))return JobFamily.SOFTWARE;if(text.contains("信息中心")||text.contains("系统"))return JobFamily.INFORMATION_SYSTEMS;if(text.contains("数字"))return JobFamily.DIGITALIZATION;if(text.contains("运维")||text.contains("网络"))return JobFamily.IT_OPERATIONS;if(text.contains("研究"))return JobFamily.RESEARCH;return JobFamily.OTHER;}
    private static OrganizationType organizationType(String name,EventType eventType){if(name.contains("医院"))return OrganizationType.HOSPITAL;if(name.contains("大学")||name.contains("学院")||name.contains("学校"))return OrganizationType.UNIVERSITY;if(eventType==EventType.STATE_OWNED_ENTERPRISE)return OrganizationType.STATE_OWNED_ENTERPRISE;if(eventType==EventType.UNIVERSITY)return OrganizationType.UNIVERSITY;if(eventType==EventType.HOSPITAL)return OrganizationType.HOSPITAL;return eventType==EventType.PUBLIC_INSTITUTION?OrganizationType.PUBLIC_INSTITUTION:OrganizationType.OTHER;}
    private static boolean blank(String value){return value==null||value.isBlank();} private static String nvl(String value){return value==null?"":value;} private static String emptyToNull(String value){return blank(value)?null:value;}

    private record Header(int rowIndex,Map<String,Integer> columns){}
    public record ImportCommand(String announcementTitle,String sourceUrl,int recruitmentYear,LocalDate publishedOn,LocalDate ageReferenceDate,String defaultLocation,EventType eventType,String defaultOrganizationName){
        public ImportCommand(String announcementTitle,String sourceUrl,int recruitmentYear,LocalDate publishedOn,LocalDate ageReferenceDate,String defaultLocation,EventType eventType){this(announcementTitle,sourceUrl,recruitmentYear,publishedOn,ageReferenceDate,defaultLocation,eventType,null);}
        public ImportCommand{if(blank(announcementTitle)||blank(sourceUrl))throw new IllegalArgumentException("announcementTitle and sourceUrl are required");if(eventType==null)eventType=EventType.PUBLIC_INSTITUTION;}
    }
    public record RowError(String sheet,int row,String message){}

    /** 行级警告：该行仍然导入，但某个硬性条件字段无法从原文可靠判定，已留空待人工确认。 */
    public record RowWarning(String sheet,int row,String field,String rawValue,String message){}

    /** 字段解析结果。值为空且带 warning 时表示"原文有约束但读不懂"，与"原文本就没有约束"区分开。 */
    record FieldParse<T>(T value,String warning){
        static <T> FieldParse<T> of(T value){return new FieldParse<>(value,null);}
        static <T> FieldParse<T> empty(){return new FieldParse<>(null,null);}
        static <T> FieldParse<T> unparsed(String warning){return new FieldParse<>(null,warning);}
    }

    public record ImportResult(UUID recruitmentEventId,int inserted,int updated,int unchanged,int deactivated,List<RowError> errors,List<RowWarning> warnings){
        public ImportResult{errors=errors==null?List.of():List.copyOf(errors);warnings=warnings==null?List.of():List.copyOf(warnings);}
    }
}
