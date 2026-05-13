using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Management;
using System.Reflection;
using System.Windows.Forms;

namespace PainelTVInstaller
{
    internal static class Program
    {
        private const string AppName = "Painel TV";
        private const string Version = "1.2.1";
        private const string ResourceName = "PainelTVPayload";

        [STAThread]
        private static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new InstallerForm());
        }

        private sealed class InstallerForm : Form
        {
            private readonly string targetDir;
            private readonly CheckBox desktopShortcutCheck;
            private readonly CheckBox startupCheck;
            private readonly CheckBox launchCheck;
            private readonly Label statusLabel;
            private readonly Button installButton;
            private readonly Button cancelButton;

            public InstallerForm()
            {
                targetDir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "Programs",
                    AppName
                );

                Text = AppName + " Setup";
                Width = 560;
                Height = 320;
                StartPosition = FormStartPosition.CenterScreen;
                FormBorderStyle = FormBorderStyle.FixedDialog;
                MaximizeBox = false;
                MinimizeBox = false;

                var titleLabel = new Label
                {
                    Left = 24,
                    Top = 22,
                    Width = 480,
                    Height = 30,
                    Text = AppName + " " + Version,
                    Font = new System.Drawing.Font("Segoe UI", 14F, System.Drawing.FontStyle.Bold)
                };

                var infoLabel = new Label
                {
                    Left = 24,
                    Top = 60,
                    Width = 500,
                    Height = 56,
                    Text = "Este instalador atualiza o Painel TV no Windows, fecha o app e o node do servidor se estiverem abertos e instala em segundo plano."
                };

                var pathLabel = new Label
                {
                    Left = 24,
                    Top = 120,
                    Width = 500,
                    Height = 40,
                    Text = "Diretorio: " + targetDir
                };

                desktopShortcutCheck = new CheckBox
                {
                    Left = 24,
                    Top = 168,
                    Width = 250,
                    Checked = true,
                    Text = "Criar atalho na area de trabalho"
                };

                startupCheck = new CheckBox
                {
                    Left = 24,
                    Top = 194,
                    Width = 250,
                    Checked = true,
                    Text = "Iniciar junto com o Windows"
                };

                launchCheck = new CheckBox
                {
                    Left = 24,
                    Top = 220,
                    Width = 250,
                    Checked = true,
                    Text = "Abrir Painel TV ao finalizar"
                };

                statusLabel = new Label
                {
                    Left = 24,
                    Top = 248,
                    Width = 500,
                    Height = 24,
                    Text = "Pronto para instalar."
                };

                installButton = new Button
                {
                    Left = 330,
                    Top = 236,
                    Width = 90,
                    Height = 34,
                    Text = "Instalar"
                };
                installButton.Click += InstallClicked;

                cancelButton = new Button
                {
                    Left = 430,
                    Top = 236,
                    Width = 90,
                    Height = 34,
                    Text = "Cancelar"
                };
                cancelButton.Click += delegate { Close(); };

                Controls.Add(titleLabel);
                Controls.Add(infoLabel);
                Controls.Add(pathLabel);
                Controls.Add(desktopShortcutCheck);
                Controls.Add(startupCheck);
                Controls.Add(launchCheck);
                Controls.Add(statusLabel);
                Controls.Add(installButton);
                Controls.Add(cancelButton);
            }

            private void InstallClicked(object sender, EventArgs e)
            {
                installButton.Enabled = false;
                cancelButton.Enabled = false;
                desktopShortcutCheck.Enabled = false;
                startupCheck.Enabled = false;
                launchCheck.Enabled = false;

                try
                {
                    SetStatus("Fechando Painel TV e node...");
                    StopRunningInstances(targetDir);

                    string tempRoot = Path.Combine(Path.GetTempPath(), "PainelTVInstaller_" + Guid.NewGuid().ToString("N"));
                    string tempZip = Path.Combine(tempRoot, "payload.zip");
                    string extractDir = Path.Combine(tempRoot, "extract");

                    Directory.CreateDirectory(tempRoot);
                    Directory.CreateDirectory(extractDir);

                    SetStatus("Extraindo pacote...");
                    ExtractEmbeddedZip(tempZip);
                    ZipFile.ExtractToDirectory(tempZip, extractDir);

                    SetStatus("Atualizando arquivos...");
                    PrepareTargetDirectory(targetDir);
                    CopyDirectory(extractDir, targetDir);

                    SetStatus("Criando atalhos...");
                    ApplyShortcutPreferences(targetDir, desktopShortcutCheck.Checked, startupCheck.Checked);

                    if (launchCheck.Checked)
                    {
                        SetStatus("Abrindo o Painel TV...");
                        LaunchApp(targetDir);
                    }

                    try { Directory.Delete(tempRoot, true); } catch { }

                    SetStatus("Instalacao concluida.");
                    MessageBox.Show(
                        AppName + " " + Version + " instalado com sucesso.",
                        AppName,
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Information
                    );
                    Close();
                }
                catch (Exception error)
                {
                    SetStatus("Falha na instalacao.");
                    installButton.Enabled = true;
                    cancelButton.Enabled = true;
                    desktopShortcutCheck.Enabled = true;
                    startupCheck.Enabled = true;
                    launchCheck.Enabled = true;
                    MessageBox.Show(
                        "Falha ao instalar " + AppName + ": " + error.Message,
                        AppName,
                        MessageBoxButtons.OK,
                        MessageBoxIcon.Error
                    );
                }
            }

            private void SetStatus(string text)
            {
                statusLabel.Text = text;
                statusLabel.Refresh();
                Application.DoEvents();
            }
        }

        private static void ExtractEmbeddedZip(string zipPath)
        {
            Assembly assembly = Assembly.GetExecutingAssembly();
            using (Stream input = assembly.GetManifestResourceStream(ResourceName))
            {
                if (input == null) throw new InvalidOperationException("Pacote embutido nao encontrado.");
                using (FileStream output = File.Create(zipPath))
                {
                    input.CopyTo(output);
                }
            }
        }

        private static void StopRunningInstances(string targetDir)
        {
            foreach (Process process in Process.GetProcessesByName("PainelTV"))
            {
                TryKill(process);
            }

            try
            {
                string normalizedTarget = targetDir.ToLowerInvariant();
                using (var searcher = new ManagementObjectSearcher("SELECT ProcessId,Name,CommandLine FROM Win32_Process WHERE Name='node.exe' OR Name='PainelTV.exe'"))
                {
                    foreach (ManagementObject item in searcher.Get())
                    {
                        string commandLine = Convert.ToString(item["CommandLine"] ?? "").ToLowerInvariant();
                        string name = Convert.ToString(item["Name"] ?? "");
                        if (name.Equals("PainelTV.exe", StringComparison.OrdinalIgnoreCase) ||
                            commandLine.Contains(normalizedTarget) ||
                            commandLine.Contains("painel tv") ||
                            commandLine.Contains("apps\\server\\src\\server.js"))
                        {
                            int processId = Convert.ToInt32(item["ProcessId"]);
                            try
                            {
                                TryKill(Process.GetProcessById(processId));
                            }
                            catch { }
                        }
                    }
                }
            }
            catch { }
        }

        private static void TryKill(Process process)
        {
            try
            {
                if (process == null || process.HasExited) return;
                process.Kill();
                process.WaitForExit(5000);
            }
            catch { }
        }

        private static void PrepareTargetDirectory(string targetDir)
        {
            if (!Directory.Exists(targetDir))
            {
                Directory.CreateDirectory(targetDir);
                return;
            }

            foreach (string directory in Directory.GetDirectories(targetDir))
            {
                Directory.Delete(directory, true);
            }

            foreach (string file in Directory.GetFiles(targetDir))
            {
                File.SetAttributes(file, FileAttributes.Normal);
                File.Delete(file);
            }
        }

        private static void CopyDirectory(string sourceDir, string targetDir)
        {
            foreach (string directory in Directory.GetDirectories(sourceDir, "*", SearchOption.AllDirectories))
            {
                string relative = directory.Substring(sourceDir.Length).TrimStart(Path.DirectorySeparatorChar);
                Directory.CreateDirectory(Path.Combine(targetDir, relative));
            }

            foreach (string file in Directory.GetFiles(sourceDir, "*", SearchOption.AllDirectories))
            {
                string relative = file.Substring(sourceDir.Length).TrimStart(Path.DirectorySeparatorChar);
                string targetFile = Path.Combine(targetDir, relative);
                string parent = Path.GetDirectoryName(targetFile);
                if (!Directory.Exists(parent)) Directory.CreateDirectory(parent);
                File.Copy(file, targetFile, true);
            }
        }

        private static void ApplyShortcutPreferences(string targetDir, bool createDesktopShortcut, bool createStartupShortcut)
        {
            string desktopDir = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            string startupDir = Environment.GetFolderPath(Environment.SpecialFolder.Startup);
            string desktopShortcut = Path.Combine(desktopDir, AppName + ".lnk");
            string startupShortcut = Path.Combine(startupDir, AppName + ".lnk");

            if (createDesktopShortcut)
            {
                CreateShortcut(desktopShortcut, Path.Combine(targetDir, "PainelTV.exe"), targetDir, Path.Combine(targetDir, "PainelTV.ico"));
            }
            else if (File.Exists(desktopShortcut))
            {
                File.Delete(desktopShortcut);
            }

            if (createStartupShortcut)
            {
                CreateShortcut(startupShortcut, Path.Combine(targetDir, "PainelTV.exe"), targetDir, Path.Combine(targetDir, "PainelTV.ico"));
            }
            else if (File.Exists(startupShortcut))
            {
                File.Delete(startupShortcut);
            }
        }

        private static void CreateShortcut(string shortcutPath, string targetPath, string workingDir, string iconPath)
        {
            Type shellType = Type.GetTypeFromProgID("WScript.Shell");
            object shell = Activator.CreateInstance(shellType);
            object shortcut = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { shortcutPath });
            Type shortcutType = shortcut.GetType();
            shortcutType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, shortcut, new object[] { targetPath });
            shortcutType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, shortcut, new object[] { workingDir });
            shortcutType.InvokeMember("IconLocation", BindingFlags.SetProperty, null, shortcut, new object[] { iconPath });
            shortcutType.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, null);
        }

        private static void LaunchApp(string targetDir)
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = Path.Combine(targetDir, "PainelTV.exe"),
                WorkingDirectory = targetDir,
                UseShellExecute = true
            });
        }
    }
}
