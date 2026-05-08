package com.apk.editor.mcp;

import android.content.Context;
import android.util.Log;

import in.sunilpaulmathew.sCommon.CommonUtils.sCommonUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP-compatible local server for AI tooling.
 *
 * Supported transports:
 *   1. Streamable HTTP: POST /mcp, optionally with Accept: text/event-stream.
 *   2. Legacy SSE: GET /sse, then POST JSON-RPC to the emitted /messages?sessionId=... endpoint.
 *   3. Plain JSON-RPC HTTP: POST /mcp or /jsonrpc.
 *
 * The server is intentionally bound to 127.0.0.1 only. Desktop clients can reach it via:
 *   adb forward tcp:8765 tcp:8765
 */
public final class McpServer {

    public static final int DEFAULT_PORT = 8765;
    private static final String TAG = "AEE-MCP";
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final int DEFAULT_READ_LIMIT = 512 * 1024;
    private static final int MAX_READ_LIMIT = 2 * 1024 * 1024;
    private static final int DEFAULT_SEARCH_LIMIT = 100;
    private static final int MAX_SEARCH_LIMIT = 1000;
    private static final long SSE_KEEPALIVE_MS = 15000L;
    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;
    private static final int MAX_LOG_ENTRIES = 200;
    private static final List<String> sLogs = Collections.synchronizedList(new ArrayList<>());

    private static McpServer sInstance;

    private final Context mAppContext;
    private final File mProjectsRoot;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final ExecutorService mClientExecutor = Executors.newCachedThreadPool();
    private final Map<String, SseSession> mSseSessions = new ConcurrentHashMap<>();
    private ServerSocket mServerSocket;
    private Thread mAcceptThread;
    private int mPort = DEFAULT_PORT;

    private McpServer(Context context) {
        mAppContext = context.getApplicationContext();
        mProjectsRoot = mAppContext.getCacheDir();
    }

    public static synchronized McpServer get(Context context) {
        if (sInstance == null) {
            sInstance = new McpServer(context);
        }
        return sInstance;
    }

    public static void start(Context context) {
        get(context).start(sCommonUtils.getInt("mcpServerPort", DEFAULT_PORT, context));
    }

