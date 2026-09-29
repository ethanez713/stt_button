// Windows half of desktop dictation: global hotkeys, an indicator, and pasting the result.
// Compiled by desktop/install.sh with the .NET Framework csc.exe (C# 5); everything else runs in WSL.
//
//   SttHotkey.exe <repo dir in WSL> <model dir in WSL>
//
// Ctrl+Alt+Space toggles a take; Ctrl+Alt+Shift+Space cancels it.
using System;
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

    readonly string repo, models;
    readonly Indicator indicator = new Indicator();
    readonly string logPath = Path.Combine(Application.StartupPath, "helper.log");
    bool recording, busy;
    IntPtr target;
    DateTime pressedAt;

    public HotkeyWindow(string repo, string models) {
        this.repo = repo;
        this.models = models;
        CreateHandle();
        RegisterHotKey(Handle, TOGGLE, MOD_CONTROL | MOD_ALT | MOD_NOREPEAT, VK_SPACE);
        RegisterHotKey(Handle, CANCEL, MOD_CONTROL | MOD_ALT | MOD_SHIFT | MOD_NOREPEAT, VK_SPACE);
    }

    protected override void SetVisibleCore(bool value) { base.SetVisibleCore(false); }

    protected override void WndProc(ref Message m) {
        if (m.Msg == WM_HOTKEY) {
            if (m.WParam.ToInt32() == TOGGLE) Toggle(); else Cancel();
        }
        base.WndProc(ref m);
    }

    void Toggle() {
        if (busy) return;
        busy = true;
        target = GetForegroundWindow();
        pressedAt = DateTime.Now;
        indicator.ShowText(recording ? "… Transcribing" : "… Starting mic");
        RunAsync("toggle", result => {
            busy = false;
            string status = Field(result, "status");
            if (status == "recording") {
                recording = true;
                indicator.ShowText("● Recording");
                return;
            }
            recording = false;
            string text = Field(result, "text");
            Log(string.Format("result status={0} chars={1} wait_ms={2:0}", status, text == null ? 0 : text.Length,
                (DateTime.Now - pressedAt).TotalMilliseconds));
            if (status == "text" && string.IsNullOrEmpty(text)) {
                indicator.Flash("No speech heard");
            } else if (status == "text") {
                indicator.Hide();
                try {
                    Paste(text);
                } catch (Exception e) {
                    Log("paste failed: " + e);
                    indicator.Flash("\u26A0 paste failed: " + e.Message);
                }
            } else {
                indicator.Flash("⚠ " + (Field(result, "error") ?? status));
            }
        });
    }

    void Cancel() {
        RunAsync("cancel", result => {
            recording = false;
            busy = false;
            indicator.Flash("Cancelled");
        });
    }

    void RunAsync(string command, Action<Dictionary<string, object>> done) {
        string script = "cd '" + repo + "' && PULSE_SERVER=${PULSE_SERVER:-unix:/mnt/wslg/PulseServer} " +
            "STT_MODEL_DIR='" + models + "' .venv/bin/python -m stt_button.dictate " + command;
        new Thread(() => {
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
        }) { IsBackground = true }.Start();
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
