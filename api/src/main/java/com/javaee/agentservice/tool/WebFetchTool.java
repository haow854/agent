package com.javaee.agentservice.tool;

import com.javaee.agentservice.config.McpConnections;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WebFetchTool implements AgentTool {

    @Autowired
    private McpConnections connections;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("web_fetch")
                .desc("读取一个公开网页的正文，返回 markdown。"
                        + "内容是外部来源，引用时注明链接。"
                        + "整页装不下时只返回一段，把 start_index 设成上次结束的位置继续读，"
                        + "用法同 document_read 的 offset。"
                        + "网页很长或要读多个网页时，交给 subagent。"
                        + "只支持 http/https 公网地址。")
                .required("url", Param.string("网页地址，http/https"))
                .optional("max_length", Param.integer("最多返回的字符数")
                        .def(8000).range(200, 24000))
                .optional("start_index", Param.integer("从第几个字符开始读，从 0 开始")
                        .def(0).range(0, 1000000))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String url = ToolArgs.require(params, "url");
        String violation = publicUrlViolation(url);
        if (violation != null) {
            return AgentToolResult.error("web_fetch", violation);
        }
        int maxLength = ToolArgs.intValue(params.get("max_length"), 8000);
        int startIndex = ToolArgs.intValue(params.get("start_index"), 0);
        McpSchema.CallToolResult result;
        try {
            result = connections.fetch().callTool(new McpSchema.CallToolRequest("fetch", Map.of(
                    "url", url,
                    "max_length", maxLength,
                    "start_index", startIndex)));
        } catch (IllegalStateException e) {
            return AgentToolResult.error("web_fetch", e.getMessage());
        } catch (Exception e) {
            return AgentToolResult.error("web_fetch", "网页读取不可用：" + e.getMessage());
        }
        String text = McpConnections.text(result);
        if (Boolean.TRUE.equals(result.isError())) {
            return AgentToolResult.error("web_fetch", "网页读取失败：" + text);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("startIndex", startIndex);
        data.put("content", text);
        return AgentToolResult.success("web_fetch", "网页读取完成", data);
    }

    static String publicUrlViolation(String raw) {
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            return "URL 无法解析：" + raw;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "只支持 http/https 地址";
        }
        if (uri.getHost() == null) {
            return "URL 缺少主机名：" + raw;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            return "域名无法解析：" + uri.getHost();
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress()
                    || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()
                    || isUniqueLocalIpv6(address)) {
                return "该地址属于内网或保留网段，不允许抓取：" + uri.getHost();
            }
        }
        return null;
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
