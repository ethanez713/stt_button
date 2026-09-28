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

    const int WM_HOTKEY = 0x0312;
    const uint MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_SHIFT = 0x4, MOD_NOREPEAT = 0x4000;
    const uint VK_SPACE = 0x20;
    const int TOGGLE = 1, CANCEL = 2;

    readonly string repo, models;
    readonly Indicator indicator = new Indicator();
    bool recording, busy;

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
            if (status == "text") {
                indicator.Hide();
                Paste(Field(result, "text"));
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
        // Sending Ctrl+V while the hotkey's Ctrl/Alt are still held would type Ctrl+Alt+V.
        while ((GetAsyncKeyState(0x11) & 0x8000) != 0 || (GetAsyncKeyState(0x12) & 0x8000) != 0) Thread.Sleep(20);
        SendKeys.SendWait("^v");
        var restore = new System.Windows.Forms.Timer { Interval = 500 };
        restore.Tick += (s, e) => {
            restore.Stop();
            try {
                if (saved != null) Clipboard.SetDataObject(saved, true); else Clipboard.Clear();
            } catch (ExternalException) { }
        };
        restore.Start();
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
