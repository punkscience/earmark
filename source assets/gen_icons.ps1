$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @"
using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

public static class IconGen {
    // Cobalt blue gradient colors (top-left to bottom-right)
    public static readonly Color CobaltLight = Color.FromArgb(0xFF, 0x4A, 0x8F, 0xFF);
    public static readonly Color CobaltDark  = Color.FromArgb(0xFF, 0x00, 0x21, 0x88);

    public static Bitmap LoadSource(string path) {
        return new Bitmap(path);
    }

    public static Rectangle DetectIconBounds(Bitmap src) {
        var rect = new Rectangle(0, 0, src.Width, src.Height);
        var data = src.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        var bytes = new byte[data.Stride * data.Height];
        System.Runtime.InteropServices.Marshal.Copy(data.Scan0, bytes, 0, bytes.Length);
        src.UnlockBits(data);

        int minX = src.Width, minY = src.Height, maxX = 0, maxY = 0;
        for (int y = 0; y < src.Height; y++) {
            for (int x = 0; x < src.Width; x++) {
                int i = y * data.Stride + x * 4;
                int b = bytes[i], g = bytes[i+1], r = bytes[i+2];
                if (r < 240 || g < 240 || b < 240) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        int dim = Math.Max(bw, bh);
        int cx = (minX + maxX) / 2;
        int cy = (minY + maxY) / 2;
        return new Rectangle(cx - dim / 2, cy - dim / 2, dim, dim);
    }

    // Extract white silhouette as alpha bitmap at outSize x outSize.
    // alpha = whiteness of the source pixel (min channel curved through threshold).
    public static Bitmap ExtractSilhouette(Bitmap src, Rectangle bounds, int outSize) {
        var cropped = new Bitmap(outSize, outSize, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(cropped)) {
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.SmoothingMode = SmoothingMode.HighQuality;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.DrawImage(src, new Rectangle(0, 0, outSize, outSize), bounds, GraphicsUnit.Pixel);
        }

        var rect = new Rectangle(0, 0, outSize, outSize);
        var data = cropped.LockBits(rect, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
        var bytes = new byte[data.Stride * data.Height];
        System.Runtime.InteropServices.Marshal.Copy(data.Scan0, bytes, 0, bytes.Length);

        for (int i = 0; i < bytes.Length; i += 4) {
            int b = bytes[i], g = bytes[i+1], r = bytes[i+2];
            int minCh = Math.Min(r, Math.Min(g, b));
            int alpha;
            if (minCh >= 220) alpha = 255;
            else if (minCh > 140) alpha = (minCh - 140) * 255 / 80;
            else alpha = 0;

            bytes[i] = 255;
            bytes[i+1] = 255;
            bytes[i+2] = 255;
            bytes[i+3] = (byte)alpha;
        }

        System.Runtime.InteropServices.Marshal.Copy(bytes, 0, data.Scan0, bytes.Length);
        cropped.UnlockBits(data);
        return cropped;
    }

    static GraphicsPath RoundedRect(float x, float y, float w, float h, float r) {
        var path = new GraphicsPath();
        path.AddArc(x, y, r * 2, r * 2, 180, 90);
        path.AddArc(x + w - r * 2, y, r * 2, r * 2, 270, 90);
        path.AddArc(x + w - r * 2, y + h - r * 2, r * 2, r * 2, 0, 90);
        path.AddArc(x, y + h - r * 2, r * 2, r * 2, 90, 90);
        path.CloseFigure();
        return path;
    }

    public static Bitmap BuildLauncherIcon(Bitmap silhouette, int size) {
        var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bmp)) {
            g.SmoothingMode = SmoothingMode.HighQuality;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;

            float radius = size * 0.22f;
            using (var path = RoundedRect(0, 0, size, size, radius))
            using (var brush = new LinearGradientBrush(
                new PointF(0, 0), new PointF(size, size),
                CobaltLight, CobaltDark)) {
                g.FillPath(brush, path);
            }

            g.DrawImage(silhouette, new Rectangle(0, 0, size, size));
        }
        return bmp;
    }

    public static Bitmap BuildLauncherIconRound(Bitmap silhouette, int size) {
        var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bmp)) {
            g.SmoothingMode = SmoothingMode.HighQuality;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;

            using (var path = new GraphicsPath()) {
                path.AddEllipse(0, 0, size, size);
                using (var brush = new LinearGradientBrush(
                    new PointF(0, 0), new PointF(size, size),
                    CobaltLight, CobaltDark)) {
                    g.FillPath(brush, path);
                }
                g.SetClip(path);
                g.DrawImage(silhouette, new Rectangle(0, 0, size, size));
                g.ResetClip();
            }
        }
        return bmp;
    }

    // Adaptive icon foreground: silhouette scaled into safe zone (66dp / 108dp = 61%) on transparent.
    public static Bitmap BuildAdaptiveForeground(Bitmap silhouette, int size) {
        var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bmp)) {
            g.SmoothingMode = SmoothingMode.HighQuality;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;

            float scale = 66f / 108f;
            int innerSize = (int)(size * scale);
            int offset = (size - innerSize) / 2;
            g.DrawImage(silhouette, new Rectangle(offset, offset, innerSize, innerSize));
        }
        return bmp;
    }
}
"@ -ReferencedAssemblies System.Drawing, System.Drawing.Common, System.Drawing.Primitives, System.Private.Windows.GdiPlus, System.Private.Windows.Core

$root = "C:\Users\darry\projects\earmarks"
$srcPath = Join-Path $root "source assets\icon.jpg"

Write-Host "Loading source: $srcPath"
$src = [IconGen]::LoadSource($srcPath)
Write-Host "Source size: $($src.Width)x$($src.Height)"

$bounds = [IconGen]::DetectIconBounds($src)
Write-Host "Icon bounds: x=$($bounds.X) y=$($bounds.Y) w=$($bounds.Width) h=$($bounds.Height)"

Write-Host "Extracting silhouette (1024px master)..."
$silhouette = [IconGen]::ExtractSilhouette($src, $bounds, 1024)

$densities = @(
    @{ name = "mdpi";    launcher = 48;  foreground = 108 },
    @{ name = "hdpi";    launcher = 72;  foreground = 162 },
    @{ name = "xhdpi";   launcher = 96;  foreground = 216 },
    @{ name = "xxhdpi";  launcher = 144; foreground = 324 },
    @{ name = "xxxhdpi"; launcher = 192; foreground = 432 }
)

foreach ($d in $densities) {
    $dir = Join-Path $root "app\src\main\res\mipmap-$($d.name)"
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

    Write-Host "Generating $($d.name) (launcher=$($d.launcher), fg=$($d.foreground))"

    $launcher = [IconGen]::BuildLauncherIcon($silhouette, $d.launcher)
    $launcher.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $launcher.Dispose()

    $round = [IconGen]::BuildLauncherIconRound($silhouette, $d.launcher)
    $round.Save((Join-Path $dir "ic_launcher_round.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $round.Dispose()

    $fg = [IconGen]::BuildAdaptiveForeground($silhouette, $d.foreground)
    $fg.Save((Join-Path $dir "ic_launcher_foreground.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $fg.Dispose()
}

# Master preview PNG (1024) for inspection
$masterPath = Join-Path $root "source assets\icon_master_preview.png"
$masterIcon = [IconGen]::BuildLauncherIcon($silhouette, 1024)
$masterIcon.Save($masterPath, [System.Drawing.Imaging.ImageFormat]::Png)
$masterIcon.Dispose()
Write-Host "Preview saved: $masterPath"

$silhouette.Dispose()
$src.Dispose()
Write-Host "Done."
