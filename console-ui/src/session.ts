import { reactive } from 'vue'

const AD_KEY = 'bidpulse.console.adId'
const USER_KEY = 'bidpulse.console.userId'
const FALLBACK_AD_ID = 999999

function readAdId(): number {
  const raw = localStorage.getItem(AD_KEY)
  const value = raw ? Number(raw) : Number.NaN
  return Number.isFinite(value) && value > 0 && value !== FALLBACK_AD_ID ? value : 110
}

export const session = reactive({
  adId: readAdId(),
  userId: localStorage.getItem(USER_KEY) || 'learner-1',
})

/** 竞价后把当前广告/用户带到预算页，避免演示时手动再输一遍。 */
export function remember(adId?: number | null, userId?: string): void {
  if (adId != null && adId !== FALLBACK_AD_ID) {
    session.adId = adId
    localStorage.setItem(AD_KEY, String(adId))
  }
  if (userId != null && userId.trim() !== '') {
    session.userId = userId.trim()
    localStorage.setItem(USER_KEY, session.userId)
  }
}
