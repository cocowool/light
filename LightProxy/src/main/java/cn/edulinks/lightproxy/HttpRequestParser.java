package cn.edulinks.lightproxy;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpRequestParser {
    // 匹配请求行的正则表达式（GET /path HTTP/1.1）
    private static final Pattern REQUEST_LINE_PATTERN = 
        Pattern.compile("^(GET|POST|PUT|DELETE|HEAD|OPTIONS)\\s+(\\S+).*");
    
    // 匹配绝对URL的正则表达式（http://host:port/path）
    private static final Pattern ABSOLUTE_URL_PATTERN = 
        Pattern.compile("^(https?)://([^:/?#]+)(?::(\\d+))?(/\\S*)?");
    
    // 匹配Host头中的IPv6地址（如 [::1]:8080）
    private static final Pattern IPV6_HOST_PATTERN = 
        Pattern.compile("^\\[(.*)](?::(\\d+))?$");

    public static void main(String[] args) {
        String httpRequest = "GET /api/v1/users HTTP/1.1\r\n" +
                "Host: example.com:8080\r\n" +
                "User-Agent: test-client\r\n\r\n";
        
        Map<String, String> result = parseRequest(httpRequest);
        System.out.println("Host: " + result.get("host"));
        System.out.println("Port: " + result.get("port"));
        System.out.println("Path: " + result.get("path"));
    }

    public static Map<String, String> parseRequest(String request) {
        Map<String, String> headers = new HashMap<>();
        Map<String, String> result = new HashMap<>();
        result.put("port", "80"); // 默认端口
        
        String[] lines = request.split("\r?\n");
        String path = "";

        // 解析请求行
        Matcher requestLineMatcher = REQUEST_LINE_PATTERN.matcher(lines[0]);
        if (requestLineMatcher.find()) {
            path = requestLineMatcher.group(2);
        }

        // 解析请求头
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) break;
            
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                headers.put(key, value);
            }
        }

        // 尝试从绝对URL解析主机信息
        Matcher urlMatcher = ABSOLUTE_URL_PATTERN.matcher(path);
        if (urlMatcher.find()) {
            result.put("host", urlMatcher.group(2));
            if (urlMatcher.group(3) != null) {
                result.put("port", urlMatcher.group(3));
            }
            result.put("path", urlMatcher.group(4) != null ? urlMatcher.group(4) : "/");
            return result;
        }

        // 从Host头解析主机信息
        String hostHeader = headers.get("Host");
        if (hostHeader != null && !hostHeader.isEmpty()) {
            // 处理IPv6地址格式
            Matcher ipv6Matcher = IPV6_HOST_PATTERN.matcher(hostHeader);
            if (ipv6Matcher.find()) {
                result.put("host", "[" + ipv6Matcher.group(1) + "]");
                if (ipv6Matcher.group(2) != null) {
                    result.put("port", ipv6Matcher.group(2));
                }
            } else {
                String[] hostParts = hostHeader.split(":");
                result.put("host", hostParts[0]);
                if (hostParts.length > 1) {
                    result.put("port", hostParts[1]);
                }
            }
        }

        result.put("path", path);
        return result;
    }
}