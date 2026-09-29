// Windows half of desktop dictation: global hotkeys, the microphone, an indicator, and pasting.
// Compiled by desktop/install.sh with the .NET Framework csc.exe (C# 5); recognition and cleaning run in WSL.
//
//   SttHotkey.exe <repo dir in WSL> <model dir in WSL>
//
// Ctrl+Alt+Space toggles a take; Ctrl+Alt+Shift+Space cancels it.
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

static class Program {
    [STAThread]
    static void Main(string[] args) {
        // SttHotkey.exe --mic-check <seconds> <out.pcm>: record through the same Mic, for diagnosis.
        if (args.Length == 3 && args[0] == "--mic-check") {
            var mic = new Mic();
            mic.Open();
            mic.BeginTake(args[2]);
            Thread.Sleep(int.Parse(args[1]) * 1000);
            mic.Close();
            return;
        }
        bool created;
        using (new Mutex(true, "stt_button.SttHotkey", out created)) {
            if (!created) return;
            string repo = args.Length > 0 ? args[0] : "/home/ezhou/stt_button";
            string models = args.Length > 1 ? args[1] : "/home/ezhou/models/stt_button";
            Application.EnableVisualStyles();
            Application.Run(new HotkeyWindow(repo, models));
        }
    }
}

class HotkeyWindow : Form {
    [DllImport("user32.dll")] static extern bool RegisterHotKey(IntPtr hWnd, int id, uint modifiers, uint vk);
    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int vk);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint count, INPUT[] inputs, int size);

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public InputUnion u; }
    // The union must be as large as its largest member, MOUSEINPUT, or SendInput rejects cbSize.
    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }
    const uint INPUT_KEYBOARD = 1, KEYEVENTF_KEYUP = 2;
    const ushort VK_CONTROL = 0x11, VK_MENU = 0x12, VK_SHIFT = 0x10, VK_V = 0x56;

    const int WM_HOTKEY = 0x0312;
    const uint MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_SHIFT = 0x4, MOD_NOREPEAT = 0x4000;
    const uint VK_SPACE = 0x20;
    const int TOGGLE = 1, CANCEL = 2;
    const int TAKE_CAP_MS = 300 * 1000;
    // After a take the mic stays open this long, so the next take starts without opening the device.
    const int MIC_WARM_MS = 5 * 60 * 1000;

    readonly string repo, models;
    readonly string takesDir = Path.Combine(Application.StartupPath, "takes");
    readonly string logPath = Path.Combine(Application.StartupPath, "helper.log");
    readonly Indicator indicator = new Indicator();
    readonly Mic mic = new Mic();
    // Commands to WSL run one at a time, in order, so a quick stop can never overtake its start.
    readonly BlockingCollection<Action> wslQueue = new BlockingCollection<Action>();
    readonly System.Windows.Forms.Timer capTimer = new System.Windows.Forms.Timer { Interval = TAKE_CAP_MS };
    readonly System.Windows.Forms.Timer micIdleTimer = new System.Windows.Forms.Timer { Interval = MIC_WARM_MS };
    string takePath;
    bool busy;
    IntPtr target;
    DateTime stoppedAt;

    public HotkeyWindow(string repo, string models) {
        this.repo = repo;
        this.models = models;
        Directory.CreateDirectory(takesDir);
        CreateHandle();
        RegisterHotKey(Handle, TOGGLE, MOD_CONTROL | MOD_ALT | MOD_NOREPEAT, VK_SPACE);
        RegisterHotKey(Handle, CANCEL, MOD_CONTROL | MOD_ALT | MOD_SHIFT | MOD_NOREPEAT, VK_SPACE);
        new Thread(() => { foreach (Action command in wslQueue.GetConsumingEnumerable()) command(); }) { IsBackground = true }.Start();
        capTimer.Tick += (s, e) => { if (takePath != null) Stop(); };
        micIdleTimer.Tick += (s, e) => { micIdleTimer.Stop(); mic.Close(); Log("mic closed after idle"); };
    }

    protected override void SetVisibleCore(bool value) { base.SetVisibleCore(false); }

    protected override void WndProc(ref Message m) {
        if (m.Msg == WM_HOTKEY) {
            if (m.WParam.ToInt32() == TOGGLE) {
                if (takePath == null) Start(); else Stop();
            } else {
                Cancel();
            }
        }
        base.WndProc(ref m);
    }

    void Start() {
        if (busy) return;
        target = GetForegroundWindow();
        micIdleTimer.Stop();
        string id = DateTime.Now.ToString("yyyyMMdd'T'HHmmss") + "-" + Guid.NewGuid().ToString("N").Substring(0, 6);
        string path = Path.Combine(takesDir, id + ".pcm");
        try {
            bool warm = mic.IsOpen;
            mic.Open();
            mic.BeginTake(path);
            Log("take started warm=" + warm);
        } catch (Exception e) {
            Log("mic failed: " + e);
            indicator.Flash("⚠ microphone: " + e.Message);
            return;
        }
        takePath = path;
        indicator.ShowText("● Recording");
        capTimer.Start();
        RunWsl("start '" + WslPath(path) + "'", result => {
            if (Field(result, "status") != "started") Abort("⚠ " + (Field(result, "error") ?? "WSL did not start"));
        });
    }

    void Stop() {
        capTimer.Stop();
        mic.EndTake();
        takePath = null;
        busy = true;
        stoppedAt = DateTime.Now;
        micIdleTimer.Start();
        indicator.ShowText("… Transcribing");
        RunWsl("stop", result => {
            busy = false;
            string status = Field(result, "status");
            string text = Field(result, "text");
            Log(string.Format("result status={0} chars={1} stop_to_text_ms={2:0}", status, text == null ? 0 : text.Length,
                (DateTime.Now - stoppedAt).TotalMilliseconds));
            if (status == "text" && string.IsNullOrEmpty(text)) {
                indicator.Flash("No speech heard");
            } else if (status == "text") {
                indicator.Hide();
                try {
                    Paste(text);
                } catch (Exception e) {
                    Log("paste failed: " + e);
                    indicator.Flash("⚠ paste failed: " + e.Message);
                }
            } else {
                indicator.Flash("⚠ " + (Field(result, "error") ?? status));
            }
        });
    }

    void Cancel() {
        if (takePath == null) return;
        Abort("Cancelled");
    }

    void Abort(string message) {
        capTimer.Stop();
        mic.EndTake();
        if (takePath != null) File.Delete(takePath);
        takePath = null;
        micIdleTimer.Start();
        indicator.Flash(message);
        RunWsl("cancel", result => { });
    }

    static string WslPath(string windowsPath) {
        return "/mnt/" + char.ToLowerInvariant(windowsPath[0]) + windowsPath.Substring(2).Replace('\\', '/');
    }

    void RunWsl(string command, Action<Dictionary<string, object>> done) {
        string script = "cd '" + repo + "' && STT_MODEL_DIR='" + models + "' .venv/bin/python -m stt_button.dictate " + command;
        wslQueue.Add(() => {
            Dictionary<string, object> result;
            try {
                var info = new ProcessStartInfo("wsl.exe", "-e bash -c \"" + script + "\"") {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    StandardOutputEncoding = new UTF8Encoding(false),
                };
                using (var process = Process.Start(info)) {
                    string output = process.StandardOutput.ReadToEnd();
                    process.WaitForExit();
                    string lastLine = output.Trim();
                    int newline = lastLine.LastIndexOf('\n');
                    if (newline >= 0) lastLine = lastLine.Substring(newline + 1);
                    result = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(lastLine);
                }
            } catch (Exception e) {
                result = new Dictionary<string, object> { { "status", "error" }, { "error", e.Message } };
            }
            BeginInvoke(new Action(() => done(result)));
        });
    }

    static string Field(Dictionary<string, object> result, string key) {
        object value;
        return result != null && result.TryGetValue(key, out value) && value != null ? value.ToString() : null;
    }

    void Paste(string text) {
        if (string.IsNullOrEmpty(text)) return;
        IDataObject saved = CopyClipboard();
        Clipboard.SetText(text);
        // Sending Ctrl+V while the hotkey's Ctrl/Alt/Shift are still held would type Ctrl+Alt+V.
        while (Held(VK_CONTROL) || Held(VK_MENU) || Held(VK_SHIFT)) Thread.Sleep(20);
        bool refocused = false;
        if (target != IntPtr.Zero && GetForegroundWindow() != target) refocused = SetForegroundWindow(target);
        // SendInput rather than SendKeys: SendKeys' journal-hook path can drop input on current Windows.
        INPUT[] keys = { Key(VK_CONTROL, 0), Key(VK_V, 0), Key(VK_V, KEYEVENTF_KEYUP), Key(VK_CONTROL, KEYEVENTF_KEYUP) };
        uint sent = SendInput((uint)keys.Length, keys, Marshal.SizeOf(typeof(INPUT)));
        Log(string.Format("paste target={0} refocused={1} foreground={2} sent={3}/4 err={4}",
            ProcessName(target), refocused, ProcessName(GetForegroundWindow()), sent, Marshal.GetLastWin32Error()));
        if (sent != keys.Length) indicator.Flash("\u26A0 Ctrl+V was blocked; the text is on the clipboard");
        // Long enough for slow apps (terminals, Electron) to read the clipboard before it is restored.
        var restore = new System.Windows.Forms.Timer { Interval = 1500 };
        restore.Tick += (s, e) => {
            restore.Stop();
            try {
                if (saved != null) Clipboard.SetDataObject(saved, true); else Clipboard.Clear();
            } catch (ExternalException) { }
        };
        restore.Start();
    }

    static bool Held(ushort vk) { return (GetAsyncKeyState(vk) & 0x8000) != 0; }

    static INPUT Key(ushort vk, uint flags) {
        return new INPUT { type = INPUT_KEYBOARD, u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, dwFlags = flags } } };
    }

    static string ProcessName(IntPtr window) {
        try {
            uint pid;
            GetWindowThreadProcessId(window, out pid);
            return Process.GetProcessById((int)pid).ProcessName;
        } catch (Exception) {
            return "?";
        }
    }

    void Log(string line) {
        try {
            File.AppendAllText(logPath, DateTime.Now.ToString("s") + " " + line + Environment.NewLine);
        } catch (IOException) { }
    }

    // The live clipboard object goes stale once replaced, so copy each format out first.
    static IDataObject CopyClipboard() {
        try {
            IDataObject live = Clipboard.GetDataObject();
            if (live == null) return null;
            var copy = new DataObject();
            foreach (string format in live.GetFormats(false)) {
                try {
                    object data = live.GetData(format, false);
                    if (data != null) copy.SetData(format, false, data);
                } catch (Exception) { }
            }
            return copy.GetFormats().Length > 0 ? copy : null;
        } catch (ExternalException) {
            return null;
        }
    }
}

