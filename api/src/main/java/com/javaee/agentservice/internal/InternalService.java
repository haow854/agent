package com.javaee.agentservice.internal;

import com.javaee.common.security.RequestUserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 内部服务代理层
 * 实现权限验证、操作审批、审计记录等功能
 * 作为AI Agent与外部系统交互的安全层
 */
@Component
public class InternalService {

    private static final Logger log = LoggerFactory.getLogger(InternalService.class);

    @Autowired
    private RequestUserContext requestUserContext;

    private static final Set<String> DESTRUCTIVE_SKILLS = Set.of("file_version_switch");

    /**
     * 检查权限
     * @param skillName 技能名称
     * @param context 上下文信息
     * @return 是否有权限
     */
    public boolean hasPermission(
            String skillName, Long userId, boolean confirmedAction) {
        log.debug("检查权限: skillName={}", skillName);

        Long authenticatedUserId = requestUserContext.getRequiredUserId();
        String role = requestUserContext.getCurrentRole();

        if (userId == null) {
            log.warn("权限检查失败，缺少有效用户上下文: skillName={}", skillName);
            return false;
        }

        if (!requestUserContext.isAdmin() && !authenticatedUserId.equals(userId)) {
            log.warn("权限检查失败，用户上下文不匹配: authenticated={}, context={}, skillName={}",
                    authenticatedUserId, userId, skillName);
            return false;
        }

        if (DESTRUCTIVE_SKILLS.contains(skillName) && !confirmedAction) {
            log.warn("权限检查失败，危险操作缺少服务端确认: userId={}, skillName={}", userId, skillName);
            return false;
        }

        log.info("用户权限检查通过: userId={}, role={}, skillName={}", userId, role, skillName);
        return true;
    }

    /**
     * 记录审计日志
     * @param skillName 技能名称
     * @param params 参数
     * @param result 结果
     */
    public void logAudit(String skillName, Map<String, Object> params, Map<String, Object> result) {
        log.info("审计记录: skillName={}, params={}, result={}", skillName, params, result);
    }

    /**
     * 发送告警
     * @param alertData 告警数据
     */
    public void sendAlert(Map<String, Object> alertData) {
        log.warn("发送告警: {}", alertData);
    }

}
