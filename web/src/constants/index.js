/**
 * @description 全局通用常量类
 */

// 正则表达式常量
export const REGEX = {
  EMAIL: /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/,
  PHONE: /^1[3-9]\d{9}$/,
  PASSWORD_FULL: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$/,

  HAS_UPPER: /[A-Z]/,        // An uppercase letter
  HAS_LOWER: /[a-z]/,        // A lowercase letter
  HAS_NUMBER: /\d/,          // 包含数字
  HAS_SPECIAL: /[@$!%*?&]/   // 包含特殊符号
}

// 浏览器缓存 Key 常量
export const STORAGE_KEYS = {
  ACCESS_TOKEN: 'accessToken',
  REFRESH_TOKEN: 'refreshToken',
  USER_INFO: 'userInfo',
  // 当前 Agent 会话。侧栏和全局助理都要读写它，写成字面量迟早会写岔。
  GLOBAL_SESSION: 'global_session_id'
}

// 默认模型。两个对话入口各自硬编码过一份，改一处漏一处，提到这里。
export const DEFAULT_MODEL = 'deepseek-v4-pro'

// 后端响应状态码常量
export const RES_CODE = {
  SUCCESS: 200,
  UNAUTHORIZED: 401,
  INTERNAL_SERVER_ERROR: 500
}