// Small always-on-top label that never takes focus from the app being dictated into.
class Indicator : Form {
    const int WS_EX_TOPMOST = 0x8, WS_EX_TRANSPARENT = 0x20, WS_EX_TOOLWINDOW = 0x80, WS_EX_NOACTIVATE = 0x08000000;
    readonly Label label = new Label();
    readonly System.Windows.Forms.Timer hideTimer = new System.Windows.Forms.Timer { Interval = 2500 };

    public Indicator() {
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        TopMost = true;
        BackColor = Color.FromArgb(32, 32, 32);
        Opacity = 0.9;
        label.ForeColor = Color.White;
        label.Font = new Font("Segoe UI", 11f);
        label.AutoSize = true;
        label.Padding = new Padding(12, 6, 12, 6);
        Controls.Add(label);
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        hideTimer.Tick += (s, e) => { hideTimer.Stop(); Hide(); };
    }

    protected override bool ShowWithoutActivation { get { return true; } }

    protected override CreateParams CreateParams {
        get {
            CreateParams p = base.CreateParams;
            p.ExStyle |= WS_EX_TOPMOST | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE;
            return p;
        }
    }

    public void ShowText(string text) {
        hideTimer.Stop();
        label.Text = text;
        Show();
        Rectangle area = Screen.PrimaryScreen.WorkingArea;
        Location = new Point(area.Left + (area.Width - Width) / 2, area.Bottom - Height - 24);
    }

