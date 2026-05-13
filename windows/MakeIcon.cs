using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;

class IconMaker
{
    static void Main(string[] args)
    {
        string path = args.Length > 0 ? args[0] : "PainelTV.ico";
        using (var bmp = new Bitmap(64, 64))
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using (var bg = new SolidBrush(Color.FromArgb(15, 118, 110)))
                g.FillRoundedRectangle(bg, 6, 8, 52, 38, 8);
            using (var screen = new SolidBrush(Color.White))
                g.FillRectangle(screen, 15, 17, 34, 18);
            using (var stand = new Pen(Color.FromArgb(23, 32, 38), 5))
            {
                g.DrawLine(stand, 32, 46, 32, 55);
                g.DrawLine(stand, 22, 56, 42, 56);
            }
            IntPtr hIcon = bmp.GetHicon();
            using (Icon icon = Icon.FromHandle(hIcon))
            using (FileStream fs = File.Create(path))
                icon.Save(fs);
        }
    }
}

static class GraphicsExtensions
{
    public static void FillRoundedRectangle(this Graphics g, Brush brush, int x, int y, int w, int h, int r)
    {
        using (GraphicsPath path = new GraphicsPath())
        {
            path.AddArc(x, y, r, r, 180, 90);
            path.AddArc(x + w - r, y, r, r, 270, 90);
            path.AddArc(x + w - r, y + h - r, r, r, 0, 90);
            path.AddArc(x, y + h - r, r, r, 90, 90);
            path.CloseFigure();
            g.FillPath(brush, path);
        }
    }
}
