package cn.edulinks;

import java.io.*;
import java.util.Properties;

/**
 * LightProxy 配置管理器
 *
 * 当前从 light.properties 文件加载配置。
 * 未来可扩展：支持从 UI 修改、热加载、多配置源等。
 *
 * 设计原则：所有配置项都有合理的默认值，即使配置文件不存在也能正常运行。
 */
public class ProxyConfig {

    private static final String DEFAULT_CONFIG_FILE = "light.properties";

    // HTTP 代理
    private boolean httpEnabled = true;
    private int httpPort = 8080;

    // SOCKS5 代理
    private boolean socks5Enabled = true;
    private int socks5Port = 1080;

    // SOCKS4 代理
    private boolean socks4Enabled = false;
    private int socks4Port = 1081;

    // 通用
    private int threadPoolMax = 0;      // 0 = 无上限
    private int socketTimeout = 30000;  // 30s
    private int connectTimeout = 10000; // 10s

    // 配置文件路径
    private String configPath;

    public ProxyConfig() {
        this(null);
    }

    public ProxyConfig(String configPath) {
        this.configPath = configPath != null ? configPath : DEFAULT_CONFIG_FILE;
        load();
    }

    /**
     * 从 properties 文件加载配置
     */
    public void load() {
        Properties props = new Properties();

        // 尝试从 classpath 加载
        InputStream is = getClass().getClassLoader().getResourceAsStream(configPath);
        if (is == null) {
            // 尝试从文件系统加载（方便开发调试）
            try {
                File f = new File(configPath);
                if (f.exists()) {
                    is = new FileInputStream(f);
                }
            } catch (FileNotFoundException ignored) {
            }
        }

        if (is != null) {
            try {
                props.load(is);
                System.out.println("[Config] Loaded from: " + configPath);
            } catch (IOException e) {
                System.out.println("[Config] Failed to load config, using defaults.");
            } finally {
                try { is.close(); } catch (IOException ignored) {}
            }
        } else {
            System.out.println("[Config] Config file not found, using defaults.");
        }

        // 解析配置
        httpEnabled = Boolean.parseBoolean(props.getProperty("http.enabled", "true"));
        httpPort = parseInt(props.getProperty("http.port", "8080"), 8080);

        socks5Enabled = Boolean.parseBoolean(props.getProperty("socks5.enabled", "true"));
        socks5Port = parseInt(props.getProperty("socks5.port", "1080"), 1080);

        socks4Enabled = Boolean.parseBoolean(props.getProperty("socks4.enabled", "false"));
        socks4Port = parseInt(props.getProperty("socks4.port", "1081"), 1081);

        threadPoolMax = parseInt(props.getProperty("thread.pool.max", "0"), 0);
        socketTimeout = parseInt(props.getProperty("socket.timeout", "30000"), 30000);
        connectTimeout = parseInt(props.getProperty("connect.timeout", "10000"), 10000);
    }

    /**
     * 保存配置到文件
     * 未来 UI 修改配置后调用此方法持久化
     */
    public void save() throws IOException {
        saveTo(configPath);
    }

    public void saveTo(String path) throws IOException {
        Properties props = new Properties();
        props.setProperty("http.enabled", String.valueOf(httpEnabled));
        props.setProperty("http.port", String.valueOf(httpPort));
        props.setProperty("socks5.enabled", String.valueOf(socks5Enabled));
        props.setProperty("socks5.port", String.valueOf(socks5Port));
        props.setProperty("socks4.enabled", String.valueOf(socks4Enabled));
        props.setProperty("socks4.port", String.valueOf(socks4Port));
        props.setProperty("thread.pool.max", String.valueOf(threadPoolMax));
        props.setProperty("socket.timeout", String.valueOf(socketTimeout));
        props.setProperty("connect.timeout", String.valueOf(connectTimeout));

        try (FileOutputStream fos = new FileOutputStream(path)) {
            props.store(fos, "LightProxy Configuration - " + java.time.LocalDateTime.now());
        }
        System.out.println("[Config] Saved to: " + path);
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // --- Getters ---
    public boolean isHttpEnabled() { return httpEnabled; }
    public int getHttpPort() { return httpPort; }
    public boolean isSocks5Enabled() { return socks5Enabled; }
    public int getSocks5Port() { return socks5Port; }
    public int getThreadPoolMax() { return threadPoolMax; }
    public int getSocketTimeout() { return socketTimeout; }
    public int getConnectTimeout() { return connectTimeout; }
    public String getConfigPath() { return configPath; }
    public boolean isSocks4Enabled() { return socks4Enabled; }
    public int getSocks4Port() { return socks4Port; }

    // --- Setters (for future UI) ---
    public void setHttpEnabled(boolean enabled) { this.httpEnabled = enabled; }
    public void setHttpPort(int port) { this.httpPort = port; }
    public void setSocks5Enabled(boolean enabled) { this.socks5Enabled = enabled; }
    public void setSocks5Port(int port) { this.socks5Port = port; }
    public void setThreadPoolMax(int max) { this.threadPoolMax = max; }
    public void setSocketTimeout(int timeout) { this.socketTimeout = timeout; }
    public void setConnectTimeout(int timeout) { this.connectTimeout = timeout; }
    public void setSocks4Enabled(boolean enabled) { this.socks4Enabled = enabled; }
    public void setSocks4Port(int port) { this.socks4Port = port; }

    /**
     * 打印当前配置（调试用）
     */
    public void printSummary() {
        System.out.println("=== LightProxy Config ===");
        System.out.println("  HTTP Proxy:    " + (httpEnabled ? "enabled (port " + httpPort + ")" : "disabled"));
        System.out.println("  SOCKS5 Proxy:  " + (socks5Enabled ? "enabled (port " + socks5Port + ")" : "disabled"));
        System.out.println("  SOCKS4 Proxy:  " + (socks4Enabled ? "enabled (port " + socks4Port + ")" : "disabled"));
        System.out.println("  Socket Timeout:   " + socketTimeout + "ms");
        System.out.println("  Connect Timeout:  " + connectTimeout + "ms");
        System.out.println("========================");
    }
}
