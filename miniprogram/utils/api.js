const { API_BASE_URL } = require('../config')

const TOKEN_KEY = 'lms.accessToken.v1'

class ApiError extends Error {
  constructor(status, message, code, existingMaterialId, currentVersion) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code || ''
    this.existingMaterialId = existingMaterialId || ''
    this.currentVersion = currentVersion
  }
}

function getToken() {
  const token = wx.getStorageSync(TOKEN_KEY)
  return typeof token === 'string' ? token : ''
}

function saveToken(token) {
  if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(token)) {
    throw new Error('服务端返回的会话令牌无效')
  }
  wx.setStorageSync(TOKEN_KEY, token)
}

function clearToken() {
  wx.removeStorageSync(TOKEN_KEY)
}

function request(path, options = {}) {
  const method = options.method || 'GET'
  const authenticated = options.auth !== false
  const token = authenticated ? getToken() : ''
  const header = { 'Content-Type': 'application/json' }
  if (token) header.Authorization = `Bearer ${token}`

  return new Promise((resolve, reject) => {
    wx.request({
      url: `${API_BASE_URL}${path}`,
      method,
      header,
      data: options.data,
      success(response) {
        const status = response.statusCode
        const payload = response.data
        if (status === 204) {
          resolve(undefined)
          return
        }
        if (status < 200 || status >= 300) {
          if (status === 401 && authenticated && getToken() === token) clearToken()
          const problem = payload && payload.error
          reject(new ApiError(status, problem && problem.message || '请求失败，请稍后重试', problem && problem.code,
            problem && problem.existingMaterialId, problem && problem.currentVersion))
          return
        }
        if (!payload || typeof payload !== 'object' || !Object.prototype.hasOwnProperty.call(payload, 'data')) {
          reject(new ApiError(status, '服务端响应格式错误'))
          return
        }
        resolve(payload.data)
      },
      fail() {
        reject(new ApiError(0, '无法连接服务器，请检查后端地址与网络'))
      },
    })
  })
}

function uploadImage(filePath, formData) {
  const token = getToken()
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${API_BASE_URL}/materials/files`, filePath, name: 'file', formData,
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success(response) {
        let payload
        try { payload = JSON.parse(response.data) }
        catch { reject(new ApiError(response.statusCode, '服务端响应格式错误')); return }
        if (response.statusCode < 200 || response.statusCode >= 300) {
          if (response.statusCode === 401 && getToken() === token) clearToken()
          const problem = payload && payload.error
          reject(new ApiError(response.statusCode, problem && problem.message || '图片上传失败',
            problem && problem.code, problem && problem.existingMaterialId))
          return
        }
        if (!payload || !payload.data) { reject(new ApiError(response.statusCode, '服务端响应格式错误')); return }
        resolve(payload.data)
      },
      fail() { reject(new ApiError(0, '无法连接服务器，请检查后端地址与网络')) },
    })
  })
}

function downloadMaterialFile(id) {
  const token = getToken()
  return new Promise((resolve, reject) => {
    wx.downloadFile({
      url: `${API_BASE_URL}/materials/${encodeURIComponent(id)}/file`,
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success(response) {
        if (response.statusCode === 401 && getToken() === token) clearToken()
        if (response.statusCode !== 200 || !response.tempFilePath) {
          reject(new ApiError(response.statusCode, '文件读取失败'))
          return
        }
        resolve(response.tempFilePath)
      },
      fail() { reject(new ApiError(0, '无法连接服务器，请检查后端地址与网络')) },
    })
  })
}

function login(username, password) {
  return request('/auth/sessions', {
    method: 'POST',
    auth: false,
    data: { username, password, clientType: 'MINIPROGRAM' },
  })
}

function getMe() {
  return request('/users/me')
}

function updateMe(data) {
  return request('/users/me', { method: 'PATCH', data })
}

function getWechatCode() {
  return new Promise((resolve, reject) => {
    wx.login({
      timeout: 10000,
      success(result) {
        if (!result.code) {
          reject(new ApiError(0, '未能取得微信登录凭证，请重试'))
          return
        }
        resolve(result.code)
      },
      fail() { reject(new ApiError(0, '无法获取微信登录凭证，请稍后重试')) },
    })
  })
}

async function loginWithWechat() {
  const code = await getWechatCode()
  return request('/auth/wechat-sessions', { method: 'POST', auth: false, data: { code } })
}

async function bindWechat() {
  const token = getToken()
  const code = await getWechatCode()
  if (getToken() !== token) throw new ApiError(409, '登录状态已变化，请重新操作', 'SESSION_CHANGED')
  return request('/users/me/wechat-binding', { method: 'POST', data: { code } })
}

function unbindWechat(currentPassword) {
  return request('/users/me/wechat-binding', { method: 'DELETE', data: { currentPassword } })
}

function logout() {
  return request('/auth/sessions/current', { method: 'DELETE' })
}

module.exports = { ApiError, getToken, saveToken, clearToken, request, login, loginWithWechat,
  getMe, updateMe, bindWechat, unbindWechat, logout,
  uploadImage, downloadMaterialFile, downloadMaterialImage: downloadMaterialFile }
