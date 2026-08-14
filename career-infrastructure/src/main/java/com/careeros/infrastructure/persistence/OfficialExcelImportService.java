package com.careeros.infrastructure.persistence;

import com.careeros.domain.DomainEnums.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final RecruitmentEventJpaRepository events;
    private final OrganizationJpaRepository organizations;
    private final JobPostingJpaRepository jobs;

    public OfficialExcelImportService(RecruitmentEventJpaRepository events,OrganizationJpaRepository organizations,JobPostingJpaRepository jobs){this.events=events;this.organizations=organizations;this.jobs=jobs;}

    @Transactional
    public ImportResult importWorkbook(InputStream input,ImportCommand command) throws Exception {
        var event=events.findFirstBySourceUrl(command.sourceUrl()).orElseGet(()->createEvent(command));
        int inserted=0,updated=0,unchanged=0,recognizedSheets=0; var errors=new ArrayList<RowError>(); var seen=new HashSet<String>();
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
                        String stableKey=sha256(command.sourceUrl()+"|"+normalize(organizationName)+"|"+normalize(blank(code)?title:code));
                        String fingerprint=sha256(String.join("|",title,nvl(code),nvl(educationText),nvl(majorText),nvl(ageText),nvl(experienceText),nvl(applicantText),nvl(employmentText),nvl(headcountText),nvl(duties),nvl(location)));
                        if(!seen.add(stableKey)) throw new IllegalArgumentException("同一文件出现重复稳定岗位键");
                        var existing=jobs.findByStableJobKey(stableKey);
                        if(existing.isPresent() && fingerprint.equals(existing.get().contentFingerprint)){ existing.get().lastSeenAt=Instant.now(); existing.get().active=true; jobs.save(existing.get()); unchanged++; continue; }
                        var entity=existing.orElseGet(JpaModels.JobPostingEntity::new);
                        if(existing.isEmpty()){entity.id=UUID.randomUUID();entity.firstSeenAt=Instant.now();inserted++;} else updated++;
                        entity.recruitmentEventId=event.id; entity.organizationId=organization.id; entity.externalJobCode=emptyToNull(code); entity.title=title;
                        entity.jobFamily=jobFamily(title,duties); entity.employmentType=employmentType(employmentText); entity.location=location; entity.headcount=Math.max(1,integer(headcountText,1));
                        entity.minimumEducation=education(educationText); entity.exactMajors=splitMajors(majorText); entity.acceptedGraduationYears=graduationYears(applicantText);
                        entity.maximumAge=ageLimit(ageText); entity.ageReferenceDate=command.ageReferenceDate(); entity.minimumExperienceYears=experienceYears(experienceText);
                        entity.requiredProfessionalTitles=new LinkedHashSet<>(); entity.duties=duties; entity.sourceUrl=command.sourceUrl(); entity.evidenceIds=new ArrayList<>();
                        entity.stableJobKey=stableKey; entity.contentFingerprint=fingerprint; entity.active=true; entity.lastSeenAt=Instant.now(); jobs.save(entity);
                    }catch(Exception exception){errors.add(new RowError(sheet.getSheetName(),rowIndex+1,exception.getMessage()));}
                }
            }
        }
        if(recognizedSheets==0) throw new IllegalArgumentException("未找到同时包含招聘单位和岗位名称的表头，已拒绝导入");
        int deactivated=0;
        if(errors.isEmpty()) for(var existing:jobs.findByRecruitmentEventId(event.id)) if(existing.stableJobKey!=null && !seen.contains(existing.stableJobKey) && existing.active){existing.active=false;existing.lastSeenAt=Instant.now();jobs.save(existing);deactivated++;}
        return new ImportResult(event.id,inserted,updated,unchanged,deactivated,List.copyOf(errors));
    }

    private JpaModels.RecruitmentEventEntity createEvent(ImportCommand c){var e=new JpaModels.RecruitmentEventEntity();e.id=UUID.randomUUID();e.title=c.announcementTitle();e.recruitmentYear=c.recruitmentYear();e.eventType=c.eventType();e.publishedOn=c.publishedOn();e.sourceUrl=c.sourceUrl();e.defaultEmploymentType=EmploymentType.UNKNOWN;e.evidenceIds=new ArrayList<>();return events.save(e);}
    private JpaModels.OrganizationEntity findOrCreateOrganization(String name,String location,EventType eventType){return organizations.findFirstByName(name).orElseGet(()->{var e=new JpaModels.OrganizationEntity();e.id=UUID.randomUUID();e.name=name;e.organizationType=organizationType(name,eventType);e.province=location!=null&&location.contains("浙江")?"浙江":null;e.city=location!=null&&location.contains("杭州")?"杭州":null;return organizations.save(e);});}

    private Header findHeader(Sheet sheet,DataFormatter formatter,boolean allowsDefaultOrganization){for(int r=sheet.getFirstRowNum();r<=Math.min(sheet.getLastRowNum(),30);r++){var row=sheet.getRow(r);if(row==null)continue;var map=new HashMap<String,Integer>();for(Cell cell:row){String text=normalizeHeader(formatter.formatCellValue(cell));if(!text.isBlank())map.put(text,cell.getColumnIndex());}boolean hasJob=containsAny(map,"岗位名称","招聘岗位","岗位");boolean hasOrganization=containsAny(map,"招聘单位","单位名称","用人单位","招聘主体");if(hasJob&&(hasOrganization||allowsDefaultOrganization))return new Header(r,map);}return null;}
    private boolean containsAny(Map<String,Integer> map,String...aliases){return Arrays.stream(aliases).map(OfficialExcelImportService::normalizeHeader).anyMatch(map::containsKey);}
    private String value(Row row,Map<String,Integer> columns,DataFormatter f,String...aliases){for(String alias:aliases){var column=columns.get(normalizeHeader(alias));if(column!=null){var cell=row.getCell(column,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);if(cell!=null){var value=f.formatCellValue(cell).trim();if(!value.isBlank())return value;}}}return null;}
    private static String normalizeHeader(String value){return value==null?"":value.replaceAll("[\\s\\n\\r：:（）()]","").trim();}
    private static String normalize(String value){return nvl(value).toLowerCase(Locale.ROOT).replaceAll("[\\s·（）()_\\-]","");}
    private static String sha256(String text){try{var digest=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(digest);}catch(Exception e){throw new IllegalStateException(e);}}
    private static Set<String> splitMajors(String text){if(blank(text))return new LinkedHashSet<>();var result=new LinkedHashSet<String>();for(String part:text.split("[、,，;；/\\n]")){var value=part.trim();if(!value.isBlank())result.add(value);}return result;}
    private static Set<Integer> graduationYears(String text){var result=new LinkedHashSet<Integer>();if(text!=null){var m=YEAR.matcher(text);while(m.find())result.add(Integer.parseInt(m.group(1)));}return result;}
    private static Integer integerOrNull(String text){if(blank(text))return null;var m=NUMBER.matcher(text);return m.find()?Integer.valueOf(m.group(1)):null;}
    private static Integer ageLimit(String text){return text!=null&&(text.contains("周岁")||text.matches(".*\\d+岁.*"))?integerOrNull(text):null;}
    private static Integer experienceYears(String text){return text!=null&&text.matches(".*\\d+\\s*年.*")?integerOrNull(text):null;}
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
    public record ImportResult(UUID recruitmentEventId,int inserted,int updated,int unchanged,int deactivated,List<RowError> errors){}
}
