# Astra AI Local v4 — Full Windows Laptop Control

Free local AI companion that directly controls and automates your Windows laptop. No OpenAI API key required.

## Requirements:
- Windows 11 / Windows 10
- Node 24+ & npm
- Java 21
- Maven (bundled at `apache-maven-3.9.9`)
- Ollama (`llama3.2:3b`)

## Quick Start:
1. Ensure Ollama is running and has the model:
   ```powershell
   ollama pull llama3.2:3b
   ```
2. Start the Backend:
   Double-click `run-backend.cmd` or run:
   ```cmd
   cd astra-backend
   mvn spring-boot:run
   ```
3. Start the Frontend:
   Double-click `run-frontend.cmd` or run:
   ```cmd
   cd astra-frontend
   npm start
   ```
4. Open your browser at **http://localhost:4300** and click the microphone or type!

---

## 💻 What you can say or do (Full Laptop Access):

### 1. Launch Any Windows Application:
- *"Open Notepad"* / *"Open Chrome"* / *"Open Edge"*
- *"Open Calculator"* / *"Open Paint"*
- *"Open VS Code"* / *"Open Code"*
- *"Open Task Manager"* / *"Open Settings"*
- *"Open Command Prompt"* / *"Open Terminal"* / *"Open PowerShell"*
- *"Open Camera"* / *"Open Spotify"* / *"Open WhatsApp"*

### 2. File Explorer & System Folders:
- *"Open Downloads"* / *"Open my downloads folder"*
- *"Open Desktop"* / *"Open Documents"* / *"Open Pictures"*
- *"Open C Drive"* / *"Open D Drive"*

### 3. Web & YouTube Automation:
- *"Search YouTube for lofi music"*
- *"Search Google for today's weather"*
- *"Open github.com"* / *"Open reddit.com"*

### 4. Laptop System Controls:
- *"Mute"* / *"Unmute"* / *"Turn volume up"* / *"Volume down"*
- *"Take a screenshot"* / *"Screen snip"*
- *"Lock my laptop"* / *"Lock screen"*
- *"Check battery"* / *"What is my battery level?"*
- *"What is my IP address?"* / *"Check Wi-Fi status"*
- *"Show system specs"* / *"Laptop specs"*
- *"Empty Recycle Bin"*

### 5. File & Folder Operations:
- *"Create folder MyProject on Desktop"*
- *"Create file notes.txt"*

### 6. Shell & PowerShell Commands:
- *"Run command ipconfig"*
- *"Run command dir"*
- *"Powershell Get-Process"*

### 7. AI Action Mode (Powered by Ollama llama3.2:3b):
- If you ask Astra to do something custom on Windows (e.g. *"Show top memory consuming processes"*, *"Check free disk space on C:"*), the AI dynamically generates and executes the safe PowerShell command and returns the live results!
- If you ask a conversational question (*"Explain quantum computing"*), it replies naturally and speaks aloud with speech synthesis.
