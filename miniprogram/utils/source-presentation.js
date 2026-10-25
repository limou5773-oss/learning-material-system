function sourceLocator(source) {
  if (!source) return '来源暂不可用'
  if (source.extractionMethod === 'METADATA') return '资料标题元数据，正文未提取'
  if (source.extractionMethod === 'PDF_TEXT') return source.pageNo ? `PDF 第 ${source.pageNo} 页` : 'PDF文字片段'
  if (source.extractionMethod === 'OCR') return source.pageNo ? `PDF 第 ${source.pageNo} 页 · OCR 识别文字` : '图片 OCR 识别文字'
  return `文字笔记片段 ${source.chunkNo || ''}`.trim()
}
function taskErrorMessage(code) {
  const messages = {
    PDF_OCR_REQUIRED: '所选页段没有可用文字层，可能需要OCR或为空白页；当前未启用OCR，请配置OCR或选择有文字的页段。',
    OCR_NOT_CONFIGURED: 'OCR尚未配置，请在后端本机配置厂商与密钥后重试。',
    OCR_CREDENTIALS_INVALID: 'OCR凭证格式无效，请检查本机配置，勿在页面提交密钥。',
    OCR_AUTH_FAILED: 'OCR鉴权失败，请检查密钥及OCR调用权限。',
    OCR_SERVICE_NOT_ENABLED: '腾讯云OCR服务尚未开通，请在厂商控制台开通后重试。',
    OCR_EMPTY_RESULT: 'OCR未识别到可用文字，请换用更清晰的图片或调整页段。',
    OCR_TIMEOUT: 'OCR请求超时，费用状态未知；请查看记录后重试。',
    OCR_RATE_LIMITED: 'OCR请求被限流，请稍后重试。',
    OCR_ATTEMPT_LIMIT: '本任务已达到OCR请求尝试上限，请缩小页段或重新提交。',
    OCR_CACHE_CONFLICT: '当前资料版本的OCR缓存与配置不一致，请更新资料版本或重新上传。',
    OCR_TEXT_LIMIT_EXCEEDED: '识别文字超过处理上限，请缩小页段或分开上传。',
    OCR_IMAGE_TOO_LARGE: '图片超过OCR处理上限，请裁剪或降低图片尺寸后重试。',
    OCR_IMAGE_INVALID: '图片无法供OCR读取，请重新上传有效图片。',
    OCR_FILE_UNAVAILABLE: 'OCR来源文件暂时无法读取，请检查或重新上传。',
    OCR_CONNECTION_FAILED: '无法连接OCR服务，请检查后端网络后重试。',
    OCR_INVALID_RESPONSE: 'OCR服务返回格式无效，未保存识别内容。',
    OCR_RESPONSE_INVALID: 'OCR结果校验失败，未保存识别内容。',
    OCR_PROVIDER_UNAVAILABLE: 'OCR服务暂不可用，请稍后重试。',
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
