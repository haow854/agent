package com.javaee.documentservice.client;

import com.javaee.common.exception.BusinessException;
import com.javaee.common.utils.JwtUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 对文档的读写都经协同服务，而不是直接落库。
 *
 * <p>写必须走这里：直接写库时打开着编辑器的那份 ydoc 并不知情，它随后的防抖回存
 * 会把 agent 的改动整片覆盖掉。读也必须走这里，否则同一次 run 里第二次读会读到
 * 尚未回存的旧内容。</p>
 */
@Component
public class CollabClient {

    private static final Logger log = LoggerFactory.getLogger(CollabClient.class);

    private final RestClient http;
    private final String secret;

    public CollabClient(
            @Value("${ai.collab.url:http://localhost:1234}") String baseUrl,
            @Value("${ai.collab.secret:}") String secret) {
        // 必须钉死 HTTP/1.1：RestClient 默认的 JDK 客户端会先试 HTTP/2，而协同服务的
        // HTTP 层是 uWebSockets，只认 1.1，会把升级握手当成非法方法回 405 text/html。
        this.http = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
                .baseUrl(baseUrl)
                .build();
        this.secret = secret;
        // 空 secret 会让协同服务拒掉每一次调用，而下游只看得到一个 forbidden。
        // 启动时说一次，不然这事只能靠猜。
        if (secret == null || secret.isBlank()) {
            log.warn("ai.collab.secret 为空，协同服务将拒绝全部 /ai/* 调用，"
                    + "document_read / replace_text / delete_text 均不可用。"
                    + "本地它来自 .env 的 COLLAB_AI_SECRET，而 .env 按进程工作目录查找。");
        }
    }

    public Map<String, Object> read(String documentId, Long userId, int offset, int limit) {
        return call("/ai/read", userId,
                Map.of("documentId", documentId, "offset", offset, "limit", limit));
    }

    public Map<String, Object> replace(String documentId, Long userId, String oldText, String newText) {
        return call("/ai/replace", userId,
                Map.of("documentId", documentId, "old", oldText, "new", newText));
    }

    public Map<String, Object> delete(String documentId, Long userId, String oldText) {
        return call("/ai/delete", userId, Map.of("documentId", documentId, "old", oldText));
    }

    /** markdown 转协同状态。不带 documentId：那侧是纯函数，不碰任何已存在的文档。 */
    public byte[] convert(String markdown) {
        try {
            byte[] state = http.post()
                    .uri("/ai/convert")
                    .header("X-AI-Secret", secret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("markdown", markdown == null ? "" : markdown))
                    .retrieve()
                    .body(byte[].class);
            if (state == null || state.length == 0) {
                throw new BusinessException("协同服务返回了空的协同状态");
            }
            return state;
        } catch (RestClientException e) {
            throw new BusinessException("协同服务不可达，无法建立文档: " + e.getMessage());
        }
    }

    /**
     * 现签一个该用户的短期 JWT。协同服务回调 Spring 时带的就是它，于是
     * document_access 的授权规则原样生效——不需要给 AI 开一条服务账号旁路。
     */
    private Map<String, Object> call(String path, Long userId, Map<String, Object> body) {
        try {
            Map<String, Object> response = http.post()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION,
                            "Bearer " + JwtUtils.generateToken(userId, "agent"))
                    .header("X-AI-Secret", secret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, res) -> { })
                    .body(new ParameterizedTypeReference<>() { });
            return explainForbidden(response);
        } catch (RestClientException e) {
            throw new BusinessException("协同服务不可达，文档读写暂不可用: " + e.getMessage());
        }
    }

    /**
     * forbidden 是协同服务的 secret 闸，原样传下去只是一个说不出原因的词。
     * 本端 secret 是空还是不匹配，只有这里知道。
     */
    private Map<String, Object> explainForbidden(Map<String, Object> response) {
        if (response == null || !"forbidden".equals(response.get("error"))) {
            return response;
        }
        Map<String, Object> explained = new LinkedHashMap<>(response);
        explained.put("error", secret == null || secret.isBlank()
                ? "本端 ai.collab.secret 未配置，协同服务拒绝了调用"
                : "两侧 COLLAB_AI_SECRET 不一致，协同服务拒绝了调用");
        return explained;
    }
}
