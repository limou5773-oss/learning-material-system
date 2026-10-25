function sourceLocator(source) {
  if (!source) return '来源暂不可用'
  if (source.extractionMethod === 'METADATA') return '资料标题元数据，正文未提取'
  if (source.extractionMethod === 'PDF_TEXT') return source.pageNo ? `PDF 第 ${source.pageNo} 页` : 'PDF文字片段'
  return `文字笔记片段 ${source.chunkNo || ''}`.trim()
}
function taskErrorMessage(code) {
  const messages = {
    PDF_OCR_REQUIRED: '所选页段没有可用文字层，可能需要OCR或为空白页；当前尚未接入OCR，请选择有文字的页段或换用文字版PDF。',
    PDF_TEXT_LIMIT_EXCEEDED: '所选PDF文字超过本次处理上限，请缩小页段后重试。',
    PDF_EXTRACTION_FAILED: 'PDF文字提取失败，请检查文件是否损坏或换用可读取的PDF。',
    PDF_READ_FAILED: 'PDF读取或文字提取失败，请检查文件是否损坏或换用可读取的PDF。',
    PDF_FILE_UNAVAILABLE: 'PDF文件暂时无法读取，请重新上传后重试。',
    PDF_ENCRYPTED: '暂不支持加密PDF，请上传可直接读取的文件。',
    PDF_EXTRACTION_FORBIDDEN: '文件未授予文字提取权限，请换用允许提取文字的PDF。',
  }
  return messages[code] || ''
}
module.exports = { sourceLocator, taskErrorMessage }
