import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || null)
  const username = ref(localStorage.getItem('username') || null)

  const setAuth = (newToken, newUsername) => {
    token.value = newToken
    username.value = newUsername
    localStorage.setItem('token', newToken)
    localStorage.setItem('username', newUsername)
  }

  const clearAuth = () => {
    token.value = null
    username.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('username')
  }

  // Decode the JWT payload and check its `exp` claim (seconds since epoch).
  const isTokenExpired = (jwt) => {
    try {
      const payload = JSON.parse(
        atob(jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))
      )
      if (!payload.exp) return false
      return payload.exp * 1000 <= Date.now()
    } catch (e) {
      // Malformed token -> treat as invalid/expired
      return true
    }
  }

  const isAuthenticated = () => {
    if (!token.value) return false
    if (isTokenExpired(token.value)) {
      clearAuth()
      return false
    }
    return true
  }

  return {
    token,
    username,
    setAuth,
    clearAuth,
    isAuthenticated
  }
})
