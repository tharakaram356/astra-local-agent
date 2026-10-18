import {CommonModule} from '@angular/common';
import {Component,NgZone,OnInit,ChangeDetectorRef,ViewChild,ElementRef,AfterViewChecked} from '@angular/core';
import {FormsModule} from '@angular/forms';

export interface ChatMessage {
  id: string;
  role: 'tharak' | 'astra' | 'you';
  text: string;
  time: string;
  isError?: boolean;
  errorDetail?: string;
  commandTip?: string;
}

export interface ToolCall {
  name: string;
  time: string;
  tag: string;
  detail: string;
}

interface Recognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: any;
  onend: any;
  onerror: any;
  start(): void;
  stop(): void;
}

@Component({
  selector: 'astra-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent implements OnInit, AfterViewChecked {
  @ViewChild('scrollViewport') private scrollViewport?: ElementRef<HTMLDivElement>;

  input = '';
  busy = false;
  listening = false;
  isSpeaking = false;
  status = 'Tharak is ready · Click mic or type';
  backendLive = false;
  soundEnabled = true;
  lastTool = 'None';
  sessionId = 'e503ecfb';
  interimSpoken = '';
  private shouldScrollToBottom = false;

  toolCalls: ToolCall[] = [];

  messages: ChatMessage[] = [
    {
      id: 'm-1',
      role: 'tharak',
      text: 'Hello there! 👋 I am Tharak, your Windows AI companion with full control over your laptop. You can speak or type commands like "Open Notepad", "Open Chrome", "Search YouTube for lofi", "Open Downloads", "Check battery", "Mute volume", "Take screenshot", or execute any command. How can I help you?',
      time: this.formatTime(new Date())
    }
  ];

  private recognition: Recognition | null = null;

  constructor(private zone: NgZone, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.sessionId = Math.random().toString(16).substring(2, 10);
    this.checkBackendHealth();
    setInterval(() => this.checkBackendHealth(), 10000);

    // Warm up speech synthesis voices
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.onvoiceschanged = () => {};
    }
  }

  ngAfterViewChecked() {
    if (this.shouldScrollToBottom) {
      this.scrollToBottom();
      this.shouldScrollToBottom = false;
    }
  }

  formatTime(d: Date): string {
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
  }

  scrollToBottom() {
    try {
      if (this.scrollViewport?.nativeElement) {
        this.scrollViewport.nativeElement.scrollTop = this.scrollViewport.nativeElement.scrollHeight;
      }
    } catch (_) {}
  }

  checkBackendHealth() {
    fetch('http://localhost:8080/api/agent/health')
      .then(r => {
        this.zone.run(() => {
          this.backendLive = r.ok;
          this.cdr.detectChanges();
        });
      })
      .catch(() => {
        this.zone.run(() => {
          this.backendLive = false;
          this.cdr.detectChanges();
        });
      });
  }

  sendMessage(text?: string) {
    const m = (text ?? this.input).trim();
    if (!m || this.busy) return;

    const timeStr = this.formatTime(new Date());

    // Show user message IMMEDIATELY
    this.messages = [
      ...this.messages,
      {
        id: 'msg-' + Date.now(),
        role: 'you',
        text: m,
        time: timeStr
      }
    ];

    this.input = '';
    this.interimSpoken = '';
    this.busy = true;
    this.status = 'Thinking of the best response for you... ✨';
    this.shouldScrollToBottom = true;
    this.cdr.detectChanges();
    this.scrollToBottom();

    fetch('http://localhost:8080/api/agent/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: m })
    })
      .then(async r => {
        const d = await r.json();
        if (!r.ok) throw new Error(d.reply || 'Backend error');
        return d;
      })
      .then(d => {
        this.zone.run(() => {
          this.backendLive = true;
          const replyText = d.reply || 'I am here to help!';
          const toolUsed = d.tool ?? 'ollama_local_ai';
          this.lastTool = toolUsed;

          this.toolCalls.unshift({
            name: toolUsed,
            time: this.formatTime(new Date()),
            tag: toolUsed === 'open_whatsapp' || toolUsed.startsWith('open_') || toolUsed === 'launch_app' || toolUsed.includes('windows') || toolUsed.includes('command') || toolUsed.includes('volume') || toolUsed.includes('battery') || toolUsed.includes('screenshot') || toolUsed.includes('specs') ? 'Windows OS' : (toolUsed === 'calculator' || toolUsed.includes('time') || toolUsed.includes('date') ? 'Built-in' : 'Local AI'),
            detail: `Processed "${m.length > 24 ? m.substring(0, 24) + '...' : m}"`
          });
          if (this.toolCalls.length > 6) this.toolCalls.pop();

          // Append reply and show immediately
          this.messages = [
            ...this.messages,
            {
              id: 'msg-' + Date.now(),
              role: 'tharak',
              text: replyText,
              time: this.formatTime(new Date())
            }
          ];
          this.status = 'Tharak is ready · Click mic or type';
          this.shouldScrollToBottom = true;
          this.cdr.detectChanges();
          this.scrollToBottom();
          this.speak(replyText);
        });
      })
      .catch(e => {
        this.zone.run(() => {
          this.backendLive = false;
          this.messages = [
            ...this.messages,
            {
              id: 'err-' + Date.now(),
              role: 'tharak',
              text: 'Could not connect to Tharak Spring Boot backend. Please verify the backend is running on port 8080.',
              time: this.formatTime(new Date()),
              isError: true,
              errorDetail: 'Connection refused on http://localhost:8080',
              commandTip: 'Run: mvn spring-boot:run in astra-backend'
            }
          ];
          this.status = 'Backend offline';
          this.shouldScrollToBottom = true;
          this.cdr.detectChanges();
          this.scrollToBottom();
        });
      })
      .finally(() => {
        this.zone.run(() => {
          this.busy = false;
          this.cdr.detectChanges();
          this.scrollToBottom();
        });
      });
  }

  retryConnection() {
    this.checkBackendHealth();
    this.sendMessage('Hello Tharak');
  }

  openWhatsApp() {
    this.sendMessage('Open my WhatsApp');
  }

  quick(t: string) {
    this.input = t;
    this.sendMessage(t);
  }

  toggleSound() {
    this.soundEnabled = !this.soundEnabled;
    if (!this.soundEnabled && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
      this.isSpeaking = false;
    }
    this.cdr.detectChanges();
  }

  clearChat() {
    this.messages = [
      {
        id: 'm-init',
        role: 'tharak',
        text: 'Hello there! 👋 I am Tharak, your friendly Windows AI voice companion. You can speak to me naturally or type commands like "Open WhatsApp", ask questions, or run calculations. How can I help you today?',
        time: this.formatTime(new Date())
      }
    ];
    this.toolCalls = [];
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
      this.isSpeaking = false;
    }
    this.cdr.detectChanges();
  }

  copyText(text: string) {
    if (navigator?.clipboard) {
      navigator.clipboard.writeText(text);
    }
  }

  startVoice() {
    const C = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!C) {
      this.status = 'Use Chrome or Edge for friendly voice interaction.';
      this.cdr.detectChanges();
      return;
    }
    if (this.listening) {
      this.recognition?.stop();
      this.listening = false;
      this.interimSpoken = '';
      this.status = 'Tharak is ready · Click mic or type';
      this.cdr.detectChanges();
      return;
    }

    // Stop speaking if Tharak was speaking
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
      this.isSpeaking = false;
    }

    const rec: Recognition = new C();
    this.recognition = rec;
    rec.lang = 'en-IN';
    rec.continuous = false;
    rec.interimResults = true;
    this.listening = true;
    this.interimSpoken = '';
    this.status = "I'm listening... Speak naturally! 🎙️";
    this.cdr.detectChanges();

    rec.onresult = (e: any) => {
      let finalTranscript = '';
      let interimTranscript = '';

      for (let i = e.resultIndex; i < e.results.length; ++i) {
        if (e.results[i].isFinal) {
          finalTranscript += e.results[i][0].transcript;
        } else {
          interimTranscript += e.results[i][0].transcript;
        }
      }

      this.zone.run(() => {
        if (interimTranscript) {
          this.interimSpoken = interimTranscript;
          this.input = interimTranscript;
          this.cdr.detectChanges();
        }
        if (finalTranscript) {
          this.interimSpoken = finalTranscript;
          this.input = finalTranscript;
          this.listening = false;
          this.status = 'Voice command received! ✨';
          this.cdr.detectChanges();
          this.sendMessage(finalTranscript);
        }
      });
    };

    rec.onerror = () => {
      this.zone.run(() => {
        this.listening = false;
        this.interimSpoken = '';
        this.status = 'Voice recognition paused';
        this.cdr.detectChanges();
      });
    };

    rec.onend = () => {
      this.zone.run(() => {
        this.listening = false;
        this.interimSpoken = '';
        if (!this.busy && !this.isSpeaking) {
          this.status = 'Tharak is ready · Click mic or type';
        }
        this.cdr.detectChanges();
      });
    };

    rec.start();
  }

  speak(t: string) {
    if (!this.soundEnabled || !('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();

    // Clean text of markdown asterisks/emojis for smoother natural speech
    const cleanText = t.replace(/\*+/g, '').replace(/[\u{1F600}-\u{1F64F}]/gu, '');
    const u = new SpeechSynthesisUtterance(cleanText);
    u.lang = 'en-IN';
    u.rate = 1.02;
    u.pitch = 1.05;

    // Pick the most friendly natural voice available
    const voices = window.speechSynthesis.getVoices();
    if (voices.length > 0) {
      const preferred = voices.find(v => 
        (v.lang.includes('IN') || v.lang.includes('en-GB') || v.lang.includes('en-US')) &&
        (v.name.includes('Natural') || v.name.includes('Google') || v.name.includes('Online') || v.name.includes('Heera') || v.name.includes('Neerja') || v.name.includes('Zira'))
      ) || voices.find(v => v.lang.startsWith('en'));

      if (preferred) u.voice = preferred;
    }

    u.onstart = () => {
      this.zone.run(() => {
        this.isSpeaking = true;
        this.status = 'Tharak is speaking... 🔊';
        this.cdr.detectChanges();
      });
    };

    u.onend = () => {
      this.zone.run(() => {
        this.isSpeaking = false;
        if (!this.busy) this.status = 'Tharak is ready · Click mic or type';
        this.cdr.detectChanges();
      });
    };

    u.onerror = () => {
      this.zone.run(() => {
        this.isSpeaking = false;
        if (!this.busy) this.status = 'Tharak is ready · Click mic or type';
        this.cdr.detectChanges();
      });
    };

    window.speechSynthesis.speak(u);
  }
}
