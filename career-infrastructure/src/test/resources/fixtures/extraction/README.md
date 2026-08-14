# Extraction regression fixtures

Retrieval date: 2026-08-14. These files are preserved byte-for-byte from the previously approved Career OS sample manifest.

| File | Original source | SHA-256 | Regression purpose |
|---|---|---|---|
| `08-zj-2025-applicant-guide.pdf` | [浙江省人社厅应聘指南附件](https://career.zjnu.edu.cn/attachment/zjnu/ueditor/file/20250323/4681_%E9%99%84%E4%BB%B62%EF%BC%9A%E5%BA%94%E8%81%98%E6%8C%87%E5%8D%97.pdf) | `59b0ccb3f374b2e60abf31532bed5c1c097c3db6bdaedc30cd01a6118513635b` | Text PDF extraction, page locators, and a successful policy document with zero job rows. |
| `09-hz-capital-recruiting.html` | [杭州市国有资本投资运营有限公司招聘入口](https://hzzbco.com/joinRecruiting?current=1) | `5ac8dac87ecb5e3ff1eccfe8e392fae54959f5749169a613a09b4851c5f452fa` | Historical dynamic-entry HTML, visible-text extraction, and script exclusion. |
| `10-hzfi-social-recruiting.html` | [杭州市金融投资集团社会招聘入口](https://hr.hzfi.cn/jsp/recruit/socialRecruit.jsp) | `138dd654aaf75e4260c91f318e642c7ec0dbabcd51297596772fb093f147056e` | Second real HTML structure for selector and empty/dynamic-content regression. |
