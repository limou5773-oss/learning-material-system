import { session } from './auth'

interface ApiEnvelope<T> {
  data: T
  requestId: string
}

interface ApiProblem {
  error?: { code?: string; message?: string; currentVersion?: number; existingMaterialId?: string }
  requestId?: string
}

export class ApiRequestError extends Error {
  constructor(public readonly status: number, message: string, public readonly currentVersion?: number,
              public readonly existingMaterialId?: string) {
    super(message)
  }
}

export async function api<T>(path: string, method = 'GET', body?: object): Promise<T> {
  const headers: Record<string, string> = {}
  if (body) headers['Content-Type'] = 'application/json'
  if (session.value) headers.Authorization = `Bearer ${session.value.accessToken}`

  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    })
  } catch {
    throw new ApiRequestError(0, '无法连接服务器，请检查后端是否启动')
  }

  if (response.status === 204) return undefined as T
  const payload = (await response.json()) as ApiEnvelope<T> & ApiProblem
  if (!response.ok) {
    throw new ApiRequestError(response.status, payload.error?.message || '请求失败，请稍后重试',
      payload.error?.currentVersion, payload.error?.existingMaterialId)
  }
  return payload.data
}

export async function uploadFile<T>(path: string, form: FormData): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, {
      method: 'POST',
      headers: session.value ? { Authorization: `Bearer ${session.value.accessToken}` } : {},
      body: form,
    })
  } catch { throw new ApiRequestError(0, '无法连接服务器，请检查后端是否启动') }
  const payload = (await response.json()) as ApiEnvelope<T> & ApiProblem
  if (!response.ok) {
    throw new ApiRequestError(response.status, payload.error?.message || '上传失败',
      payload.error?.currentVersion, payload.error?.existingMaterialId)
  }
  return payload.data
}

export async function materialFileBlob(id: string): Promise<Blob> {
  let response: Response
  try {
    response = await fetch(`/api/v1/materials/${encodeURIComponent(id)}/file`, {
      headers: session.value ? { Authorization: `Bearer ${session.value.accessToken}` } : {},
      cache: 'no-store',
    })
  } catch { throw new ApiRequestError(0, '无法连接服务器，请检查后端是否启动') }
  if (!response.ok) {
    const payload = await response.json().catch(() => ({})) as ApiProblem
    throw new ApiRequestError(response.status, payload.error?.message || '文件读取失败')
  }
  return response.blob()
}