    public void Flash(string text) {
        ShowText(text);
        hideTimer.Start();
    }
}

// The microphone through the waveIn API, which delivers 16 kHz mono PCM16 through Windows' own
// resampler. It stays open between takes while warm; audio outside a take only feeds the pre-roll.
class Mic {
    [DllImport("winmm.dll")] static extern int waveInOpen(out IntPtr handle, uint device, ref WAVEFORMATEX format, IntPtr callback, IntPtr instance, uint flags);
    [DllImport("winmm.dll")] static extern int waveInPrepareHeader(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInUnprepareHeader(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInAddBuffer(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInStart(IntPtr handle);
    [DllImport("winmm.dll")] static extern int waveInReset(IntPtr handle);
    [DllImport("winmm.dll")] static extern int waveInClose(IntPtr handle);

    [StructLayout(LayoutKind.Sequential)]
    struct WAVEFORMATEX { public ushort wFormatTag, nChannels; public uint nSamplesPerSec, nAvgBytesPerSec; public ushort nBlockAlign, wBitsPerSample, cbSize; }
    [StructLayout(LayoutKind.Sequential)]
    struct WAVEHDR { public IntPtr lpData; public uint dwBufferLength, dwBytesRecorded; public IntPtr dwUser; public uint dwFlags, dwLoops; public IntPtr lpNext, reserved; }

    const uint WAVE_MAPPER = 0xFFFFFFFF, WHDR_DONE = 1;
    const int BYTES_PER_SECOND = 32000, BUFFER_BYTES = 1600, BUFFERS = 16;
    // Audio kept from just before the hotkey, so a first word spoken with the keypress is not clipped.
    const int PRE_ROLL_BYTES = BYTES_PER_SECOND * 3 / 10;

    readonly object sync = new object();
    readonly Queue<byte[]> preRoll = new Queue<byte[]>();
    int preRollBytes;
    IntPtr handle = IntPtr.Zero;
    IntPtr[] headers;
    Thread poller;
    volatile bool running;
    FileStream take;

    public bool IsOpen { get { return handle != IntPtr.Zero; } }

    public void Open() {
        if (IsOpen) return;
        var format = new WAVEFORMATEX { wFormatTag = 1, nChannels = 1, nSamplesPerSec = 16000, nAvgBytesPerSec = BYTES_PER_SECOND, nBlockAlign = 2, wBitsPerSample = 16 };
        int error = waveInOpen(out handle, WAVE_MAPPER, ref format, IntPtr.Zero, IntPtr.Zero, 0);
        if (error != 0) { handle = IntPtr.Zero; throw new IOException("waveInOpen failed with MMRESULT " + error); }
        headers = new IntPtr[BUFFERS];
        int size = Marshal.SizeOf(typeof(WAVEHDR));
        for (int i = 0; i < BUFFERS; i++) {
            var header = new WAVEHDR { lpData = Marshal.AllocHGlobal(BUFFER_BYTES), dwBufferLength = BUFFER_BYTES };
            headers[i] = Marshal.AllocHGlobal(size);
            Marshal.StructureToPtr(header, headers[i], false);
            waveInPrepareHeader(handle, headers[i], size);
            waveInAddBuffer(handle, headers[i], size);
        }
        waveInStart(handle);
        running = true;
        poller = new Thread(Poll) { IsBackground = true };
        poller.Start();
    }

    public void BeginTake(string path) {
        lock (sync) {
            take = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete);
            foreach (byte[] chunk in preRoll) take.Write(chunk, 0, chunk.Length);
            take.Flush();
        }
    }

    public void EndTake() {
        lock (sync) {
            if (take == null) return;
            take.Dispose();
            take = null;
        }
    }

    public void Close() {
        if (!IsOpen) return;
        EndTake();
        running = false;
        poller.Join();
        waveInReset(handle);
        int size = Marshal.SizeOf(typeof(WAVEHDR));
        foreach (IntPtr header in headers) {
            waveInUnprepareHeader(handle, header, size);
            Marshal.FreeHGlobal(((WAVEHDR)Marshal.PtrToStructure(header, typeof(WAVEHDR))).lpData);
            Marshal.FreeHGlobal(header);
        }
        waveInClose(handle);
        handle = IntPtr.Zero;
        lock (sync) { preRoll.Clear(); preRollBytes = 0; }
    }

    // Buffers fill in the order they were queued, so they are drained in that same order.
    void Poll() {
        int size = Marshal.SizeOf(typeof(WAVEHDR));
        int next = 0;
        while (running) {
            IntPtr header = headers[next];
            var h = (WAVEHDR)Marshal.PtrToStructure(header, typeof(WAVEHDR));
            if ((h.dwFlags & WHDR_DONE) == 0) {
                Thread.Sleep(10);
                continue;
            }
            var chunk = new byte[h.dwBytesRecorded];
            Marshal.Copy(h.lpData, chunk, 0, chunk.Length);
            Consume(chunk);
            waveInUnprepareHeader(handle, header, size);
            h.dwFlags = 0;
            h.dwBytesRecorded = 0;
            Marshal.StructureToPtr(h, header, false);
            waveInPrepareHeader(handle, header, size);
            waveInAddBuffer(handle, header, size);
            next = (next + 1) % BUFFERS;
        }
    }

    void Consume(byte[] chunk) {
        lock (sync) {
            if (take != null) {
                take.Write(chunk, 0, chunk.Length);
                take.Flush();
            }
            preRoll.Enqueue(chunk);
            preRollBytes += chunk.Length;
            while (preRollBytes - preRoll.Peek().Length >= PRE_ROLL_BYTES) preRollBytes -= preRoll.Dequeue().Length;
        }
    }
}
