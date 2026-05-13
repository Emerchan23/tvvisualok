using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;

namespace PainelTvSetup
{
    internal static class Program
    {
        private const string ResourceName = "PainelPackage.zip";

        private static int Main()
        {
            try
            {
                string target = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "PainelTV");
                string tempZip = Path.Combine(Path.GetTempPath(), "PainelTV-" + Guid.NewGuid().ToString("N") + ".zip");

                Console.Title = "Instalador Painel TV";
                Console.WriteLine("Instalando Painel TV...");
                Console.WriteLine("Destino: " + target);

                using (Stream resource = Assembly.GetExecutingAssembly().GetManifestResourceStream(ResourceName))
                {
                    if (resource == null) throw new InvalidOperationException("Pacote interno nao encontrado.");
                    using (FileStream output = File.Create(tempZip))
                    {
                        resource.CopyTo(output);
                    }
                }

                if (Directory.Exists(target))
                {
                    Directory.Delete(target, true);
                }
                Directory.CreateDirectory(target);
                ZipFile.ExtractToDirectory(tempZip, target);
                File.Delete(tempZip);

                string installer = Path.Combine(target, "INSTALAR-PAINEL-WINDOWS.bat");
                if (!File.Exists(installer)) throw new FileNotFoundException("Instalador interno nao encontrado.", installer);

                Process process = Process.Start(new ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = "/c \"" + installer + "\"",
                    WorkingDirectory = target,
                    UseShellExecute = false
                });
                process.WaitForExit();

                Console.WriteLine();
                Console.WriteLine("Painel TV instalado/atualizado com sucesso.");
                Console.WriteLine("Pasta instalada: " + target);
                Console.WriteLine("Pressione qualquer tecla para sair.");
                Console.ReadKey(true);
                return process.ExitCode;
            }
            catch (Exception error)
            {
                Console.WriteLine();
                Console.WriteLine("Falha ao instalar Painel TV:");
                Console.WriteLine(error.Message);
                Console.WriteLine("Pressione qualquer tecla para sair.");
                Console.ReadKey(true);
                return 1;
            }
        }
    }
}
