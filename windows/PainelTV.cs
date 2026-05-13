using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace PainelTV
{
    internal static class Program
    {
        private static NotifyIcon tray;
        private static Process server;
        private static string installDir;
        private static string nodeExe;
        private static Mutex instanceMutex;

        [STAThread]
        private static void Main()
        {
            bool createdNew;
            instanceMutex = new Mutex(true, "PainelTV.Tray.Singleton", out createdNew);
            if (!createdNew)
            {
                OpenPanelFromCurrentDirectory();
                return;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            installDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
            nodeExe = Path.Combine(installDir, "runtime", "node.exe");
            if (!File.Exists(nodeExe)) nodeExe = "node.exe";

            tray = new NotifyIcon();
            tray.Text = "Painel TV";
            tray.Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            tray.Visible = true;
            tray.ContextMenuStrip = BuildMenu();
            tray.DoubleClick += delegate { OpenPanel(); };

            StartServer();
            Application.ApplicationExit += delegate { StopServer(); tray.Visible = false; };
            Application.Run();
            instanceMutex.ReleaseMutex();
        }

        private static ContextMenuStrip BuildMenu()
        {
            var menu = new ContextMenuStrip();
            menu.Items.Add("Abrir painel", null, delegate { OpenPanel(); });
            menu.Items.Add("Reiniciar servidor", null, delegate { RestartServer(); });
            menu.Items.Add("Parar servidor", null, delegate { StopServer(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Sair", null, delegate { Application.Exit(); });
            return menu;
        }

        private static void StartServer()
        {
            try
            {
                if (server != null && !server.HasExited) return;
                string serverJs = Path.Combine(installDir, "apps", "server", "src", "server.js");
                if (!File.Exists(serverJs))
                {
                    tray.ShowBalloonTip(5000, "Painel TV", "Servidor nao encontrado: " + serverJs, ToolTipIcon.Error);
                    return;
                }

                var psi = new ProcessStartInfo();
                psi.FileName = nodeExe;
                psi.Arguments = "\"" + serverJs + "\"";
                psi.WorkingDirectory = installDir;
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.WindowStyle = ProcessWindowStyle.Hidden;
                server = Process.Start(psi);
                tray.ShowBalloonTip(2500, "Painel TV", "Servidor iniciado em segundo plano.", ToolTipIcon.Info);
            }
            catch (Exception error)
            {
                tray.ShowBalloonTip(7000, "Painel TV", "Falha ao iniciar servidor: " + error.Message, ToolTipIcon.Error);
            }
        }

        private static void StopServer()
        {
            try
            {
                if (server != null && !server.HasExited)
                {
                    server.Kill();
                    server.WaitForExit(3000);
                }
            }
            catch { }
        }

        private static void RestartServer()
        {
            StopServer();
            StartServer();
        }

        private static void OpenPanel()
        {
            string port = ResolveActivePort();
            Process.Start(new ProcessStartInfo("http://localhost:" + port + "/") { UseShellExecute = true });
        }

        private static void OpenPanelFromCurrentDirectory()
        {
            try
            {
                string baseDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
                string port = ResolveActivePort(baseDir);
                Process.Start(new ProcessStartInfo("http://localhost:" + port + "/") { UseShellExecute = true });
            }
            catch
            {
                Process.Start(new ProcessStartInfo("http://localhost:8787/") { UseShellExecute = true });
            }
        }

        private static string ReadConfiguredPort()
        {
            try
            {
                string config = Path.Combine(installDir, "apps", "server", "data", "runtime-config.json");
                return ReadConfiguredPortFrom(config);
            }
            catch { return "8787"; }
        }

        private static string ResolveActivePort()
        {
            return ResolveActivePort(installDir);
        }

        private static string ResolveActivePort(string baseDir)
        {
            string configured = ReadConfiguredPortFrom(Path.Combine(baseDir, "apps", "server", "data", "runtime-config.json"));
            if (IsServerHealthy(configured)) return configured;
            if (IsServerHealthy("8787")) return "8787";

            string[] candidates = { configured, "8788", "8888", "9090", "3000", "8080" };
            foreach (string candidate in candidates)
            {
                if (!string.IsNullOrWhiteSpace(candidate) && IsServerHealthy(candidate)) return candidate;
            }
            return configured;
        }

        private static string ReadConfiguredPortFrom(string config)
        {
            if (!File.Exists(config)) return "8787";
            string text = File.ReadAllText(config);
            Match match = Regex.Match(text, "\"port\"\\s*:\\s*(\\d+)");
            return match.Success ? match.Groups[1].Value : "8787";
        }

        private static bool IsServerHealthy(string port)
        {
            try
            {
                var request = (HttpWebRequest)WebRequest.Create("http://localhost:" + port + "/api/health");
                request.Timeout = 700;
                request.ReadWriteTimeout = 700;
                using (var response = (HttpWebResponse)request.GetResponse())
                {
                    return (int)response.StatusCode >= 200 && (int)response.StatusCode < 300;
                }
            }
            catch
            {
                return false;
            }
        }
    }
}