    public synchronized void start(int port) {
        int safePort = normalizePort(port);
        if (mRunning.get() && mPort == safePort) return;
        if (mRunning.get()) stop();
        try {
            mServerSocket = new ServerSocket(safePort, 50, InetAddress.getByName("127.0.0.1"));
            mPort = safePort;
            mRunning.set(true);
            mAcceptThread = new Thread(() -> acceptLoop(safePort), "AEE MCP Server");
            mAcceptThread.setDaemon(true);
            mAcceptThread.start();
            addLog("SERVER", "started on 127.0.0.1:" + safePort);
            Log.i(TAG, "MCP server listening on 127.0.0.1:" + safePort);
        } catch (IOException e) {
            mRunning.set(false);
            addLog("ERROR", "failed to start on port " + safePort + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            Log.e(TAG, "Failed to start MCP server", e);
        }
    }

    public synchronized void stop() {
        mRunning.set(false);
        for (SseSession session : mSseSessions.values()) {
            session.close();
        }
        mSseSessions.clear();
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException ignored) {
            }
        }
        mServerSocket = null;
        addLog("SERVER", "stopped");
    }

    public boolean isRunning() {
        return mRunning.get();
    }

    public int getPort() {
        return mPort;
    }

    public static int normalizePort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT ? port : DEFAULT_PORT;
    }

    public static List<String> getLogs() {
        synchronized (sLogs) {
            return new ArrayList<>(sLogs);
        }
    }

    public static void clearLogs() {
        synchronized (sLogs) {
            sLogs.clear();
        }
    }

    private static void addLog(String type, String message) {
        String entry = String.format(Locale.US, "%1$tH:%1$tM:%1$tS  %2$s  %3$s", System.currentTimeMillis(), type, message);
        synchronized (sLogs) {
            sLogs.add(0, entry);
            while (sLogs.size() > MAX_LOG_ENTRIES) {
                sLogs.remove(sLogs.size() - 1);
            }
        }
        Log.d(TAG, entry);
    }

    private void acceptLoop(int port) {
        while (mRunning.get()) {
            try {
                Socket socket = mServerSocket.accept();
                socket.setSoTimeout(15000);
                mClientExecutor.execute(() -> handleSocket(socket));
            } catch (IOException e) {
                if (mRunning.get()) {
                    Log.e(TAG, "Accept failed on port " + port, e);
                }
            }
        }
    }

    private void handleSocket(Socket socket) {
        try (Socket s = socket;
             InputStream input = new BufferedInputStream(s.getInputStream());
             OutputStream output = new BufferedOutputStream(s.getOutputStream())) {
            HttpRequest request = HttpRequest.read(input);
            if (request == null) return;
            addLog("HTTP", request.method + " " + request.path);

            if ("OPTIONS".equals(request.method)) {
                writeOptions(output);
                return;
            }

            if ("GET".equals(request.method) && ("/".equals(request.path) || "/health".equals(request.path))) {
                JSONObject health = new JSONObject()
                        .put("status", "ok")
                        .put("name", "APK Explorer & Editor MCP")
                        .put("endpoint", "/mcp")
                        .put("sseEndpoint", "/sse")
                        .put("port", mPort)
                        .put("projectsRoot", mProjectsRoot.getAbsolutePath())
                        .put("transports", new JSONArray()
                                .put("streamable-http")
                                .put("sse")
                                .put("plain-jsonrpc"));
                writeJson(output, 200, health);
                return;
            }

            if ("GET".equals(request.method) && ("/sse".equals(request.path) || ("/mcp".equals(request.path) && acceptsSse(request)))) {
                handleSseOpen(socket, output, "/mcp".equals(request.path));
                return;
            }

            if ("POST".equals(request.method) && "/messages".equals(request.path)) {
                handleSseMessage(request, output);
                return;
            }

            if ("POST".equals(request.method) && ("/mcp".equals(request.path) || "/jsonrpc".equals(request.path))) {
                JSONObject response = handleJsonRpc(new JSONObject(new String(request.body, StandardCharsets.UTF_8)));
                if (response == null) return;
                if (acceptsSse(request)) {
                    writeSseResponse(output, response);
                } else {
                    writeJson(output, 200, response);
                }
                return;
            }

            writeText(output, 404, "Not Found");
        } catch (SocketTimeoutException ignored) {
        } catch (Exception e) {
            addLog("ERROR", e.getMessage() == null ? e.toString() : e.getMessage());
            try {
                JSONObject error = errorResponse(null, -32603, e.getMessage() == null ? e.toString() : e.getMessage());
                writeJson(new BufferedOutputStream(socket.getOutputStream()), 200, error);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Legacy MCP SSE transport. The first event tells the client where JSON-RPC messages must be POSTed.
     */
    private void handleSseOpen(Socket socket, OutputStream output, boolean streamableGet) throws IOException {
        socket.setSoTimeout(0);
        String sessionId = UUID.randomUUID().toString();
        SseSession session = new SseSession(sessionId, output);
        mSseSessions.put(sessionId, session);
        writeSseHeaders(output);
        if (streamableGet) {
            session.comment("streamable-http session " + sessionId);
        } else {
            session.event("endpoint", "/messages?sessionId=" + sessionId);
        }
        try {
            while (mRunning.get() && session.isOpen()) {
                sleep(SSE_KEEPALIVE_MS);
                if (session.isOpen()) {
                    session.comment("keepalive");
                }
            }
        } finally {
            mSseSessions.remove(sessionId);
            session.close();
        }
    }

    private void handleSseMessage(HttpRequest request, OutputStream output) throws Exception {
        String sessionId = request.queryParam("sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            writeText(output, 400, "Missing sessionId");
            return;
        }
        SseSession session = mSseSessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            writeText(output, 404, "Unknown SSE session");
            return;
        }
        JSONObject response = handleJsonRpc(new JSONObject(new String(request.body, StandardCharsets.UTF_8)));
        if (response != null) {
            session.event("message", response.toString());
        }
        writeText(output, 202, "Accepted");
    }

    private boolean acceptsSse(HttpRequest request) {
        String accept = request.header("accept");
        return accept != null && accept.toLowerCase(Locale.US).contains("text/event-stream");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private JSONObject handleJsonRpc(JSONObject request) throws JSONException {
        boolean isNotification = !request.has("id") || request.isNull("id");
        Object id = isNotification ? null : request.opt("id");
        String method = request.optString("method", "");
        addLog("RPC", method.isEmpty() ? "<empty>" : method);
        JSONObject params = request.optJSONObject("params");
        if (params == null) params = new JSONObject();

        try {
            switch (method) {
                case "initialize":
                    return successResponse(id, initializeResult());
                case "notifications/initialized":
                    return null;
                case "ping":
                    return isNotification ? null : successResponse(id, new JSONObject());
                case "tools/list":
                    return isNotification ? null : successResponse(id, new JSONObject().put("tools", tools()));
                case "tools/call":
                    return isNotification ? null : successResponse(id, callTool(params));
                case "resources/list":
                    return isNotification ? null : successResponse(id, resourcesList());
                case "resources/read":
                    return isNotification ? null : successResponse(id, resourcesRead(params));
                default:
                    return isNotification ? null : errorResponse(id, -32601, "Unknown method: " + method);
            }
        } catch (IllegalArgumentException e) {
            return isNotification ? null : errorResponse(id, -32602, e.getMessage());
        } catch (Exception e) {
            return isNotification ? null : errorResponse(id, -32603, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private JSONObject initializeResult() throws JSONException {
        return new JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("serverInfo", new JSONObject()
                        .put("name", "apk-explorer-editor-mcp")
                        .put("version", "0.2.0"))
                .put("capabilities", new JSONObject()
                        .put("tools", new JSONObject())
                        .put("resources", new JSONObject()));
    }

    private JSONArray tools() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("list_projects", "List decompiled APK projects currently stored by APK Explorer & Editor.", new JSONObject()));
        tools.put(tool("get_project_info", "Return metadata for a decompiled APK project.", new JSONObject()
                .put("project", "Project path, folder name, or package name. Optional: latest project is used when omitted.")));
        tools.put(tool("list_files", "List files/directories inside a decompiled APK project.", new JSONObject()
                .put("project", "Project path, folder name, or package name. Optional.")
                .put("path", "Relative directory path inside the project. Optional, defaults to root.")));
        tools.put(tool("read_file", "Read a UTF-8/text file from a decompiled APK project.", new JSONObject()
                .put("project", "Project path, folder name, or package name. Optional.")
                .put("path", "Relative file path inside the project.")
                .put("maxBytes", "Optional maximum bytes, default 524288, max 2097152.")));
        tools.put(tool("write_file", "Write a UTF-8/text file inside a decompiled APK project. Creates parent directories as needed.", new JSONObject()
                .put("project", "Project path, folder name, or package name. Optional.")
                .put("path", "Relative file path inside the project.")
                .put("content", "New UTF-8 file content.")));
        tools.put(tool("search_files", "Search text in project files by substring, with optional file name filter.", new JSONObject()
                .put("project", "Project path, folder name, or package name. Optional.")
                .put("query", "Text to search for.")
                .put("glob", "Optional simple filename suffix/contains filter, e.g. .smali or AndroidManifest.xml.")
                .put("maxResults", "Optional result limit, default 100, max 1000.")));
        tools.put(tool("replace_in_file", "Replace literal text in a project file and return replacement count.", new JSONObject()
                .put("project", "Project path, folder name, or package name. Optional.")
                .put("path", "Relative file path inside the project.")
                .put("old", "Literal text to replace.")
                .put("new", "Replacement text.")));
        return tools;
    }

    private JSONObject tool(String name, String description, JSONObject properties) throws JSONException {
        JSONObject props = new JSONObject();
        JSONArray required = new JSONArray();
        JSONArray names = properties.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.getString(i);
                props.put(key, new JSONObject().put("type", inferType(key)).put("description", properties.getString(key)));
                if ("path".equals(key) || "content".equals(key) || "query".equals(key) || "old".equals(key) || "new".equals(key)) {
                    required.put(key);
                }
            }
        }
        return new JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", new JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", required));
    }

    private String inferType(String key) {
        if ("maxBytes".equals(key) || "maxResults".equals(key)) return "integer";
        return "string";
    }

    private JSONObject callTool(JSONObject params) throws Exception {
        String name = params.optString("name", "");
        JSONObject args = params.optJSONObject("arguments");
        addLog("TOOL", name.isEmpty() ? "<empty>" : name);
        if (args == null) args = new JSONObject();

        JSONObject result;
        switch (name) {
            case "list_projects":
                result = listProjects();
                break;
            case "get_project_info":
                result = getProjectInfo(resolveProject(args.optString("project", null)));
                break;
            case "list_files":
                result = listFiles(resolveProject(args.optString("project", null)), args.optString("path", ""));
                break;
            case "read_file":
                result = readFile(resolveProject(args.optString("project", null)), requiredString(args, "path"), args.optInt("maxBytes", DEFAULT_READ_LIMIT));
                break;
            case "write_file":
                result = writeFile(resolveProject(args.optString("project", null)), requiredString(args, "path"), requiredString(args, "content"));
                break;
            case "search_files":
                result = searchFiles(resolveProject(args.optString("project", null)), requiredString(args, "query"), args.optString("glob", null), args.optInt("maxResults", DEFAULT_SEARCH_LIMIT));
                break;
            case "replace_in_file":
                result = replaceInFile(resolveProject(args.optString("project", null)), requiredString(args, "path"), requiredString(args, "old"), requiredString(args, "new"));
                break;
            default:
                throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return new JSONObject()
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "text")
                        .put("text", result.toString(2))))
                .put("structuredContent", result)
                .put("isError", false);
    }

    private JSONObject resourcesList() throws Exception {
        JSONArray resources = new JSONArray();
        for (File project : getProjects()) {
            resources.put(new JSONObject()
                    .put("uri", "aee://project/" + project.getName())
                    .put("name", project.getName())
                    .put("mimeType", "application/json"));
        }
        return new JSONObject().put("resources", resources);
    }

    private JSONObject resourcesRead(JSONObject params) throws Exception {
        String uri = requiredString(params, "uri");
        if (!uri.startsWith("aee://project/")) {
            throw new IllegalArgumentException("Unsupported resource URI: " + uri);
        }
        File project = resolveProject(uri.substring("aee://project/".length()));
        return new JSONObject().put("contents", new JSONArray().put(new JSONObject()
                .put("uri", uri)
                .put("mimeType", "application/json")
                .put("text", getProjectInfo(project).toString(2))));
    }

    private JSONObject listProjects() throws Exception {
        JSONArray array = new JSONArray();
        for (File project : getProjects()) {
            array.put(projectSummary(project));
        }
        return new JSONObject()
                .put("projectsRoot", mProjectsRoot.getAbsolutePath())
                .put("count", array.length())
                .put("projects", array);
    }

    private JSONObject getProjectInfo(File project) throws Exception {
        JSONObject data = readAppData(project);
        return projectSummary(project)
                .put("appData", data)
                .put("backupPath", new File(project, ".aeeBackup").getAbsolutePath());
    }

    private JSONObject listFiles(File project, String relativePath) throws Exception {
        File dir = resolveInsideProject(project, relativePath == null ? "" : relativePath);
        if (!dir.isDirectory()) throw new IllegalArgumentException("Not a directory: " + relativePath);
        JSONArray files = new JSONArray();
        File[] children = dir.listFiles();
        if (children != null) {
            List<File> sorted = new ArrayList<>();
            Collections.addAll(sorted, children);
            Collections.sort(sorted, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File child : sorted) {
                files.put(fileInfo(project, child));
            }
        }
        return new JSONObject()
                .put("project", project.getName())
                .put("path", relative(project, dir))
                .put("files", files);
    }

    private JSONObject readFile(File project, String relativePath, int maxBytes) throws Exception {
        int limit = Math.max(1, Math.min(maxBytes, MAX_READ_LIMIT));
        File file = resolveInsideProject(project, relativePath);
        if (!file.isFile()) throw new IllegalArgumentException("Not a file: " + relativePath);
        if (file.length() > limit) {
            throw new IllegalArgumentException("File is too large (" + file.length() + " bytes). Increase maxBytes up to " + MAX_READ_LIMIT + " or read a smaller file.");
        }
        byte[] bytes = readAll(file, limit);
        return new JSONObject()
                .put("project", project.getName())
                .put("path", relative(project, file))
                .put("size", file.length())
                .put("content", new String(bytes, StandardCharsets.UTF_8));
    }

    private JSONObject writeFile(File project, String relativePath, String content) throws Exception {
        File file = resolveInsideProject(project, relativePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
        }
        if (file.getName().endsWith(".smali")) {
            markSmaliEdited(project);
        }
        return new JSONObject()
                .put("project", project.getName())
                .put("path", relative(project, file))
                .put("bytesWritten", bytes.length);
    }

    private JSONObject searchFiles(File project, String query, String glob, int maxResults) throws Exception {
        if (query.isEmpty()) throw new IllegalArgumentException("query must not be empty");
        int limit = Math.max(1, Math.min(maxResults, MAX_SEARCH_LIMIT));
        JSONArray results = new JSONArray();
        searchRecursive(project, project, query, glob, limit, results);
        return new JSONObject()
                .put("project", project.getName())
                .put("query", query)
                .put("count", results.length())
                .put("results", results);
    }

    private void searchRecursive(File project, File current, String query, String glob, int limit, JSONArray results) throws Exception {
        if (results.length() >= limit || current.getName().equals(".aeeBackup")) return;
        if (current.isDirectory()) {
            File[] children = current.listFiles();
            if (children == null) return;
            for (File child : children) {
                if (results.length() >= limit) return;
                searchRecursive(project, child, query, glob, limit, results);
            }
            return;
        }
        if (!current.isFile() || current.length() > DEFAULT_READ_LIMIT || !matchesGlob(current, glob)) return;
        String text = new String(readAll(current, DEFAULT_READ_LIMIT), StandardCharsets.UTF_8);
        int index = text.indexOf(query);
        if (index < 0) return;
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        int start = Math.max(0, index - 120);
        int end = Math.min(text.length(), index + query.length() + 120);
        results.put(new JSONObject()
                .put("path", relative(project, current))
                .put("line", line)
                .put("preview", text.substring(start, end)));
    }

    private JSONObject replaceInFile(File project, String relativePath, String oldText, String newText) throws Exception {
        if (oldText.isEmpty()) throw new IllegalArgumentException("old must not be empty");
        File file = resolveInsideProject(project, relativePath);
        if (!file.isFile()) throw new IllegalArgumentException("Not a file: " + relativePath);
        String text = new String(readAll(file, MAX_READ_LIMIT), StandardCharsets.UTF_8);
        int count = countOccurrences(text, oldText);
        if (count > 0) {
            writeFile(project, relativePath, text.replace(oldText, newText));
        }
        return new JSONObject()
                .put("project", project.getName())
                .put("path", relative(project, file))
                .put("replacements", count);
    }

    private int countOccurrences(String text, String part) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(part, index)) >= 0) {
            count++;
            index += part.length();
        }
        return count;
    }

    private boolean matchesGlob(File file, String glob) {
        if (glob == null || glob.trim().isEmpty()) return true;
        String g = glob.trim().toLowerCase(Locale.US).replace("*", "");
        String name = file.getName().toLowerCase(Locale.US);
        String path = file.getAbsolutePath().toLowerCase(Locale.US);
        return name.contains(g) || path.endsWith(g) || path.contains(g);
    }

    private File resolveProject(String project) throws Exception {
        List<File> projects = getProjects();
        if (projects.isEmpty()) throw new IllegalArgumentException("No decompiled projects found. Explore/decompile an APK first.");
        if (project == null || project.trim().isEmpty() || "latest".equalsIgnoreCase(project.trim())) {
            return Collections.max(projects, Comparator.comparingLong(File::lastModified));
        }
        String wanted = project.trim();
        File candidate = new File(wanted);
        if (!candidate.isAbsolute()) candidate = new File(mProjectsRoot, wanted);
        try {
            if (isProject(candidate) && isInside(mProjectsRoot, candidate)) return candidate.getCanonicalFile();
        } catch (IOException ignored) {
        }
        for (File item : projects) {
            JSONObject data = readAppData(item);
            if (item.getName().equals(wanted)
                    || item.getAbsolutePath().equals(wanted)
                    || data.optString("package_name").equals(wanted)
                    || data.optString("app_name").equals(wanted)) {
                return item.getCanonicalFile();
            }
        }
        throw new IllegalArgumentException("Project not found: " + wanted);
    }

    private List<File> getProjects() throws IOException {
        List<File> projects = new ArrayList<>();
        File[] files = mProjectsRoot.listFiles();
        if (files != null) {
            for (File file : files) {
                if (isProject(file)) projects.add(file.getCanonicalFile());
            }
        }
        Collections.sort(projects, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return projects;
    }

    private boolean isProject(File file) {
        return file != null && file.isDirectory() && new File(file, ".aeeBackup/appData").isFile();
    }

    private File resolveInsideProject(File project, String relativePath) throws Exception {
        String rel = relativePath == null ? "" : relativePath.trim();
        while (rel.startsWith("/")) rel = rel.substring(1);
        File target = new File(project, rel).getCanonicalFile();
        if (!isInside(project, target)) {
            throw new IllegalArgumentException("Path escapes project root: " + relativePath);
        }
        return target;
    }

    private boolean isInside(File root, File target) throws IOException {
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
    }

    private JSONObject projectSummary(File project) throws Exception {
        JSONObject data = readAppData(project);
        return new JSONObject()
                .put("id", project.getName())
                .put("path", project.getAbsolutePath())
                .put("appName", data.optString("app_name", project.getName()))
                .put("packageName", data.optString("package_name", ""))
                .put("versionInfo", data.optString("version_info", ""))
                .put("lastModified", project.lastModified());
    }

    private JSONObject fileInfo(File project, File file) throws Exception {
        return new JSONObject()
                .put("name", file.getName())
                .put("path", relative(project, file))
                .put("directory", file.isDirectory())
                .put("size", file.isFile() ? file.length() : 0)
                .put("lastModified", file.lastModified());
    }

    private JSONObject readAppData(File project) {
        File appData = new File(project, ".aeeBackup/appData");
        try {
            return new JSONObject(new String(readAll(appData, MAX_READ_LIMIT), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void markSmaliEdited(File project) {
        File appData = new File(project, ".aeeBackup/appData");
        try {
            JSONObject data = readAppData(project);
            data.put("smali_edited", true);
            try (FileOutputStream fos = new FileOutputStream(appData)) {
                fos.write(data.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private String relative(File project, File file) throws IOException {
        String root = project.getCanonicalPath();
        String path = file.getCanonicalPath();
        if (path.equals(root)) return "";
        return path.substring(root.length() + 1);
    }

    private String requiredString(JSONObject object, String key) {
        String value = object.optString(key, null);
        if (value == null) throw new IllegalArgumentException("Missing required argument: " + key);
        return value;
    }

    private byte[] readAll(File file, int maxBytes) throws IOException {
        if (file.length() > maxBytes) {
            throw new IOException("File exceeds limit: " + file.getAbsolutePath());
        }
        try (FileInputStream fis = new FileInputStream(file); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = fis.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("File exceeds limit: " + file.getAbsolutePath());
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        }
    }

    private JSONObject successResponse(Object id, JSONObject result) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id).put("result", result);
    }

    private JSONObject errorResponse(Object id, int code, String message) throws JSONException {
        return new JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id == null ? JSONObject.NULL : id)
                .put("error", new JSONObject().put("code", code).put("message", message == null ? "Error" : message));
    }

    private void writeJson(OutputStream output, int status, JSONObject body) throws IOException {
        writeBytes(output, status, "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeText(OutputStream output, int status, String body) throws IOException {
        writeBytes(output, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void writeOptions(OutputStream output) throws IOException {
        String headers = "HTTP/1.1 204 No Content\r\n"
                + corsHeaders()
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type, Accept, Mcp-Session-Id, Last-Event-ID\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private void writeBytes(OutputStream output, int status, String contentType, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 202 ? "Accepted" : status == 400 ? "Bad Request" : status == 404 ? "Not Found" : "Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + corsHeaders()
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private void writeSseHeaders(OutputStream output) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache, no-transform\r\n"
                + "Connection: keep-alive\r\n"
                + corsHeaders()
                + "X-Accel-Buffering: no\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private void writeSseResponse(OutputStream output, JSONObject response) throws IOException {
        writeSseHeaders(output);
        writeSseEvent(output, "message", response.toString());
    }

    private String corsHeaders() {
        return "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Expose-Headers: Mcp-Session-Id\r\n";
    }

    private static void writeSseEvent(OutputStream output, String event, String data) throws IOException {
        StringBuilder builder = new StringBuilder();
        if (event != null && !event.isEmpty()) {
            builder.append("event: ").append(event).append('\n');
        }
        String safeData = data == null ? "" : data;
        String[] lines = safeData.split("\\r?\\n", -1);
        for (String line : lines) {
            builder.append("data: ").append(line).append('\n');
        }
        builder.append('\n');
        output.write(builder.toString().getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void writeSseComment(OutputStream output, String comment) throws IOException {
        output.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static final class SseSession {
        final String id;
        final OutputStream output;
        final AtomicBoolean open = new AtomicBoolean(true);

        SseSession(String id, OutputStream output) {
            this.id = id;
            this.output = output;
        }

        boolean isOpen() {
            return open.get();
        }

        synchronized void event(String event, String data) throws IOException {
            if (!open.get()) throw new IOException("SSE session is closed");
            try {
                writeSseEvent(output, event, data);
            } catch (IOException e) {
                open.set(false);
                throw e;
            }
        }

        synchronized void comment(String comment) throws IOException {
            if (!open.get()) throw new IOException("SSE session is closed");
            try {
                writeSseComment(output, comment);
            } catch (IOException e) {
                open.set(false);
                throw e;
            }
        }

        void close() {
            open.set(false);
        }
    }

    private static final class HttpRequest {
        final String method;
        final String path;
        final String query;
        final Map<String, String> headers;
        final byte[] body;

        HttpRequest(String method, String path, String query, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        String queryParam(String name) {
            if (query == null || query.isEmpty()) return null;
            String[] parts = query.split("&");
            for (String part : parts) {
                int equals = part.indexOf('=');
                String key = equals >= 0 ? part.substring(0, equals) : part;
                String value = equals >= 0 ? part.substring(equals + 1) : "";
                if (name.equals(urlDecode(key))) return urlDecode(value);
            }
            return null;
        }

        static HttpRequest read(InputStream input) throws IOException {
            String requestLine = readLine(input);
            if (requestLine == null || requestLine.isEmpty()) return null;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) throw new IOException("Bad HTTP request line");
            String method = parts[0].toUpperCase(Locale.US);
            String pathAndQuery = parts[1];
            String path = pathAndQuery;
            String query = "";
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) {
                query = path.substring(queryIndex + 1);
                path = path.substring(0, queryIndex);
            }
            int contentLength = 0;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(input)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                    String value = line.substring(colon + 1).trim();
                    headers.put(name, value);
                    if ("content-length".equals(name)) {
                        contentLength = Integer.parseInt(value);
                    }
                }
            }
            if (contentLength > MAX_BODY_BYTES) throw new IOException("HTTP body too large");
            byte[] body = new byte[contentLength];
            int offset = 0;
            while (offset < contentLength) {
                int read = input.read(body, offset, contentLength - offset);
                if (read < 0) throw new IOException("Unexpected EOF");
                offset += read;
            }
            return new HttpRequest(method, path, query, headers, body);
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int previous = -1;
            int current;
            while ((current = input.read()) != -1) {
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = baos.toByteArray();
                    int length = bytes.length;
                    if (length > 0 && bytes[length - 1] == '\r') length--;
                    return new String(bytes, 0, length, StandardCharsets.US_ASCII);
                }
                baos.write(current);
                previous = current;
                if (baos.size() > 8192) throw new IOException("HTTP header line too long");
            }
            return baos.size() == 0 ? null : new String(baos.toByteArray(), StandardCharsets.US_ASCII);
        }

        private static String urlDecode(String value) {
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch (Exception ignored) {
                return value;
            }
        }
    }
}
