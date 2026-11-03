param(
    [string]$OutputRoot = (Join-Path $PSScriptRoot '..\app\src\main\res'),
    [string]$Master = (Join-Path $PSScriptRoot 'icon-master.png')
)

Add-Type -AssemblyName System.Drawing

if (-not (Test-Path -LiteralPath $Master)) {
    throw "Icon master not found: $Master"
}

$sizes = [ordered]@{
    'mdpi'    = 48
    'hdpi'    = 72
    'xhdpi'   = 96
    'xxhdpi'  = 144
    'xxxhdpi' = 192
}

$source = [System.Drawing.Bitmap]::FromFile($Master)

foreach ($entry in $sizes.GetEnumerator()) {
    $dir = Join-Path $OutputRoot ("mipmap-" + $entry.Key)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $size = [int]$entry.Value

    # Normal square icon. The master already has transparent rounded corners.
    $square = New-Object System.Drawing.Bitmap($size, $size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $sg = [System.Drawing.Graphics]::FromImage($square)
    $sg.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $sg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $sg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $dest = New-Object System.Drawing.Rectangle(0, 0, $size, $size)
    $sg.DrawImage($source, $dest, 0, 0, $source.Width, $source.Height,
        [System.Drawing.GraphicsUnit]::Pixel)
    $sg.Dispose()
    $square.Save((Join-Path $dir 'ic_launcher.png'),
        [System.Drawing.Imaging.ImageFormat]::Png)
    $square.Dispose()

    # Circular icon for launchers that request roundIcon.
    $round = New-Object System.Drawing.Bitmap($size, $size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $rg = [System.Drawing.Graphics]::FromImage($round)
    $rg.Clear([System.Drawing.Color]::Transparent)
    $rg.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
    $rg.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $rg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $rg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $rg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

    $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
    $clip.AddEllipse(0, 0, $size - 1, $size - 1)
    $rg.SetClip($clip)

    # Slight overscan keeps the rounded-square master's transparent top edge
    # from showing through the circle mask.
    $over = [float]$size * 0.04
    $rg.DrawImage($source, (-$over / 2), (-$over / 2),
        ($size + $over), ($size + $over))

    $rg.Dispose()
    $round.Save((Join-Path $dir 'ic_launcher_round.png'),
        [System.Drawing.Imaging.ImageFormat]::Png)
    $round.Dispose()

    Write-Output "wrote mipmap-$($entry.Key) ($size px)"
}

$source.Dispose()
Write-Output 'icons generated from tools/icon-master.png'
