import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppProviders } from '../../app/AppProviders'
import { PlanningJobDetailPage } from './PlanningJobDetailPage'
import { Route, Routes } from 'react-router-dom'

function json(value: unknown) {
  return new Response(JSON.stringify(value), { status: 200, headers: { 'Content-Type': 'application/json' } })
}

describe('PlanningJobDetailPage', () => {
  afterEach(() => { localStorage.clear(); vi.unstubAllGlobals() })

  it('shows archived official job facts and the recruitment process without requiring opportunity admission', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/career-plan?targetYear=')) return json({
        recommendedRoutes: [{ representativeJobs: [{ jobId: 'job-1', scenarioOutcomes: [
          { scenarioCode: 'PRE_GRADUATION', outcome: 'UNCERTAIN', reasons: ['工作经历事实尚未确认'] },
          { scenarioCode: 'DEGREE_PENDING_VERIFICATION', outcome: 'CONDITIONALLY_ELIGIBLE', reasons: ['硕士已取得但留服认证待完成'] },
          { scenarioCode: 'MASTER_VERIFIED', outcome: 'ELIGIBLE', reasons: ['已采集硬条件未发现阻断项'] },
        ] }] }],
      })
      if (url.endsWith('/api/v1/jobs/job-1')) return json({
        id: 'job-1', recruitmentEventId: 'event-1', organizationId: 'org-1', externalJobCode: 'A101',
        title: '信息系统建设', jobFamily: 'INFORMATION_SYSTEMS', employmentType: 'PUBLIC_INSTITUTION_FORMAL',
        location: '杭州', headcount: 1, minimumEducation: 'BACHELOR', exactMajors: ['计算机科学与技术'],
        acceptedGraduationYears: [], maximumAge: 35, ageReferenceDate: '2026-03-31', minimumExperienceYears: 2,
        requiredProfessionalTitles: ['中级职称'], duties: '负责政务信息系统建设与运维',
        sourceUrl: 'https://example.gov.cn/jobs.xlsx', evidenceIds: [], supervisingDepartment: '杭州市数据资源局',
        jobCategory: '专业技术', jobGrade: '十级', educationRequirementText: '本科及以上', degreeRequirement: '学士及以上',
        majorRequirementText: '计算机科学与技术', ageRequirementText: '35周岁及以下', genderRequirement: '不限',
        candidateScope: '社会人员', otherRequirements: '两年相关工作经历',
        originalRequirementText: '本科及以上，计算机科学与技术，35周岁及以下，两年相关工作经历',
        interviewRatio: '1:3', professionalTestRequired: true, contactPhone: '0571-12345678',
      })
      if (url.endsWith('/api/v1/recruitment-events/event-1')) return json({
        id: 'event-1', title: '杭州市事业单位2026年统一招聘', recruitmentYear: 2026,
        publishedOn: '2026-03-10', applicationStartsOn: '2026-03-20', applicationEndsOn: '2026-03-27',
        sourceUrl: 'https://example.gov.cn/notice', registrationUrl: 'https://example.gov.cn/apply',
        writtenExamOn: '2026-04-25', writtenExamSubjects: ['职业能力倾向测验', '综合应用能力'],
        graduateRule: null, overseasDegreeRule: '境外学历须完成认证', experienceEvidenceRule: '工作经历须提供证明',
        employmentStatement: '签订事业单位聘用合同', interviewRule: '按笔试成绩1:3入围',
      })
      if (url.endsWith('/api/v1/organizations/org-1')) return json({
        id: 'org-1', name: '杭州市政务信息中心', organizationType: 'PUBLIC_INSTITUTION',
        province: '浙江', city: '杭州', district: null, officialWebsite: 'https://example.gov.cn',
      })
      throw new Error(`Unexpected request: ${url}`)
    }))

    window.history.pushState({}, '', '/jobs/job-1')
    render(<AppProviders><Routes><Route path="/jobs/:jobId" element={<PlanningJobDetailPage />} /></Routes></AppProviders>)

    expect(await screen.findByRole('heading', { name: '信息系统建设' })).toBeInTheDocument()
    expect(screen.getAllByText(/杭州市政务信息中心/)).toHaveLength(2)
    expect(screen.getByText('计算机科学与技术')).toBeInTheDocument()
    expect(screen.getAllByText(/35周岁及以下/)).toHaveLength(2)
    expect(screen.getByText(/2026-03-20 至 2026-03-27/)).toBeInTheDocument()
    expect(screen.getByText(/职业能力倾向测验、综合应用能力/)).toBeInTheDocument()
    expect(screen.getAllByText(/两年相关工作经历/)).toHaveLength(2)
    expect(screen.getByRole('link', { name: '查看官方公告' })).toHaveAttribute('href', 'https://example.gov.cn/notice')
    expect(screen.getByRole('link', { name: '查看岗位附件' })).toHaveAttribute('href', 'https://example.gov.cn/jobs.xlsx')
    expect(screen.getByText(/历史岗位事实，不代表当前仍可报名/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '按你的三个阶段分别判断' })).toBeInTheDocument()
    expect(screen.getByText('工作经历事实尚未确认')).toBeInTheDocument()
    expect(screen.getByText('硕士已取得但留服认证待完成')).toBeInTheDocument()
    expect(screen.getByText('已采集硬条件未发现阻断项')).toBeInTheDocument()
  })
})
