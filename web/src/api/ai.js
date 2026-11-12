/**
 * @description AI 核心功能接口 (直连 8083 服务)
 */
import request from '../utils/request'

/**
 * 模型列表。跑 Agent 走 api/agent.js，那边才有会话和审批。
 */
export const aiApi = {
    getModels: () => request.get('/ai/models')
}
