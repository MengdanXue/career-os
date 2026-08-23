package com.careeros.infrastructure.persistence;

import com.careeros.application.JobUpsertService;
import com.careeros.application.JobUpsertService.JobUpsertBatch;
import com.careeros.application.JobUpsertService.NormalizedJob;
import com.careeros.application.OfficialJobAdmissionService;
import com.careeros.domain.DomainEnums.*;
import java.io.InputStream;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficialExcelImportService {
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern YEAR = Pattern.compile("(20\\d{2})");
    private final RecruitmentEventJpaRepository events;
    private final OrganizationJpaRepository organizations;
    private final JobUpsertService upserts;
    private final OfficialJobAdmissionService admissions;
    private final OfficialWorkbookEvidenceService workbookEvidence;
    private final JobPostingJpaRepository jobPostings;

    public static final class NonJobWorkbookException extends IllegalArgumentException {
        public NonJobWorkbookException(String message) { super(message); }
    }
    public static final class NonTargetWorkbookException extends IllegalArgumentException {
        public NonTargetWorkbookException(String message) { super(message); }
    }

    @Autowired
    public OfficialExcelImportService(RecruitmentEventJpaRepository events,OrganizationJpaRepository organizations,JobUpsertService upserts,OfficialJobAdmissionService admissions,OfficialWorkbookEvidenceService workbookEvidence,JobPostingJpaRepository jobPostings){this.events=events;this.organizations=organizations;this.upserts=upserts;this.admissions=admissions;this.workbookEvidence=workbookEvidence;this.jobPostings=jobPostings;}
    OfficialExcelImportService(RecruitmentEventJpaRepository events,OrganizationJpaRepository organizations,JobUpsertService upserts,OfficialJobAdmissionService admissions,OfficialWorkbookEvidenceService workbookEvidence){this(events,organizations,upserts,admissions,workbookEvidence,null);}
    OfficialExcelImportService(RecruitmentEventJpaRepository events,OrganizationJpaRepository organizations,JobUpsertService upserts,OfficialJobAdmissionService admissions){this(events,organizations,upserts,admissions,null,null);}

    @Transactional
    public ImportResult importWorkbook(InputStream input,ImportCommand command) throws Exception {
        byte[] workbookBytes=input.readAllBytes();
        UUID workbookEvidenceId=workbookEvidence==null?null:workbookEvidence.begin(command.workbookSourceUrl(),command.announcementTitle(),workbookBytes);
        var sourceEvent = command.sourceUrl().equals(command.workbookSourceUrl())
            ? Optional.<JpaModels.RecruitmentEventEntity>empty()
            : events.findFirstBySourceUrl(command.sourceUrl());
        var announcementEvent = sourceEvent
            .filter(value -> value.evidenceIds != null && !value.evidenceIds.isEmpty());
        var legacyEvent = sourceEvent.filter(value -> value.legacyWorkbookSnapshot
            && (value.evidenceIds == null || value.evidenceIds.isEmpty()));
        String workbookIdentity=OfficialWorkbookIdentity.of(command.workbookSourceUrl());
        var event=findWorkbookEvent(command.workbookSourceUrl(),workbookIdentity)
            .map(existing -> mergeAnnouncementDates(existing, command, announcementEvent.orElse(null),workbookIdentity))
            .orElseGet(()->createEvent(command, announcementEvent.orElse(null),workbookIdentity));
        int recognizedSheets=0; int substantiveSheets=0; int nonJobSheets=0; int nonTargetSheets=0; String workbookDefaultOrganization=command.defaultOrganizationName(); var errors=new ArrayList<RowError>(); var seen=new HashSet<String>(); var normalizedJobs=new ArrayList<NormalizedJob>();var rowEvidence=new ArrayList<RowEvidence>();
        try(var workbook=WorkbookFactory.create(new java.io.ByteArrayInputStream(workbookBytes))){
            var formatter=new DataFormatter(Locale.ROOT);
            String titleOrganization=workbookOrganization(workbook,formatter);
            if(!blank(titleOrganization))workbookDefaultOrganization=titleOrganization;
            for(int sheetIndex=0;sheetIndex<workbook.getNumberOfSheets();sheetIndex++){
                var sheet=workbook.getSheetAt(sheetIndex);
                var header=findHeader(sheet,formatter,!blank(workbookDefaultOrganization));
                boolean nonJob=header==null&&isClearlyNonJobSheet(sheet,formatter);
                boolean nonTarget=isClearlyNonTargetSheet(sheet,formatter,header);
                boolean substantive=isSubstantiveSheet(sheet,formatter)||nonJob||nonTarget;
                if(substantive)substantiveSheets++;
                if(nonJob){nonJobSheets++;continue;}
                if(nonTarget){nonTargetSheets++;continue;}
                if(header==null) continue;
                recognizedSheets++;
                String previousOrganization=null;
                for(int rowIndex=header.rowIndex()+1;rowIndex<=sheet.getLastRowNum();rowIndex++){
                    var row=sheet.getRow(rowIndex); if(row==null) continue;
                    try{
                        String title=value(row,header.columns(),formatter,"岗位名称","招聘岗位","招聘岗位名称","岗位","科室/岗位","部门/岗位","部门岗位","科室岗位","选聘岗位");
                        if(blank(title)) continue;
                        String organizationName=value(row,header.columns(),formatter,
                            "招聘单位","单位名称","用人单位","招聘主体");
                        if(blank(organizationName)&&blank(workbookDefaultOrganization))organizationName=value(
                            row,header.columns(),formatter,"用人学院（部门）","用人学院部门");
                        if(blank(organizationName)) organizationName=previousOrganization; else previousOrganization=organizationName;
                        if(blank(organizationName)) organizationName=workbookDefaultOrganization;
                        if(blank(organizationName)) throw new IllegalArgumentException("招聘单位为空");
                        var organization=findOrCreateOrganization(organizationName,command.defaultLocation(),command.eventType());
                        String code=value(row,header.columns(),formatter,"岗位代码","岗位编号","职位代码","岗位序号","序号");
                        String educationText=value(row,header.columns(),formatter,"学历","学历要求","最低学历","学历/学位");
                        String majorText=value(row,header.columns(),formatter,
                            "专业","专业要求","所学专业","学科/专业要求","专业/学科方向","学科专业要求");
                        String ageText=value(row,header.columns(),formatter,"年龄","年龄要求");
                        String conditions=values(row,header.columns(),formatter,
                            "其他条件","其他资格条件或要求","资格条件","其他要求","备注");
                        String experienceText=join(value(row,header.columns(),formatter,
                            "工作经历","工作经验","工作年限","相关经历"),conditions);
                        String applicantText=join(value(row,header.columns(),formatter,
                            "招聘对象","人员范围","对象范围"),conditions);
                        String employmentText=value(row,header.columns(),formatter,"用工性质","编制性质","岗位性质","聘用形式");
                        String headcountText=value(row,header.columns(),formatter,"招聘人数","人数","计划人数","计划数");
                        String duties=values(row,header.columns(),formatter,"岗位职责","主要职责","工作内容");
                        String professionalTitleText=join(value(row,header.columns(),formatter,
                            "职称要求","专业技术职称","专业技术职务任职资格"),conditions);
                        String supervisingDepartment=value(row,header.columns(),formatter,"主管单位（部门）","主管单位","主管部门");
                        String jobCategory=value(row,header.columns(),formatter,"岗位类别","岗位类型");
                        String jobGrade=value(row,header.columns(),formatter,"岗位等级","岗位级别");
                        String degreeRequirement=value(row,header.columns(),formatter,"学位","学位要求","学历/学位");
                        String genderRequirement=value(row,header.columns(),formatter,"性别要求","性别");
                        String candidateScope=value(row,header.columns(),formatter,"招聘对象","人员范围","对象范围");
                        if(blank(candidateScope)&&conditions!=null&&conditions.contains("应届毕业生"))candidateScope=conditions;
                        String interviewRatio=value(row,header.columns(),formatter,"经笔试入围比例","入围面试比例","面试比例");
                        Boolean professionalTestRequired=yesNo(value(row,header.columns(),formatter,
                            "是否设置专业（业务、技能、心理素质）测试","是否设置专业测试","专业测试"));
                        String contactPhone=value(row,header.columns(),formatter,"招聘单位咨询电话","咨询电话","联系电话");
                        String originalRequirementText=joinValues(educationText,degreeRequirement,majorText,ageText,conditions);
                        String location=value(row,header.columns(),formatter,"工作地点","地区","所在地");
                        EmploymentType parsedEmployment=employmentType(employmentText);
                        if(parsedEmployment==EmploymentType.UNKNOWN&&event.defaultEmploymentType!=null)parsedEmployment=event.defaultEmploymentType;
                        var evidenceIds=new LinkedHashSet<UUID>();if(event.evidenceIds!=null)evidenceIds.addAll(event.evidenceIds);if(workbookEvidenceId!=null)evidenceIds.add(workbookEvidenceId);
                        var normalized=new NormalizedJob(
                            event.id,organization.id,organizationName,emptyToNull(code),title,jobFamily(title,duties,majorText),
                            parsedEmployment,location,Math.max(1,integer(headcountText,1)),education(educationText),
                            splitMajors(majorText),graduationYears(applicantText),ageLimit(ageText),first(event.ageReferenceDate,command.ageReferenceDate()),
                            experienceYears(experienceText),professionalTitles(professionalTitleText),duties,
                            command.sourceUrl(),command.workbookSourceUrl(),command.sourceUrl(),List.copyOf(evidenceIds),
                            supervisingDepartment,jobCategory,jobGrade,educationText,degreeRequirement,majorText,ageText,
                            genderRequirement,candidateScope,conditions,originalRequirementText,interviewRatio,
                            professionalTestRequired,contactPhone);
                        String stableKey=upserts.stableKey(normalized);
                        if(!seen.add(stableKey)) throw new IllegalArgumentException("同一文件出现重复稳定岗位键");
                        normalizedJobs.add(normalized);
                    var sourceColumns=sourceColumns(header);
                    rowEvidence.add(new RowEvidence(sheet.getSheetName(),rowIndex+1,evidenceFields(fields(
                        normalized,headcountText,educationText,majorText,ageText,employmentText),sourceColumns),
                        sourceColumns));
                    }catch(Exception exception){errors.add(new RowError(sheet.getSheetName(),rowIndex+1,exception.getMessage()));}
                }
            }
        }
        if(recognizedSheets==0){
            if(substantiveSheets>0&&nonTargetSheets==substantiveSheets)throw new NonTargetWorkbookException("附件内容仅为教学科研人员计划，不在候选人目标岗位范围内");
            if(substantiveSheets>0&&nonJobSheets==substantiveSheets)throw new NonJobWorkbookException("附件内容属于报名、应聘汇总或人员名单，不是岗位计划表");
            throw new IllegalArgumentException("未找到同时包含招聘单位和岗位名称的表头，已拒绝导入");
        }
        var result=upserts.upsert(new JobUpsertBatch(
            event.id,command.sourceUrl(),normalizedJobs,true,
            errors.stream().map(error->error.sheet()+":"+error.row()+":"+error.message()).toList(),
            legacyEvent.map(value -> value.id).orElse(null)));
        if(workbookEvidenceId!=null){
            if(jobPostings!=null)jobPostings.flush();
            for(int index=0;index<Math.min(result.jobIds().size(),rowEvidence.size());index++){var located=rowEvidence.get(index);workbookEvidence.replaceJobFacts(workbookEvidenceId,result.jobIds().get(index),located.sheet(),located.row(),located.fields(),located.sourceColumns());}
        }
        admissions.classify(result.jobIds(), Instant.now());
        return new ImportResult(event.id,result.inserted(),result.updated(),result.unchanged(),result.deactivated(),List.copyOf(errors));
    }

    private Optional<JpaModels.RecruitmentEventEntity> findWorkbookEvent(String sourceUrl,String identity){return events.findFirstByWorkbookIdentity(identity).or(()->events.findFirstBySourceUrl(sourceUrl)).or(()->events.findAll().stream().filter(value->identity.equals(OfficialWorkbookIdentity.of(value.sourceUrl))).findFirst());}
    private JpaModels.RecruitmentEventEntity createEvent(ImportCommand c,JpaModels.RecruitmentEventEntity announcement,String identity){var e=new JpaModels.RecruitmentEventEntity();e.id=UUID.randomUUID();e.title=c.announcementTitle();e.recruitmentYear=c.recruitmentYear();e.eventType=c.eventType();e.publishedOn=first(announcement==null?null:announcement.publishedOn,c.publishedOn());e.applicationStartsOn=announcement==null?null:announcement.applicationStartsOn;e.applicationEndsOn=announcement==null?null:announcement.applicationEndsOn;e.sourceUrl=c.workbookSourceUrl();e.workbookIdentity=identity;e.defaultEmploymentType=announcement==null||announcement.defaultEmploymentType==null?EmploymentType.UNKNOWN:announcement.defaultEmploymentType;e.evidenceIds=announcement==null||announcement.evidenceIds==null?new ArrayList<>():new ArrayList<>(announcement.evidenceIds);copyAnnouncementFacts(e,announcement);return events.save(e);}
    private JpaModels.RecruitmentEventEntity mergeAnnouncementDates(JpaModels.RecruitmentEventEntity event,ImportCommand c,JpaModels.RecruitmentEventEntity announcement,String identity){boolean changed=false;if(!Objects.equals(event.workbookIdentity,identity)){event.workbookIdentity=identity;changed=true;}if(!Objects.equals(event.sourceUrl,c.workbookSourceUrl())){var occupant=events.findFirstBySourceUrl(c.workbookSourceUrl());if(occupant.isEmpty()||Objects.equals(occupant.get().id,event.id)){event.sourceUrl=c.workbookSourceUrl();changed=true;}else if(jobPostings==null||jobPostings.existsByRecruitmentEventIdAndActiveTrue(occupant.get().id)){throw new IllegalStateException("当前附件链接已属于仍含有效岗位的其他招聘事件");}}if(event.publishedOn==null){var value=first(announcement==null?null:announcement.publishedOn,c.publishedOn());if(value!=null){event.publishedOn=value;changed=true;}}if(announcement!=null&&event.applicationStartsOn==null&&announcement.applicationStartsOn!=null){event.applicationStartsOn=announcement.applicationStartsOn;changed=true;}if(announcement!=null&&event.applicationEndsOn==null&&announcement.applicationEndsOn!=null){event.applicationEndsOn=announcement.applicationEndsOn;changed=true;}if(announcement!=null){copyAnnouncementFacts(event,announcement);event.defaultEmploymentType=announcement.defaultEmploymentType;var evidence=new LinkedHashSet<UUID>();if(event.evidenceIds!=null)evidence.addAll(event.evidenceIds);if(announcement.evidenceIds!=null)evidence.addAll(announcement.evidenceIds);event.evidenceIds=new ArrayList<>(evidence);changed=true;}return changed?events.save(event):event;}
    private static void copyAnnouncementFacts(JpaModels.RecruitmentEventEntity target,JpaModels.RecruitmentEventEntity source){if(source==null)return;target.applicationStartsAt=source.applicationStartsAt;target.applicationEndsAt=source.applicationEndsAt;target.ageReferenceDate=source.ageReferenceDate;target.registrationUrl=source.registrationUrl;target.qualificationReviewEndsOn=source.qualificationReviewEndsOn;target.paymentEndsOn=source.paymentEndsOn;target.admissionTicketStartsOn=source.admissionTicketStartsOn;target.admissionTicketEndsOn=source.admissionTicketEndsOn;target.writtenExamOn=source.writtenExamOn;target.writtenExamSubjects=source.writtenExamSubjects==null?new ArrayList<>():new ArrayList<>(source.writtenExamSubjects);target.graduateRule=source.graduateRule;target.overseasDegreeRule=source.overseasDegreeRule;target.experienceEvidenceRule=source.experienceEvidenceRule;target.employmentStatement=source.employmentStatement;target.interviewRule=source.interviewRule;}
    private static <T> T first(T preferred,T fallback){return preferred!=null?preferred:fallback;}
    private JpaModels.OrganizationEntity findOrCreateOrganization(String name,String location,EventType eventType){return organizations.findFirstByName(name).orElseGet(()->{var e=new JpaModels.OrganizationEntity();e.id=UUID.randomUUID();e.name=name;e.organizationType=organizationType(name,eventType);e.province=location!=null&&location.contains("浙江")?"浙江":null;e.city=location!=null&&location.contains("杭州")?"杭州":null;return organizations.save(e);});}

    private Header findHeader(Sheet sheet,DataFormatter formatter,boolean allowsDefaultOrganization){for(int r=sheet.getFirstRowNum();r<=Math.min(sheet.getLastRowNum(),30);r++){var row=sheet.getRow(r);if(row==null)continue;var map=new HashMap<String,Integer>();var labels=new HashMap<String,String>();for(Cell cell:row){String raw=formatter.formatCellValue(cell).trim();String text=normalizeHeader(raw);if(!text.isBlank()){map.put(text,cell.getColumnIndex());labels.put(text,raw);}}boolean hasJob=containsAny(map,"岗位名称","招聘岗位","招聘岗位名称","岗位","科室/岗位","部门/岗位","部门岗位","科室岗位","选聘岗位");boolean hasOrganization=containsAny(map,"招聘单位","单位名称","用人单位","招聘主体","用人学院（部门）","用人学院部门");if(hasJob&&(hasOrganization||allowsDefaultOrganization))return new Header(r,map,labels);}return null;}
    private String workbookOrganization(Workbook workbook,DataFormatter formatter){
        for(int sheetIndex=0;sheetIndex<workbook.getNumberOfSheets();sheetIndex++){
            var sheet=workbook.getSheetAt(sheetIndex);
            for(int r=sheet.getFirstRowNum();r<=Math.min(sheet.getLastRowNum(),6);r++){
                var row=sheet.getRow(r);if(row==null)continue;
                for(Cell cell:row){
                    String organization=organizationFromWorkbookTitle(formatter.formatCellValue(cell));
                    if(!blank(organization))return organization;
                }
            }
        }
        return null;
    }
    private String organizationFromWorkbookTitle(String value){
        if(blank(value))return null;
        String title=value.replaceAll("[《》〈〉]","").replaceAll("^[附件一二三四五六七八九十0-9.：:、\\-]+","").strip();
        if(!(title.contains("招聘")||title.contains("选聘"))||!(title.contains("计划")||title.contains("岗位表")))return null;
        int end=title.length();
        var year=YEAR.matcher(title);if(year.find())end=Math.min(end,year.start());
        for(String marker:List.of("事业单位公开招聘","公开招聘","事业单位招聘","招聘计划表","招聘岗位表","公开选聘","选聘计划表")){
            int index=title.indexOf(marker);if(index>0)end=Math.min(end,index);
        }
        if(end<=1)return null;
        String candidate=title.substring(0,end).replaceFirst("(?:下属|所属|直属)?事业单位$","").strip();
        if(candidate.matches("浙江省(?:属)?")||candidate.equals("省属"))return null;
        return candidate.length()>=2&&candidate.length()<=180?candidate:null;
    }
    private boolean isClearlyNonJobSheet(Sheet sheet,DataFormatter formatter){
        var labels=new HashSet<String>();
        labels.add(normalizeHeader(sheet.getSheetName()));
        for(int r=sheet.getFirstRowNum();r<=Math.min(sheet.getLastRowNum(),30);r++){
            var row=sheet.getRow(r);if(row==null)continue;
            for(Cell cell:row){String value=normalizeHeader(formatter.formatCellValue(cell));if(!value.isBlank())labels.add(value);}
        }
        if(labels.stream().anyMatch(value->value.contains("应聘信息汇总表")||value.contains("报名表")
            ||value.contains("申请表")||value.contains("亲属关系申报")||value.contains("入围人员名单")))return true;
        boolean hasName=labels.stream().anyMatch(value->value.equals("姓名")||value.endsWith("姓名"));
        boolean hasPersonalData=labels.stream().anyMatch(value->value.contains("身份证")||value.contains("联系电话")
            ||value.contains("毕业院校")||value.contains("面试成绩")||value.contains("准考证号"));
        return hasName&&hasPersonalData;
    }
    private boolean isClearlyNonTargetSheet(Sheet sheet,DataFormatter formatter,Header header){
        boolean teachingPlan=normalizeHeader(sheet.getSheetName()).contains("教学科研人员计划");
        int headingEnd=header==null?Math.min(sheet.getLastRowNum(),10):header.rowIndex();
        for(int r=sheet.getFirstRowNum();r<=headingEnd&&!teachingPlan;r++){
            var row=sheet.getRow(r);if(row==null)continue;
            for(Cell cell:row){
                String value=normalizeHeader(formatter.formatCellValue(cell));
                if(value.contains("教学科研人员计划")||value.contains("教师岗招聘计划")){teachingPlan=true;break;}
            }
        }
        if(!teachingPlan)return false;
        if(header==null)return true;
        for(int r=header.rowIndex()+1;r<=sheet.getLastRowNum();r++){
            var row=sheet.getRow(r);if(row==null)continue;
            String title=value(row,header.columns(),formatter,
                "岗位名称","招聘岗位","招聘岗位名称","岗位","科室/岗位","部门/岗位","部门岗位","科室岗位","选聘岗位");
            if(!blank(title)&&containsTechnicalRole(title))return false;
        }
        return true;
    }
    private static boolean containsTechnicalRole(String title){
        return List.of("信息","计算机","软件","数据","网络","安全","人工智能","数字化","运维","系统","技术","工程师")
            .stream().anyMatch(title::contains);
    }
    private boolean isSubstantiveSheet(Sheet sheet,DataFormatter formatter){
        int populatedRows=0;
        for(int r=sheet.getFirstRowNum();r<=sheet.getLastRowNum();r++){
            var row=sheet.getRow(r);if(row==null)continue;
            int populatedCells=0;
            for(Cell cell:row)if(!formatter.formatCellValue(cell).isBlank())populatedCells++;
            if(populatedCells>=2&&++populatedRows>=2)return true;
        }
        return false;
    }
    private boolean containsAny(Map<String,Integer> map,String...aliases){return Arrays.stream(aliases).map(OfficialExcelImportService::normalizeHeader).anyMatch(map::containsKey);}
    private String value(Row row,Map<String,Integer> columns,DataFormatter f,String...aliases){for(String alias:aliases){var column=columns.get(normalizeHeader(alias));if(column!=null){var cell=row.getCell(column,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);if(cell!=null){var value=f.formatCellValue(cell).trim();if(!value.isBlank())return value;}}}return null;}
    private String values(Row row,Map<String,Integer> columns,DataFormatter f,String...aliases){var result=new LinkedHashSet<String>();for(String alias:aliases){var column=columns.get(normalizeHeader(alias));if(column!=null){var cell=row.getCell(column,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);if(cell!=null){var value=f.formatCellValue(cell).trim();if(!value.isBlank())result.add(value);}}}return result.isEmpty()?null:String.join("；",result);}
    private static String normalizeHeader(String value){return value==null?"":value.replaceAll("[\\s\\n\\r：:（）()]","").trim();}
    private static Set<String> splitMajors(String text){
        if(blank(text))return new LinkedHashSet<>();
        String normalizedText=text.replaceAll("[\\s，,。；;：:]","");
        if(List.of("不限","不限制","无","无要求","专业不限").contains(normalizedText))return new LinkedHashSet<>();
        var result=new LinkedHashSet<String>();
        for(String part:text.split("[、,，;；/\\n]")){
            String value=part.trim();
            int restricted=Math.max(value.indexOf("（限"),value.indexOf("(限"));
            if(restricted>=0)value=value.substring(restricted+2).trim();
            value=value.replaceFirst("[）)]$","").replaceFirst("方向$","").trim();
            if(!value.isBlank())result.add(value);
        }
        return result;
    }
    private static Set<Integer> graduationYears(String text){var result=new LinkedHashSet<Integer>();if(text!=null){var m=YEAR.matcher(text);while(m.find())result.add(Integer.parseInt(m.group(1)));}return result;}
    private static Integer integerOrNull(String text){if(blank(text))return null;var m=NUMBER.matcher(text);return m.find()?Integer.valueOf(m.group(1)):null;}
    private static Integer ageLimit(String text){return text!=null&&(text.contains("周岁")||text.matches(".*\\d+岁.*"))?integerOrNull(text):null;}
    private static Integer experienceYears(String text){return text!=null&&text.matches(".*\\d+\\s*年.*")?integerOrNull(text):null;}
    private static Set<String> professionalTitles(String text){var result=new LinkedHashSet<String>();if(blank(text))return result;for(String level:List.of("正高级","副高级","高级","中级","初级")){if(text.contains(level))result.add(level);}return result;}
    private static int integer(String text,int fallback){var value=integerOrNull(text);return value==null?fallback:value;}
    private static Boolean yesNo(String text){if(blank(text))return null;if(text.contains("是")||text.equalsIgnoreCase("yes"))return true;if(text.contains("否")||text.equalsIgnoreCase("no"))return false;return null;}
    private static EducationLevel education(String text){if(text!=null&&(text.contains("博士")))return EducationLevel.DOCTORATE;if(text!=null&&(text.contains("硕士")||text.contains("研究生")))return EducationLevel.MASTER;if(text!=null&&text.contains("本科"))return EducationLevel.BACHELOR;if(text!=null&&(text.contains("专科")||text.contains("大专")))return EducationLevel.ASSOCIATE;return EducationLevel.UNKNOWN;}
    private static EmploymentType employmentType(String text){if(blank(text))return EmploymentType.UNKNOWN;if(text.contains("劳务派遣"))return EmploymentType.LABOR_DISPATCH;if(text.contains("人事代理"))return EmploymentType.PERSONNEL_AGENCY;if(text.contains("项目"))return EmploymentType.PROJECT_BASED;if(text.contains("合同"))return EmploymentType.CONTRACT;if(text.contains("事业编")||text.contains("编制内"))return EmploymentType.ESTABLISHMENT;return EmploymentType.UNKNOWN;}
    private static JobFamily jobFamily(String title,String duties,String majors){String role=nvl(title)+nvl(duties);String text=role+nvl(majors);if(role.contains("信息中心")||role.contains("信息管理")||role.contains("信息化")||role.contains("信息系统"))return JobFamily.INFORMATION_SYSTEMS;if(text.contains("人工智能")||text.contains("算法")||text.contains("机器学习"))return JobFamily.AI;if(text.contains("数据"))return JobFamily.DATA;if(text.contains("网络安全")||text.contains("信息安全")||text.contains("安全技术"))return JobFamily.CYBERSECURITY;if(text.contains("软件")||text.contains("开发")||text.contains("Java"))return JobFamily.SOFTWARE;if(text.contains("计算机"))return JobFamily.INFORMATION_SYSTEMS;if(text.contains("数字"))return JobFamily.DIGITALIZATION;if(text.contains("运维")||text.contains("网络")||text.contains("通信"))return JobFamily.IT_OPERATIONS;if(text.contains("研究"))return JobFamily.RESEARCH;return JobFamily.OTHER;}
    private static OrganizationType organizationType(String name,EventType eventType){if(name.contains("医院"))return OrganizationType.HOSPITAL;if(name.contains("大学")||name.contains("学院")||name.contains("学校"))return OrganizationType.UNIVERSITY;if(eventType==EventType.STATE_OWNED_ENTERPRISE)return OrganizationType.STATE_OWNED_ENTERPRISE;if(eventType==EventType.UNIVERSITY)return OrganizationType.UNIVERSITY;if(eventType==EventType.HOSPITAL)return OrganizationType.HOSPITAL;return eventType==EventType.PUBLIC_INSTITUTION?OrganizationType.PUBLIC_INSTITUTION:OrganizationType.OTHER;}
    private static boolean blank(String value){return value==null||value.isBlank();} private static String nvl(String value){return value==null?"":value;} private static String emptyToNull(String value){return blank(value)?null:value;}
    private static String join(String first,String second){if(blank(first))return second;if(blank(second))return first;return first+"；"+second;}
    private static String joinValues(String...values){var result=new ArrayList<String>();for(String value:values)if(!blank(value))result.add(value);return result.isEmpty()?null:String.join("；",result);}
    private static Map<String,String> fields(NormalizedJob job,String headcountText,String educationText,String majorText,String ageText,String employmentText){var fields=new LinkedHashMap<String,String>();put(fields,"externalJobCode",job.externalJobCode());put(fields,"title",job.title());put(fields,"organizationName",job.organizationName());put(fields,"headcount",headcountText);put(fields,"employmentType",employmentText);put(fields,"supervisingDepartment",job.supervisingDepartment());put(fields,"jobCategory",job.jobCategory());put(fields,"jobGrade",job.jobGrade());put(fields,"educationRequirementText",educationText);put(fields,"degreeRequirement",job.degreeRequirement());put(fields,"majorRequirementText",majorText);put(fields,"ageRequirementText",ageText);put(fields,"genderRequirement",job.genderRequirement());put(fields,"candidateScope",job.candidateScope());put(fields,"otherRequirements",job.otherRequirements());put(fields,"interviewRatio",job.interviewRatio());put(fields,"professionalTestRequired",job.professionalTestRequired()==null?null:job.professionalTestRequired().toString());put(fields,"contactPhone",job.contactPhone());return Collections.unmodifiableMap(fields);}
    private static Map<String,String> sourceColumns(Header header){var result=new LinkedHashMap<String,String>();column(result,header,"externalJobCode","岗位代码","岗位编号","职位代码","岗位序号","序号");column(result,header,"title","岗位名称","招聘岗位","招聘岗位名称","岗位","科室/岗位","部门/岗位","部门岗位","科室岗位","选聘岗位");column(result,header,"organizationName","招聘单位","单位名称","用人单位","招聘主体","用人学院（部门）","用人学院部门");column(result,header,"headcount","招聘人数","人数","计划人数","计划数");column(result,header,"employmentType","用工性质","编制性质","岗位性质","聘用形式");column(result,header,"supervisingDepartment","主管单位（部门）","主管单位","主管部门");column(result,header,"jobCategory","岗位类别","岗位类型");column(result,header,"jobGrade","岗位等级","岗位级别");column(result,header,"educationRequirementText","学历","学历要求","最低学历","学历/学位");column(result,header,"degreeRequirement","学位","学位要求","学历/学位");column(result,header,"majorRequirementText","专业","专业要求","所学专业","学科/专业要求","专业/学科方向","学科专业要求");column(result,header,"ageRequirementText","年龄","年龄要求");column(result,header,"genderRequirement","性别要求","性别");column(result,header,"candidateScope","招聘对象","人员范围","对象范围");column(result,header,"otherRequirements","其他条件","其他资格条件或要求","资格条件","其他要求","备注");column(result,header,"interviewRatio","经笔试入围比例","入围面试比例","面试比例");column(result,header,"professionalTestRequired","是否设置专业（业务、技能、心理素质）测试","是否设置专业测试","专业测试");column(result,header,"contactPhone","招聘单位咨询电话","咨询电话","联系电话");return Collections.unmodifiableMap(result);}
    private static Map<String,String> evidenceFields(Map<String,String> fields,Map<String,String> sourceColumns){var result=new LinkedHashMap<String,String>();fields.forEach((field,value)->{if(sourceColumns.containsKey(field))result.put(field,value);});return Collections.unmodifiableMap(result);}
    private static void column(Map<String,String> target,Header header,String field,String...aliases){for(String alias:aliases){String label=header.labels().get(normalizeHeader(alias));if(label!=null&&!label.isBlank()){target.put(field,label);return;}}}
    private static void put(Map<String,String> target,String key,String value){if(!blank(value))target.put(key,value);}

    private record Header(int rowIndex,Map<String,Integer> columns,Map<String,String> labels){}
    private record RowEvidence(String sheet,int row,Map<String,String> fields,Map<String,String> sourceColumns){}
    public record ImportCommand(String announcementTitle,String sourceUrl,int recruitmentYear,LocalDate publishedOn,LocalDate ageReferenceDate,String defaultLocation,EventType eventType,String defaultOrganizationName,String workbookSourceUrl){
        public ImportCommand(String announcementTitle,String sourceUrl,int recruitmentYear,LocalDate publishedOn,LocalDate ageReferenceDate,String defaultLocation,EventType eventType){this(announcementTitle,sourceUrl,recruitmentYear,publishedOn,ageReferenceDate,defaultLocation,eventType,null,sourceUrl);}
        public ImportCommand(String announcementTitle,String sourceUrl,int recruitmentYear,LocalDate publishedOn,LocalDate ageReferenceDate,String defaultLocation,EventType eventType,String defaultOrganizationName){this(announcementTitle,sourceUrl,recruitmentYear,publishedOn,ageReferenceDate,defaultLocation,eventType,defaultOrganizationName,sourceUrl);}
        public ImportCommand{if(blank(announcementTitle)||blank(sourceUrl)||blank(workbookSourceUrl))throw new IllegalArgumentException("announcementTitle, sourceUrl and workbookSourceUrl are required");if(eventType==null)eventType=EventType.PUBLIC_INSTITUTION;}
    }
    public record RowError(String sheet,int row,String message){}
    public record ImportResult(UUID recruitmentEventId,int inserted,int updated,int unchanged,int deactivated,List<RowError> errors){}
}
