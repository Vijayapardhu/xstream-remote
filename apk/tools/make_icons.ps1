Add-Type -AssemblyName System.Drawing

function New-LauncherIcon {
    param([int]$Size, [string]$Path)

    $bmp = New-Object System.Drawing.Bitmap($Size, $Size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $radius = [double]$Size * 0.22
    $d = $radius * 2
    $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
    $gp.AddArc(0, 0, $d, $d, 180, 90)
    $gp.AddArc($Size - $d, 0, $d, $d, 270, 90)
    $gp.AddArc($Size - $d, $Size - $d, $d, $d, 0, 90)
    $gp.AddArc(0, $Size - $d, $d, $d, 90, 90)
    $gp.CloseFigure()

    $rect = New-Object System.Drawing.Rectangle(0, 0, $Size, $Size)
    $top = [System.Drawing.Color]::FromArgb(255, 255, 74, 84)
    $bottom = [System.Drawing.Color]::FromArgb(255, 201, 0, 0)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $top, $bottom, 90)
    $g.FillPath($brush, $gp)

    $penWidth = [double][Math]::Max(2.0, $Size * 0.059)
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, [single]$penWidth)
    $r = [double]$Size * 0.204
    $cx = [double]$Size / 2.0
    $cy = [double]$Size / 2.0
    $g.DrawEllipse($pen, [single]($cx - $r), [single]($cy - $r), [single]($r * 2), [single]($r * 2))

    $dot = [double]$Size * 0.046
    $white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $g.FillEllipse($white, [single]($cx - $dot), [single]($cy - $dot), [single]($dot * 2), [single]($dot * 2))

    $g.Dispose()
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $pen.Dispose()
    $brush.Dispose()
    $white.Dispose()
    $gp.Dispose()
}

$root = Join-Path (Split-Path -Parent $PSScriptRoot) 'res'
$map = @{
    'mipmap-mdpi'    = 48
    'mipmap-hdpi'    = 72
    'mipmap-xhdpi'   = 96
    'mipmap-xxhdpi'  = 144
    'mipmap-xxxhdpi' = 192
}

foreach ($key in $map.Keys) {
    $size = $map[$key]
    $path = Join-Path (Join-Path $root $key) 'ic_launcher.png'
    New-LauncherIcon -Size $size -Path $path
    $roundPath = Join-Path (Join-Path $root $key) 'ic_launcher_round.png'
    Copy-Item $path $roundPath -Force
    "$key -> $size px"
}
"icons written"
