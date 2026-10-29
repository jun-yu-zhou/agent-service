# 测试样例文件生成说明

- `imm-sample-10p.docx` / `imm-sample-10p.doc`：10 页中文招标文档样例，用于 IMM 按页切图（`CreateOfficeConversionTask`）集成测试，期望转换后输出 10 张 png。
- docx 由 `generate_fixtures.py`（python-docx，10 个章节 + 分页符 + 表格）生成：

  ```bash
  python generate_fixtures.py
  ```

- doc 由 Word COM 另存为 Word 97-2003 格式（FileFormat=0）得到，PowerShell 命令：

  ```powershell
  $word = New-Object -ComObject Word.Application
  $doc = $word.Documents.Open("<绝对路径>\imm-sample-10p.docx")
  $doc.SaveAs([ref]"<绝对路径>\imm-sample-10p.doc", [ref]0)
  $doc.Close($false); $word.Quit()
  ```

- 集成测试 `DocumentToImgIntegrationTest` 需要这两个文件的 OSS URL（切图入口 `ImmGateway.convertDocumentToImages` 只接受 OSS http/https URL），拿到 URL 后填入该测试类并解除 `@Disabled`。
