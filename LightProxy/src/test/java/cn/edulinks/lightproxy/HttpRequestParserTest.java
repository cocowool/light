package cn.edulinks.lightproxy;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class HttpRequestParserTest {

    @Test
    public void testAbsoluteUrlWithPort() {
        String request = "GET http://example.com:8080/api/v1 HTTP/1.1\r\n" +
                "User-Agent: test\r\n\r\n";
        Map<String, String> result = HttpRequestParser.parseRequest(request);
        
        assertEquals("example.com", result.get("host"));
        assertEquals("8080", result.get("port"));
        assertEquals("/api/v1", result.get("path"));
    }

    @Test
    public void testHostHeaderWithIpv6() {
        String request = "GET / HTTP/1.1\r\n" +
                "Host: [::1]:8080\r\n\r\n";
        Map<String, String> result = HttpRequestParser.parseRequest(request);
        
        assertEquals("[::1]", result.get("host"));
        assertEquals("8080", result.get("port"));
        assertEquals("/", result.get("path"));
    }

    @Test
    public void testHostHeaderWithoutPort() {
        String request = "GET /path HTTP/1.1\r\n" +
                "Host: example.com\r\n\r\n";
        Map<String, String> result = HttpRequestParser.parseRequest(request);
        
        assertEquals("example.com", result.get("host"));
        assertEquals("80", result.get("port"));
        assertEquals("/path", result.get("path"));
    }

    @Test
    public void testNoHostHeader() {
        String request = "GET http://192.168.1.1:8080/ HTTP/1.1\r\n\r\n";
        Map<String, String> result = HttpRequestParser.parseRequest(request);
        
        assertEquals("192.168.1.1", result.get("host"));
        assertEquals("8080", result.get("port"));
        assertEquals("/", result.get("path"));
    }

    @Test
    public void testMixedAbsoluteAndHostHeader() {
        String request = "GET http://conflict.com/path HTTP/1.1\r\n" +
                "Host: should.ignore.com\r\n\r\n";
        Map<String, String> result = HttpRequestParser.parseRequest(request);
        
        // 验证绝对URL优先级高于Host头
        assertEquals("conflict.com", result.get("host"));
        assertEquals("/path", result.get("path"));
    }

    @Test
    public void testIpv4WithPortInHost() {
        String request = "GET / HTTP/1.1\r\n" +
                "Host: 127.0.0.1:8080\r\n\r\n";
        Map<String, String> result = HttpRequestParser.parseRequest(request);
        
        assertEquals("127.0.0.1", result.get("host"));
        assertEquals("8080", result.get("port"));
    }

    @Test
    public void testMinimumValidRequest() {
        String request = "GET / HTTP/1.1\r\n\r\n";
        Map<String, String> result = HttpRequestParser.parseRequest(request);
        
        assertNull(result.get("host"));
        assertEquals("80", result.get("port"));
        assertEquals("/", result.get("path"));
    }

    @Test
    public void testHttpsDefaultPort() {
        String request = "GET https://secure.site.com/path HTTP/1.1\r\n\r\n";
        Map<String, String> result = HttpRequestParser.parseRequest(request);
        
        assertEquals("secure.site.com", result.get("host"));
        assertEquals("443", result.get("port")); // 需要扩展代码支持协议判断
        assertEquals("/path", result.get("path"));
    }
}