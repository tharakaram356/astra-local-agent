package com.astra.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production-ready Windows tooling helper.
 *
 * Key improvements:
 * - Robust URL opening using Desktop.browse with cmd.exe start fallback.
 * - Dedicated openChrome() which prefers local Chrome executable with args (falls back to openUrl()).
 * - Optional adb helpers to open apps on a connected Android device (requires adb in PATH).
 * - Better PowerShell execution with stderr capture, configurable timeout and logging.
 * - Safe mode for arbitrary shell execution (controlled by env var ALLOW_SHELL_COMMANDS).
 * - SLF4J logging instead of silent returns, and clearer ExecutionResult usage.
 */
@Service
public class WindowsToolService {

    private static final Logger log = LoggerFactory.getLogger(WindowsToolService.class);

    public record ExecutionResult(String reply, String tool, boolean success, String commandExecuted) {}

    // Configuration (can be overridden via environment variables)
    private final long powershellTimeoutSeconds = Long.parseLong(System.getenv().getOrDefault("WIN_TOOL_POWERSHELL_TIMEOUT", "12"));
    private final long genericStartTimeoutSeconds = Long.parseLong(System.getenv().getOrDefault("WIN_TOOL_START_TIMEOUT", "5"));
    private final boolean allowShellExecution = Boolean.parseBoolean(System.getenv().getOrDefault("ALLOW_SHELL_COMMANDS", "false"));
    private final boolean preferChromePathLaunch = Boolean.parseBoolean(System.getenv().getOrDefault("PREFER_CHROME_PATH_LAUNCH", "true"));

    /* -------------------------
       Process / PowerShell helpers
       ------------------------- */

