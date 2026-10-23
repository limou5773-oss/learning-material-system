import { ref } from 'vue'

export interface User {
  id: string
  username: string
  displayName: string
  role: 'USER' | 'ADMIN'
  status: 'ENABLED' | 'DISABLED'
  hasWechatBinding: boolean
  createdAt: string
}

export interface SessionToken {
  accessToken: string
  tokenType: 'Bearer'
  expiresAt: string
  user: User
}

// D02 keeps the token only in memory. Refreshing the page requires a new login.
export const session = ref<SessionToken | null>(null)

export function clearSession() {
  session.value = null
}
