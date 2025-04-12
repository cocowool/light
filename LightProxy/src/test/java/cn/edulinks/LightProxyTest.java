package cn.edulinks;

import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class LightProxyTest {
    private static final int PROXY_PORT = 8080;
    private static final String PROXY_HOST = "localhost";
    private static final String TEST_SERVER_URL = "https://httpbin.org";

    private static ExecutorService proxyExecutor;

    @BeforeAll
    static void startProxyServer() throws InterruptedException {
        // 启动代理服务器线程
        proxyExecutor = Executors.newSingleThreadExecutor();
        proxyExecutor.submit(() -> LightProxy.main(new String[]{}));

        // 等待代理端口可用（优化等待逻辑）
        waitForProxyPort(PROXY_PORT, 10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stopProxyServer() {
        if (proxyExecutor != null) {
            proxyExecutor.shutdownNow();
        }
    }

    /**
     * 轮询检查代理端口是否就绪
     */
    private static void waitForProxyPort(int port, int timeout, TimeUnit unit) throws InterruptedException {
        long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < endTime) {
            try (ServerSocket socket = new ServerSocket(port)) {
                socket.close(); // 端口未被占用，说明代理未启动
                Thread.sleep(500);
            } catch (IOException e) {
                // 端口被占用，说明代理已启动
                return;
            }
        }
        throw new IllegalStateException("代理服务器未在指定时间内启动");
    }

    /**
     * 创建配置代理的 HTTP 客户端
     */
    private CloseableHttpClient createProxyClient() {
        HttpHost proxy = new HttpHost(PROXY_HOST, PROXY_PORT);
        DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(proxy);
        return HttpClients.custom()
                .setRoutePlanner(routePlanner)
                .build();
    }

    @Test
    void testGetRequestViaProxy() throws Exception {
        try (CloseableHttpClient client = createProxyClient()) {
            HttpGet request = new HttpGet(TEST_SERVER_URL + "/get");
            try (CloseableHttpResponse response = client.execute(request)) {
                assertEquals(200, response.getCode(), "GET 请求响应状态码应为200");
                String body = EntityUtils.toString(response.getEntity());
                assertTrue(body.contains("\"url\": \"" + TEST_SERVER_URL + "/get\""), "响应应包含请求 URL");
            }
        }
    }

    @Test
    void testPostRequestViaProxy() throws Exception {
        try (CloseableHttpClient client = createProxyClient()) {
            HttpPost request = new HttpPost(TEST_SERVER_URL + "/post");
            request.setEntity(new StringEntity("{\"data\": \"test\"}"));
            request.setHeader("Content-Type", "application/json");

            try (CloseableHttpResponse response = client.execute(request)) {
                assertEquals(200, response.getCode(), "POST 请求响应状态码应为200");
                String body = EntityUtils.toString(response.getEntity());
                assertTrue(body.contains("\"data\": \"test\""), "响应应包含 POST 数据");
            }
        }
    }

    @Test
    void testPutRequestViaProxy() throws Exception {
        try (CloseableHttpClient client = createProxyClient()) {
            HttpPut request = new HttpPut(TEST_SERVER_URL + "/put");
            request.setEntity(new StringEntity("{\"action\": \"update\"}"));
            request.setHeader("Content-Type", "application/json");

            try (CloseableHttpResponse response = client.execute(request)) {
                assertEquals(200, response.getCode(), "PUT 请求响应状态码应为200");
                String body = EntityUtils.toString(response.getEntity());
                assertTrue(body.contains("\"action\": \"update\""), "响应应包含 PUT 数据");
            }
        }
    }
}