    /**
     * Execute PowerShell command synchronously with timeout and capture stdout/stderr.
     */
    public String executePowerShell(String script) {
        Objects.requireNonNull(script, "script must not be null");
        List<String> cmd = Arrays.asList(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command", script
        );
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append(System.lineSeparator());
                }
            }

            boolean finished = p.waitFor(powershellTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("PowerShell command timed out after {} seconds: {}", powershellTimeoutSeconds, script);
                return "Command timed out after " + powershellTimeoutSeconds + " seconds.";
            }

            String output = sb.toString().trim();
            if (output.isEmpty()) {
                return "Command executed successfully (no text output).";
            }
            return output;
        } catch (Exception e) {
            log.error("PowerShell execution error for script: {}", script, e);
            return "Execution error: " + e.getMessage();
        }
    }

    /**
     * Generic background launcher using cmd /c start so the UI opens immediately.
     * Accepts either a raw target (URL, protocol, exe) or an argument list.
     */
    public boolean launchAsync(String... targetParts) {
        try {
            // Build command: cmd.exe /c start "" arg1 arg2 ...
            List<String> cmd = new ArrayList<>();
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("start");
            cmd.add(""); // title required by start when quoting next arg
            Collections.addAll(cmd, targetParts);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.start(); // do not wait
            return true;
        } catch (Exception e) {
            log.error("Failed to launch asynchronously: {}", Arrays.toString(targetParts), e);
            return false;
        }
    }

    /**
     * Lower-level command execution with timeout and returned stdout (not safe for arbitrary user input).
     */
    private String executeCommandCaptureOutput(List<String> command, long timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append(System.lineSeparator());
                }
            }

            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("Command timed out: {}", command);
                return "Timed out";
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Error executing command {}: {}", command, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /* -------------------------
       Browser & App open helpers
       ------------------------- */

    /**
     * Open a URL using Desktop.browse when available; fall back to cmd start.
     */
    public boolean openUrlInDefaultBrowser(String url) {
        try {
            if (url == null || url.isBlank()) return false;
            // Ensure scheme
            if (!url.matches("(?i)^https?://.*")) {
                url = "https://" + url;
            }

            // Try Desktop.browse first (works when AWT is available)
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(new URI(url));
                    log.info("Opened URL via Desktop.browse: {}", url);
                    return true;
                }
            } catch (Throwable t) {
                log.debug("Desktop.browse not available or failed, falling back to cmd start: {}", t.getMessage());
            }

            // Fallback: cmd start
            boolean ok = launchAsync(url);
            if (ok) {
                log.info("Opened URL via cmd start fallback: {}", url);
            }
            return ok;
        } catch (Exception e) {
            log.error("openUrlInDefaultBrowser failed for {}: {}", url, e.getMessage());
            return false;
        }
    }

    /**
     * Try to launch Chrome specifically. Tries known install paths; if not found or fails,
     * falls back to openUrlInDefaultBrowser.
     */
    public ExecutionResult openChrome(String url) {
        String friendly = "Google Chrome";
        String normalizedUrl = (url == null || url.isBlank()) ? "https://www.google.com" : url;
        if (!normalizedUrl.matches("(?i)^https?://.*")) {
            normalizedUrl = "https://" + normalizedUrl;
        }

        // Try preferred local chrome executables
        List<String> possiblePaths = new ArrayList<>();
        String chromePath = System.getenv("CHROME_PATH");
        if (chromePath != null && !chromePath.isBlank()) {
            possiblePaths.add(chromePath);
        }
        String programFiles = System.getenv("PROGRAMFILES");
        String programFilesX86 = System.getenv("PROGRAMFILES(X86)");
        String home = System.getProperty("user.home");

        if (programFiles != null && !programFiles.isBlank()) {
            possiblePaths.add(programFiles + "\\Google\\Chrome\\Application\\chrome.exe");
        }
        if (programFilesX86 != null && !programFilesX86.isBlank()) {
            possiblePaths.add(programFilesX86 + "\\Google\\Chrome\\Application\\chrome.exe");
        }
        if (home != null && !home.isBlank()) {
            possiblePaths.add(home + "\\AppData\\Local\\Google\\Chrome\\Application\\chrome.exe");
        }

        for (String path : possiblePaths) {
            if (path == null || path.isBlank()) continue;
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                try {
                    List<String> cmd = new ArrayList<>();
                    cmd.add(path);
                    cmd.add("--new-window");
                    cmd.add("--no-first-run");
                    cmd.add(normalizedUrl);
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.start();
                    log.info("Launched Chrome via path: {} url: {}", path, normalizedUrl);
                    return new ExecutionResult("Opened " + friendly + ".", "open_chrome", true, String.join(" ", cmd));
                } catch (Exception e) {
                    log.warn("Failed to launch chrome at {}: {}", path, e.getMessage());
                }
            }
        }

        // Fallback: default browser open
        boolean opened = openUrlInDefaultBrowser(normalizedUrl);
        if (opened) {
            return new ExecutionResult("Opened " + friendly + " (or default browser) for you.", "open_chrome_fallback", true, normalizedUrl);
        } else {
            return new ExecutionResult("Could not open Chrome or browser for URL.", "open_chrome", false, normalizedUrl);
        }
    }

    /**
     * Open WhatsApp Desktop (protocol) or fall back to WhatsApp Web in browser.
     */
    public String openWhatsApp() {
        try {
            boolean okProto = launchAsync("whatsapp:");
            boolean okWeb = openUrlInDefaultBrowser("https://web.whatsapp.com/");
            if (okProto || okWeb) {
                return "Opening WhatsApp (desktop or web) on your machine.";
            }
            return "Attempted to open WhatsApp but could not launch either desktop or web client.";
        } catch (Exception e) {
            log.error("openWhatsApp failed", e);
            openUrlInDefaultBrowser("https://web.whatsapp.com/");
            return "Could not launch WhatsApp Desktop. Opening WhatsApp Web instead.";
        }
    }

    /* -------------------------
       Android device helpers (adb)
       ------------------------- */

    /**
     * Run an adb command if adb is available in PATH. Return stdout or error string.
     * Example: runAdb("shell", "am", "start", "-n", "com.android.chrome/com.google.android.apps.chrome.Main");
     */
    public String runAdb(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("adb");
            Collections.addAll(cmd, args);
            log.info("Running adb command: {}", cmd);
            return executeCommandCaptureOutput(cmd, genericStartTimeoutSeconds);
        } catch (Throwable t) {
            log.error("ADB command failed: {}", Arrays.toString(args), t);
            return "ADB error: " + t.getMessage();
        }
    }

    /**
     * Try to open Chrome on a connected Android device (requires adb).
     */
    public ExecutionResult openChromeOnAndroid(String url) {
        try {
            String target = (url == null || url.isBlank()) ? "https://www.google.com" : url;
            if (!target.matches("(?i)^https?://.*")) {
                target = "https://" + target;
            }
            String startResp = runAdb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", target, "com.android.chrome");
            return new ExecutionResult("Tried to open Chrome on Android: " + startResp, "adb_open_chrome", true, "adb am start VIEW " + target);
        } catch (Exception e) {
            log.error("openChromeOnAndroid failed", e);
            return new ExecutionResult("Could not open Chrome on Android: " + e.getMessage(), "adb_open_chrome", false, "");
        }
    }

    /**
     * Try to open WhatsApp on a connected Android device (requires adb).
     */
    public ExecutionResult openWhatsAppOnAndroid() {
        try {
            String resp = runAdb("shell", "monkey", "-p", "com.whatsapp", "-c", "android.intent.category.LAUNCHER", "1");
            return new ExecutionResult("Tried to open WhatsApp on Android: " + resp, "adb_open_whatsapp", true, "adb monkey com.whatsapp");
        } catch (Exception e) {
            log.error("openWhatsAppOnAndroid failed", e);
            return new ExecutionResult("Could not open WhatsApp on Android: " + e.getMessage(), "adb_open_whatsapp", false, "");
        }
    }

    /* -------------------------
       Safe-ish shell command entrypoint
       ------------------------- */

    /**
     * Execute a shell command only if explicitly allowed by environment variable.
     * This prevents accidental remote code execution in production.
     */
    public ExecutionResult runShellCommand(String cmd) {
        if (!allowShellExecution) {
            log.warn("runShellCommand blocked by configuration: ALLOW_SHELL_COMMANDS=false");
            return new ExecutionResult("Shell execution is disabled in this deployment for safety.", "run_command", false, cmd);
        }
        if (cmd.contains("|") || cmd.contains("&") || cmd.contains(">") || cmd.contains(";")) {
            log.warn("Refusing to run potentially chained command: {}", cmd);
            return new ExecutionResult("Refusing to run command containing pipes or redirects.", "run_command", false, cmd);
        }
        String out = executePowerShell(cmd);
        String trimmed = out.length() > 600 ? out.substring(0, 600) + "\n...[truncated]" : out;
        return new ExecutionResult("Command executed:\n" + trimmed, "run_command", true, cmd);
    }

    /* -------------------------
       Existing methods wiring to improved helpers
       ------------------------- */

    public ExecutionResult launchApplication(String appName) {
        String key = appName == null ? "" : appName.trim().toLowerCase(Locale.ROOT);
        String command;
        String friendlyName;

        switch (key) {
            case "notepad", "note pad", "text editor" -> { command = "notepad.exe"; friendlyName = "Notepad"; }
            case "calc", "calculator" -> { command = "calc.exe"; friendlyName = "Calculator"; }
            case "paint", "mspaint" -> { command = "mspaint.exe"; friendlyName = "Paint"; }
            case "chrome", "google chrome", "browser" -> { command = "chrome"; friendlyName = "Google Chrome"; }
            case "edge", "microsoft edge" -> { command = "msedge"; friendlyName = "Microsoft Edge"; }
            case "code", "vscode", "vs code", "visual studio code" -> { command = "code"; friendlyName = "Visual Studio Code"; }
            case "task manager", "taskmgr", "task monitor" -> { command = "taskmgr.exe"; friendlyName = "Task Manager"; }
            case "settings", "windows settings" -> { command = "ms-settings:"; friendlyName = "Windows Settings"; }
            case "control panel" -> { command = "control.exe"; friendlyName = "Control Panel"; }
            case "cmd", "command prompt", "terminal" -> { command = "cmd.exe"; friendlyName = "Command Prompt"; }
            case "powershell" -> { command = "powershell.exe"; friendlyName = "PowerShell"; }
            case "camera", "webcam" -> { command = "microsoft.windows.camera:"; friendlyName = "Windows Camera"; }
            case "spotify" -> { command = "spotify:"; friendlyName = "Spotify"; }
            case "snipping tool", "snip", "snip and sketch" -> { command = "snippingtool.exe"; friendlyName = "Snipping Tool"; }
            case "word", "winword", "microsoft word" -> { command = "winword"; friendlyName = "Microsoft Word"; }
            case "excel", "microsoft excel" -> { command = "excel"; friendlyName = "Microsoft Excel"; }
            case "powerpoint", "powerpnt" -> { command = "powerpnt"; friendlyName = "Microsoft PowerPoint"; }
            case "explorer", "file explorer", "files", "my computer", "this pc" -> { command = "explorer.exe"; friendlyName = "File Explorer"; }
            default -> { command = key; friendlyName = key; }
        }

        if (key.contains("chrome")) {
            return openChrome("");
        }

        boolean ok = launchAsync(command);
        if (ok) {
            return new ExecutionResult("I have opened " + friendlyName + " for you.", "launch_app", true, command);
        } else {
            return new ExecutionResult("I attempted to launch " + friendlyName + ", but Windows reported an issue.", "launch_app", false, command);
        }
    }

    public ExecutionResult openFolder(String folderName) {
        String key = folderName.trim().toLowerCase(Locale.ROOT);
        String target;
        String friendly;

        switch (key) {
            case "downloads", "my downloads" -> { target = "shell:Downloads"; friendly = "Downloads"; }
            case "desktop", "my desktop" -> { target = "shell:Desktop"; friendly = "Desktop"; }
            case "documents", "my documents", "docs" -> { target = "shell:Personal"; friendly = "Documents"; }
            case "pictures", "photos", "my pictures" -> { target = "shell:My Pictures"; friendly = "Pictures"; }
            case "music", "my music" -> { target = "shell:My Music"; friendly = "Music"; }
            case "videos", "my videos" -> { target = "shell:My Video"; friendly = "Videos"; }
            case "c drive", "c:", "c" -> { target = "C:\\"; friendly = "C: Drive"; }
            case "d drive", "d:", "d" -> { target = "D:\\"; friendly = "D: Drive"; }
            default -> { target = folderName; friendly = folderName; }
        }

        boolean ok = launchAsync("explorer.exe", target);
        return new ExecutionResult("Opened your " + friendly + " folder in File Explorer.", "open_folder", ok, "explorer.exe " + target);
    }

    public ExecutionResult searchWebOrOpenUrl(String query, String type) {
        try {
            if ("youtube".equalsIgnoreCase(type)) {
                String encoded = URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
                String url = (query == null || query.isBlank()) ? "https://www.youtube.com" : "https://www.youtube.com/results?search_query=" + encoded;
                openUrlInDefaultBrowser(url);
                String reply = (query == null || query.isBlank()) ? "Opened YouTube for you." : "Searching YouTube for \"" + query + "\" on your browser.";
                return new ExecutionResult(reply, "youtube_search", true, url);
            } else if ("google".equalsIgnoreCase(type)) {
                String encoded = URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
                String url = (query == null || query.isBlank()) ? "https://www.google.com" : "https://www.google.com/search?q=" + encoded;
                openUrlInDefaultBrowser(url);
                String reply = (query == null || query.isBlank()) ? "Opened Google for you." : "Searching Google for \"" + query + "\" on your browser.";
                return new ExecutionResult(reply, "google_search", true, url);
            } else {
                String url = (query == null) ? "" : query;
                if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
                openUrlInDefaultBrowser(url);
                return new ExecutionResult("Opening " + url + " in your browser.", "open_url", true, url);
            }
        } catch (Exception e) {
            log.error("searchWebOrOpenUrl failed", e);
            return new ExecutionResult("Could not open web link: " + e.getMessage(), "web_error", false, query);
        }
    }

    public ExecutionResult controlVolume(String action) {
        String ps;
        String desc;
        switch (action.toLowerCase(Locale.ROOT)) {
            case "mute", "unmute", "toggle_mute" -> { ps = "$w = New-Object -ComObject Wscript.Shell; $w.SendKeys([char]173)"; desc = "Toggled volume mute on your laptop."; }
            case "up" -> { ps = "$w = New-Object -ComObject Wscript.Shell; 1..5 | ForEach-Object { $w.SendKeys([char]175) }"; desc = "Turned up your laptop volume."; }
            case "down" -> { ps = "$w = New-Object -ComObject Wscript.Shell; 1..5 | ForEach-Object { $w.SendKeys([char]174) }"; desc = "Turned down your laptop volume."; }
            case "play_pause" -> { ps = "$w = New-Object -ComObject Wscript.Shell; $w.SendKeys([char]179)"; desc = "Sent media play/pause to your media player."; }
            default -> { ps = ""; desc = "Unknown volume action."; }
        }

        if (!ps.isEmpty()) {
            executePowerShell(ps);
            return new ExecutionResult(desc, "volume_control", true, ps);
        }
        return new ExecutionResult("Could not adjust volume.", "volume_control", false, "");
    }

    public ExecutionResult takeScreenshot() {
        boolean a = launchAsync("snippingtool.exe");
        boolean b = launchAsync("ms-screenclip:");
        if (a || b) {
            return new ExecutionResult("I launched the Windows Snipping Tool. Select the area you want to capture.", "take_screenshot", true, "snippingtool.exe");
        } else {
            return new ExecutionResult("Failed to launch Snipping Tool.", "take_screenshot", false, "");
        }
    }

    public ExecutionResult lockPc() {
        boolean ok = launchAsync("rundll32.exe", "user32.dll,LockWorkStation");
        return new ExecutionResult("Your laptop screen is now locked for security.", "lock_workstation", ok, "LockWorkStation");
    }

    public ExecutionResult getBattery() {
        String script = "(Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue | Select-Object -ExpandProperty EstimatedChargeRemaining)";
        String out = executePowerShell(script).trim();
        if (out.matches("\\d+")) {
            return new ExecutionResult("Your laptop battery is currently at " + out + "%.", "battery_status", true, script);
        } else {
            return new ExecutionResult("Your computer is plugged in and running on AC power (or no battery was detected).", "battery_status", true, script);
        }
    }

    public ExecutionResult getNetworkInfo() {
        String script = "(Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.InterfaceAlias -notlike '*Loopback*' -and $_.IPAddress -notlike '169.254*' } | Select-Object -ExpandProperty IPAddress) -join ', '";
        String ip = executePowerShell(script).trim();
        if (!ip.isEmpty() && !ip.toLowerCase(Locale.ROOT).contains("error")) {
            return new ExecutionResult("Your laptop's local IP address is " + ip + ".", "network_info", true, script);
        }
        return new ExecutionResult("Network info could not be determined at this moment.", "network_info", false, script);
    }

    public ExecutionResult getSystemSpecs() {
        String script = "$os = (Get-CimInstance Win32_OperatingSystem).Caption; " +
                "$ram = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1); " +
                "$cpu = (Get-CimInstance Win32_Processor).Name; " +
                "\"OS: $os | CPU: $cpu | RAM: $ram GB\"";
        String out = executePowerShell(script).trim();
        if (!out.isEmpty() && !out.contains("error")) {
            return new ExecutionResult("Here are your laptop specifications:\n" + out, "system_specs", true, script);
        }
        return new ExecutionResult("Windows system running on this laptop.", "system_specs", true, script);
    }

    public ExecutionResult emptyRecycleBin() {
        String script = "Clear-RecycleBin -Force -ErrorAction SilentlyContinue";
        executePowerShell(script);
        return new ExecutionResult("Your Windows Recycle Bin has been emptied.", "empty_recycle_bin", true, script);
    }

    public ExecutionResult createFolder(String folderName) {
        String desktop = System.getProperty("user.home") + "\\Desktop\\" + folderName.trim();
        File f = new File(desktop);
        boolean created = f.mkdirs();
        if (created || f.exists()) {
            return new ExecutionResult("Created folder \"" + folderName.trim() + "\" on your Desktop.", "create_folder", true, "mkdir " + desktop);
        } else {
            return new ExecutionResult("Could not create folder on Desktop.", "create_folder", false, desktop);
        }
    }

    public ExecutionResult createFile(String fileName, String content) {
        try {
            String path = System.getProperty("user.home") + "\\Desktop\\" + fileName.trim();
            java.nio.file.Files.writeString(java.nio.file.Paths.get(path), content != null ? content : "");
            return new ExecutionResult("Created file \"" + fileName.trim() + "\" on your Desktop.", "create_file", true, path);
        } catch (Exception e) {
            log.error("createFile failed: {}", e.getMessage());
            return new ExecutionResult("Failed to create file: " + e.getMessage(), "create_file", false, fileName);
        }
    }

    /* -------------------------
       Intent matcher (uses improved methods where appropriate)
       ------------------------- */

    public ExecutionResult tryExecuteNativeIntent(String input) {
        if (input == null || input.isBlank()) return null;
        String m = input.trim();
        String l = m.toLowerCase(Locale.ROOT);

        if (l.contains("open whatsapp") || l.contains("launch whatsapp") || l.equals("whatsapp")) {
            String reply = openWhatsApp();
            return new ExecutionResult(reply, "open_whatsapp", true, "whatsapp:");
        }

        if (l.startsWith("run command ") || l.startsWith("execute command ") || (l.startsWith("run ") && l.length() > 6)) {
            String cmd = m.replaceFirst("(?i)^(run command|execute command|run)\\s+", "").trim();
            if (!cmd.isEmpty()) {
                return runShellCommand(cmd);
            }
        }
        if (l.startsWith("powershell ")) {
            String cmd = m.substring(11).trim();
            if (!cmd.isEmpty()) return runShellCommand(cmd);
        }

        Pattern ytPat = Pattern.compile("(?i)(?:search youtube for|search for|play)?\\s*(.+?)\\s*(?:on youtube)$");
        Matcher ytMat = ytPat.matcher(m);
        if (ytMat.find()) {
            return searchWebOrOpenUrl(ytMat.group(1).trim(), "youtube");
        }
        if (l.startsWith("search youtube for ") || l.startsWith("youtube ")) {
            String q = m.replaceFirst("(?i)^(search youtube for|youtube)\\s+", "").trim();
            return searchWebOrOpenUrl(q, "youtube");
        }
        if (l.equals("open youtube") || l.equals("youtube") || l.equals("launch youtube")) {
            return searchWebOrOpenUrl("", "youtube");
        }

        if (l.startsWith("search google for ") || l.startsWith("google ")) {
            String q = m.replaceFirst("(?i)^(search google for|google)\\s+", "").trim();
            return searchWebOrOpenUrl(q, "google");
        }
        if (l.equals("open google") || l.equals("google")) {
            return searchWebOrOpenUrl("", "google");
        }
        if (l.startsWith("search for ") && !l.contains("youtube")) {
            String q = m.substring(11).trim();
            return searchWebOrOpenUrl(q, "google");
        }

        if (l.matches("(?i)^(open|go to)\\s+([a-zA-Z0-9.-]+\\.(com|org|net|io|in|edu|gov|dev)(/.*)?)$")) {
            String url = m.replaceFirst("(?i)^(open|go to)\\s+", "").trim();
            return searchWebOrOpenUrl(url, "url");
        }

        if (l.contains("unmute")) return controlVolume("unmute");
        if (l.contains("mute") || l.contains("silence")) return controlVolume("mute");
        if (l.contains("volume up") || l.contains("increase volume") || l.contains("turn up volume") || l.contains("louder")) return controlVolume("up");
        if (l.contains("volume down") || l.contains("decrease volume") || l.contains("turn down volume") || l.contains("lower volume")) return controlVolume("down");
        if (l.contains("play music") || l.contains("pause music") || l.equals("pause") || l.equals("play/pause")) return controlVolume("play_pause");

        if (l.contains("screenshot") || l.contains("screen shot") || l.contains("capture screen") || l.contains("snipping tool") || l.contains("screen snip")) {
            return takeScreenshot();
        }

        if (l.contains("lock my laptop") || l.contains("lock laptop") || l.contains("lock pc") || l.contains("lock my pc") || l.contains("lock screen") || l.equals("lock")) {
            return lockPc();
        }

        if (l.contains("battery") || l.contains("battery percentage") || l.contains("battery level") || l.contains("battery status") || l.contains("charge percentage")) {
            return getBattery();
        }

        if (l.contains("ip address") || l.contains("my ip") || l.contains("wifi status") || l.contains("wifi info") || l.contains("network status")) {
            return getNetworkInfo();
        }

        if (l.contains("system spec") || l.contains("laptop spec") || l.contains("pc spec") || l.contains("system info") || l.contains("ram usage") || l.contains("specs")) {
            return getSystemSpecs();
        }

        if (l.contains("empty recycle bin") || l.contains("clear recycle bin") || l.contains("clean recycle bin")) {
            return emptyRecycleBin();
        }

        Pattern folderPat = Pattern.compile("(?i)^(?:open|show|browse)(?:\\s+my)?\\s+(downloads|desktop|documents|docs|pictures|photos|music|videos|c drive|d drive)(?:\\s+folder)?$");
        Matcher folderMat = folderPat.matcher(m);
        if (folderMat.find()) {
            return openFolder(folderMat.group(1));
        }

        Pattern createFolderPat = Pattern.compile("(?i)^(?:create|make|new)\\s+folder\\s+(?:called|named)?\\s*(.+)$");
        Matcher cfMat = createFolderPat.matcher(m);
        if (cfMat.find()) {
            return createFolder(cfMat.group(1));
        }

        Pattern createFilePat = Pattern.compile("(?i)^(?:create|make|new)\\s+file\\s+(?:called|named)?\\s*([a-zA-Z0-9_.-]+)$");
        Matcher cfileMat = createFilePat.matcher(m);
        if (cfileMat.find()) {
            return createFile(cfileMat.group(1), "");
        }

        Pattern appPat = Pattern.compile("(?i)^(?:open|launch|start|run)(?:\\s+my)?\\s+(notepad|calculator|calc|paint|mspaint|chrome|google chrome|edge|microsoft edge|vs code|vscode|code|visual studio code|task manager|taskmgr|settings|windows settings|control panel|cmd|command prompt|powershell|terminal|camera|webcam|spotify|snipping tool|word|winword|excel|powerpoint|file explorer|explorer|files)$");
        Matcher appMat = appPat.matcher(m);
        if (appMat.find()) {
            return launchApplication(appMat.group(1));
        }

        if (l.contains("show capabilities") || l.contains("what can you do") || l.equals("help") || l.contains("capabilities") || l.contains("commands list")) {
            String cap = "I have direct access to your Windows laptop! Here is what I can do:\n" +
                    "• Open Apps: Notepad, Chrome (uses improved launcher), VS Code, Calc, Paint, Task Manager, Settings, Spotify, Terminal, Camera, etc.\n" +
                    "• Browse & Search: Search YouTube (\"Search YouTube for jazz\"), Google (\"Google weather\"), or open any website.\n" +
                    "• Laptop Controls: Mute/unmute, volume up/down, lock laptop screen, take screenshot.\n" +
                    "• Folders & Files: Open Downloads, Desktop, Documents, C: Drive, create folders/files.\n" +
                    "• System Info: Check battery level, local IP address, system specs, empty recycle bin.\n" +
                    "• Run Commands: Run PowerShell or adb commands if enabled (requires explicit environment variables for safety).";
            return new ExecutionResult(cap, "show_capabilities", true, "help");
        }

        return null;
    }
}
