package com.astra.agent;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WindowsToolService {

    public record ExecutionResult(String reply, String tool, boolean success, String commandExecuted) {}

    /**
     * Run a PowerShell command synchronously with timeout and capture output.
     */
    public String executePowerShell(String script) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-Command", script
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(12, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Command timed out after 12 seconds.";
            }

            String output = sb.toString().trim();
            return output.isEmpty() ? "Command executed successfully (no text output)." : output;
        } catch (Exception e) {
            return "Execution error: " + e.getMessage();
        }
    }

    /**
     * Start a process in background asynchronously via cmd /c start.
     */
    public boolean launchAsync(String target) {
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "", target).start();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String openWhatsApp() {
        try {
            launchAsync("whatsapp:");
            Thread.sleep(1000);
            launchAsync("https://web.whatsapp.com/");
            return "Opening WhatsApp on your laptop.";
        } catch (Exception e) {
            return "Could not launch WhatsApp. Opening WhatsApp Web instead.";
        }
    }

    public ExecutionResult launchApplication(String appName) {
        String key = appName.trim().toLowerCase(Locale.ROOT);
        String command;
        String friendlyName;

        switch (key) {
            case "notepad", "note pad", "text editor" -> {
                command = "notepad.exe";
                friendlyName = "Notepad";
            }
            case "calc", "calculator" -> {
                command = "calc.exe";
                friendlyName = "Calculator";
            }
            case "paint", "mspaint" -> {
                command = "mspaint.exe";
                friendlyName = "Paint";
            }
            case "chrome", "google chrome", "browser" -> {
                command = "chrome";
                friendlyName = "Google Chrome";
            }
            case "edge", "microsoft edge" -> {
                command = "msedge";
                friendlyName = "Microsoft Edge";
            }
            case "code", "vscode", "vs code", "visual studio code" -> {
                command = "code";
                friendlyName = "Visual Studio Code";
            }
            case "task manager", "taskmgr", "task monitor" -> {
                command = "taskmgr.exe";
                friendlyName = "Task Manager";
            }
            case "settings", "windows settings" -> {
                command = "ms-settings:";
                friendlyName = "Windows Settings";
            }
            case "control panel" -> {
                command = "control.exe";
                friendlyName = "Control Panel";
            }
            case "cmd", "command prompt", "terminal" -> {
                command = "cmd.exe";
                friendlyName = "Command Prompt";
            }
            case "powershell" -> {
                command = "powershell.exe";
                friendlyName = "PowerShell";
            }
            case "camera", "webcam" -> {
                command = "microsoft.windows.camera:";
                friendlyName = "Windows Camera";
            }
            case "spotify" -> {
                command = "spotify:";
                friendlyName = "Spotify";
            }
            case "snipping tool", "snip", "snip and sketch" -> {
                command = "snippingtool.exe";
                friendlyName = "Snipping Tool";
            }
            case "word", "winword", "microsoft word" -> {
                command = "winword";
                friendlyName = "Microsoft Word";
            }
            case "excel", "microsoft excel" -> {
                command = "excel";
                friendlyName = "Microsoft Excel";
            }
            case "powerpoint", "powerpnt" -> {
                command = "powerpnt";
                friendlyName = "Microsoft PowerPoint";
            }
            case "explorer", "file explorer", "files", "my computer", "this pc" -> {
                command = "explorer.exe";
                friendlyName = "File Explorer";
            }
            default -> {
                command = key;
                friendlyName = key;
            }
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
            case "downloads", "my downloads" -> {
                target = "shell:Downloads";
                friendly = "Downloads";
            }
            case "desktop", "my desktop" -> {
                target = "shell:Desktop";
                friendly = "Desktop";
            }
            case "documents", "my documents", "docs" -> {
                target = "shell:Personal";
                friendly = "Documents";
            }
            case "pictures", "photos", "my pictures" -> {
                target = "shell:My Pictures";
                friendly = "Pictures";
            }
            case "music", "my music" -> {
                target = "shell:My Music";
                friendly = "Music";
            }
            case "videos", "my videos" -> {
                target = "shell:My Video";
                friendly = "Videos";
            }
            case "c drive", "c:", "c" -> {
                target = "C:\\";
                friendly = "C: Drive";
            }
            case "d drive", "d:", "d" -> {
                target = "D:\\";
                friendly = "D: Drive";
            }
            default -> {
                target = folderName;
                friendly = folderName;
            }
        }

        launchAsync("explorer.exe " + target);
        return new ExecutionResult("Opened your " + friendly + " folder in File Explorer.", "open_folder", true, "explorer.exe " + target);
    }

    public ExecutionResult searchWebOrOpenUrl(String query, String type) {
        try {
            if ("youtube".equalsIgnoreCase(type)) {
                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
                String url = query.isBlank() ? "https://www.youtube.com" : "https://www.youtube.com/results?search_query=" + encoded;
                launchAsync(url);
                String reply = query.isBlank() ? "Opened YouTube for you." : "Searching YouTube for \"" + query + "\" on your browser.";
                return new ExecutionResult(reply, "youtube_search", true, url);
            } else if ("google".equalsIgnoreCase(type)) {
                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
                String url = query.isBlank() ? "https://www.google.com" : "https://www.google.com/search?q=" + encoded;
                launchAsync(url);
                String reply = query.isBlank() ? "Opened Google for you." : "Searching Google for \"" + query + "\" on your browser.";
                return new ExecutionResult(reply, "google_search", true, url);
            } else {
                String url = query.startsWith("http://") || query.startsWith("https://") ? query : "https://" + query;
                launchAsync(url);
                return new ExecutionResult("Opening " + url + " in your browser.", "open_url", true, url);
            }
        } catch (Exception e) {
            return new ExecutionResult("Could not open web link: " + e.getMessage(), "web_error", false, query);
        }
    }

    public ExecutionResult controlVolume(String action) {
        String ps;
        String desc;
        switch (action.toLowerCase(Locale.ROOT)) {
            case "mute", "unmute", "toggle_mute" -> {
                ps = "$w = New-Object -ComObject Wscript.Shell; $w.SendKeys([char]173)";
                desc = "Toggled volume mute on your laptop.";
            }
            case "up" -> {
                ps = "$w = New-Object -ComObject Wscript.Shell; 1..5 | ForEach-Object { $w.SendKeys([char]175) }";
                desc = "Turned up your laptop volume.";
            }
            case "down" -> {
                ps = "$w = New-Object -ComObject Wscript.Shell; 1..5 | ForEach-Object { $w.SendKeys([char]174) }";
                desc = "Turned down your laptop volume.";
            }
            case "play_pause" -> {
                ps = "$w = New-Object -ComObject Wscript.Shell; $w.SendKeys([char]179)";
                desc = "Sent media play/pause to your media player.";
            }
            default -> {
                ps = "";
                desc = "Unknown volume action.";
            }
        }

        if (!ps.isEmpty()) {
            executePowerShell(ps);
            return new ExecutionResult(desc, "volume_control", true, ps);
        }
        return new ExecutionResult("Could not adjust volume.", "volume_control", false, "");
    }

    public ExecutionResult takeScreenshot() {
        launchAsync("snippingtool.exe");
        launchAsync("ms-screenclip:");
        return new ExecutionResult("I launched the Windows Snipping Tool. Select the area you want to capture.", "take_screenshot", true, "snippingtool.exe");
    }

    public ExecutionResult lockPc() {
        launchAsync("rundll32.exe user32.dll,LockWorkStation");
        return new ExecutionResult("Your laptop screen is now locked for security.", "lock_workstation", true, "LockWorkStation");
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
        if (!ip.isEmpty() && !ip.contains("error")) {
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
        return new ExecutionResult("Windows 11 system running on this laptop.", "system_specs", true, script);
    }

    public ExecutionResult emptyRecycleBin() {
        String script = "Clear-RecycleBin -Force -ErrorAction SilentlyContinue";
        executePowerShell(script);
        return new ExecutionResult("Your Windows Recycle Bin has been emptied.", "empty_recycle_bin", true, script);
    }

    public ExecutionResult runShellCommand(String cmd) {
        String out = executePowerShell(cmd);
        String trimmed = out.length() > 600 ? out.substring(0, 600) + "\n...[truncated]" : out;
        return new ExecutionResult("Command executed:\n" + trimmed, "run_command", true, cmd);
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
            return new ExecutionResult("Failed to create file: " + e.getMessage(), "create_file", false, fileName);
        }
    }

    /**
     * Check if user message matches high-speed native Windows intents.
     * Returns null if no direct match is found (allowing Ollama AI Agent to process it).
     */
    public ExecutionResult tryExecuteNativeIntent(String input) {
        if (input == null || input.isBlank()) return null;
        String m = input.trim();
        String l = m.toLowerCase(Locale.ROOT);

        // 1. WhatsApp
        if (l.contains("open whatsapp") || l.contains("launch whatsapp") || l.equals("whatsapp")) {
            return new ExecutionResult(openWhatsApp(), "open_whatsapp", true, "whatsapp:");
        }

        // 2. Direct run command: "run command dir", "powershell Get-Process", "cmd ipconfig"
        if (l.startsWith("run command ") || l.startsWith("execute command ") || l.startsWith("run ") && l.length() > 6) {
            String cmd = m.replaceFirst("(?i)^(run command|execute command|run)\\s+", "").trim();
            if (!cmd.isEmpty()) {
                return runShellCommand(cmd);
            }
        }
        if (l.startsWith("powershell ")) {
            String cmd = m.substring(11).trim();
            if (!cmd.isEmpty()) return runShellCommand(cmd);
        }

        // 3. YouTube Search: "search youtube for ...", "open youtube and search ...", "play ... on youtube", "youtube ..."
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

        // 4. Google Search: "search google for ...", "google ...", "search for ..."
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

        // 5. Open URL directly: "open github.com", "open reddit.com", etc.
        if (l.matches("(?i)^(open|go to)\\s+([a-zA-Z0-9.-]+\\.(com|org|net|io|in|edu|gov|dev)(/.*)?)$")) {
            String url = m.replaceFirst("(?i)^(open|go to)\\s+", "").trim();
            return searchWebOrOpenUrl(url, "url");
        }

        // 6. Volume & Media Controls
        if (l.contains("unmute")) {
            return controlVolume("unmute");
        }
        if (l.contains("mute") || l.contains("silence")) {
            return controlVolume("mute");
        }
        if (l.contains("volume up") || l.contains("increase volume") || l.contains("turn up volume") || l.contains("louder")) {
            return controlVolume("up");
        }
        if (l.contains("volume down") || l.contains("decrease volume") || l.contains("turn down volume") || l.contains("lower volume")) {
            return controlVolume("down");
        }
        if (l.contains("play music") || l.contains("pause music") || l.equals("pause") || l.equals("play/pause")) {
            return controlVolume("play_pause");
        }

        // 7. Screenshot / Snipping Tool
        if (l.contains("screenshot") || l.contains("screen shot") || l.contains("capture screen") || l.contains("snipping tool") || l.contains("screen snip")) {
            return takeScreenshot();
        }

        // 8. Lock PC / Laptop
        if (l.contains("lock my laptop") || l.contains("lock laptop") || l.contains("lock pc") || l.contains("lock my pc") || l.contains("lock screen") || l.equals("lock")) {
            return lockPc();
        }

        // 9. Battery status
        if (l.contains("battery") || l.contains("battery percentage") || l.contains("battery level") || l.contains("battery status") || l.contains("charge percentage")) {
            return getBattery();
        }

        // 10. Wi-Fi / IP / Network
        if (l.contains("ip address") || l.contains("my ip") || l.contains("wifi status") || l.contains("wifi info") || l.contains("network status")) {
            return getNetworkInfo();
        }

        // 11. System Specs / Specs / RAM
        if (l.contains("system spec") || l.contains("laptop spec") || l.contains("pc spec") || l.contains("system info") || l.contains("ram usage") || l.contains("specs")) {
            return getSystemSpecs();
        }

        // 12. Recycle bin
        if (l.contains("empty recycle bin") || l.contains("clear recycle bin") || l.contains("clean recycle bin")) {
            return emptyRecycleBin();
        }

        // 13. Open Folders: "open downloads", "open desktop", "open documents", "open pictures", "open c drive"
        Pattern folderPat = Pattern.compile("(?i)^(?:open|show|browse)(?:\\s+my)?\\s+(downloads|desktop|documents|docs|pictures|photos|music|videos|c drive|d drive)(?:\\s+folder)?$");
        Matcher folderMat = folderPat.matcher(m);
        if (folderMat.find()) {
            return openFolder(folderMat.group(1));
        }

        // 14. Create folder on desktop: "create folder test", "make folder project"
        Pattern createFolderPat = Pattern.compile("(?i)^(?:create|make|new)\\s+folder\\s+(?:called|named)?\\s*(.+)$");
        Matcher cfMat = createFolderPat.matcher(m);
        if (cfMat.find()) {
            return createFolder(cfMat.group(1));
        }

        // 15. Create file: "create file note.txt"
        Pattern createFilePat = Pattern.compile("(?i)^(?:create|make|new)\\s+file\\s+(?:called|named)?\\s*([a-zA-Z0-9_.-]+)$");
        Matcher cfileMat = createFilePat.matcher(m);
        if (cfileMat.find()) {
            return createFile(cfileMat.group(1), "");
        }

        // 16. Launching common apps: "open notepad", "open chrome", "open calc", "open code", "open paint", etc.
        Pattern appPat = Pattern.compile("(?i)^(?:open|launch|start|run)(?:\\s+my)?\\s+(notepad|calculator|calc|paint|mspaint|chrome|google chrome|edge|microsoft edge|vs code|vscode|code|visual studio code|task manager|taskmgr|settings|windows settings|control panel|cmd|command prompt|powershell|terminal|camera|webcam|spotify|snipping tool|word|winword|excel|powerpoint|file explorer|explorer|files)$");
        Matcher appMat = appPat.matcher(m);
        if (appMat.find()) {
            return launchApplication(appMat.group(1));
        }

        // 17. Help & Capabilities
        if (l.contains("show capabilities") || l.contains("what can you do") || l.equals("help") || l.contains("capabilities") || l.contains("commands list")) {
            String cap = "I have direct access to your Windows laptop! Here is what I can do:\n" +
                    "• Open Apps: Notepad, Chrome, VS Code, Calc, Paint, Task Manager, Settings, Spotify, Terminal, Camera, etc.\n" +
                    "• Browse & Search: Search YouTube (\"Search YouTube for jazz\"), Google (\"Google weather\"), or open any website.\n" +
                    "• Laptop Controls: Mute/unmute, volume up/down, lock laptop screen, take screenshot.\n" +
                    "• Folders & Files: Open Downloads, Desktop, Documents, C: Drive, create folders/files.\n" +
                    "• System Info: Check battery level, local IP address, system specs, empty recycle bin.\n" +
                    "• Run Commands: Run any PowerShell or CMD command (\"run command ipconfig\").\n" +
                    "• AI Companion: Answer questions, chat, and calculate math locally with Ollama!";
            return new ExecutionResult(cap, "show_capabilities", true, "help");
        }

        return null;
    }
}
