-- 产品需求 §10.6：每个资格结论都要有可定位证据。
-- 此前抽取阶段 ExtractedFact 记下的片段级 evidenceFragmentIds 在归一化成岗位时被丢弃，
-- 资格判定只能挂公告级证据。新增逐字段证据列把片段级定位保留到岗位模型。
--
-- 历史行没有片段级证据，留空对象；JobPosting.evidenceFor() 会回落到公告级 evidence_ids。

ALTER TABLE job_posting
    ADD COLUMN field_evidence JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE job_posting
    ADD CONSTRAINT job_posting_field_evidence_check
    CHECK (jsonb_typeof(field_evidence) = 'object');
