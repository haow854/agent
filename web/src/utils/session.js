import { computed, ref } from 'vue'
import { STORAGE_KEYS } from '../constants'

const SESSION_KEYS = [
  STORAGE_KEYS.ACCESS_TOKEN,
  STORAGE_KEYS.REFRESH_TOKEN,
  STORAGE_KEYS.USER_INFO,
  STORAGE_KEYS.GLOBAL_SESSION,
  'userId',
  'userName'
]

const accessToken = ref(localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN) || '')

export const isLoggedIn = computed(() => Boolean(accessToken.value))

export function getAccessToken() {
  return accessToken.value
}

export function saveSession({ accessToken: token, refreshToken, user }) {
  const profile = user || {}
  accessToken.value = token || ''
  localStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, accessToken.value)
  if (refreshToken) {
    localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, refreshToken)
  }
  localStorage.setItem(STORAGE_KEYS.USER_INFO, JSON.stringify(profile))
  if (profile.id) {
    localStorage.setItem('userId', profile.id)
  }
  if (profile.username) {
    localStorage.setItem('userName', profile.username)
  }
}

export function clearSession() {
  accessToken.value = ''
  SESSION_KEYS.forEach(key => localStorage.removeItem(key))
}
