param([long]$Handle, [double]$DragX, [double]$DragY, [string]$Screenshot)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class WindowInputProbe {
 [StructLayout(LayoutKind.Sequential)] public struct Rect { public int L,T,R,B; }
 [StructLayout(LayoutKind.Sequential)] public struct Point { public int X,Y; }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out Rect r);
 [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h,ref Point p);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr c);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern bool GetCursorPos(out Point p);
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags,uint dx,uint dy,uint data,UIntPtr extra);
}
'@
[void][WindowInputProbe]::SetThreadDpiAwarenessContext([IntPtr](-4))
$hwnd = [IntPtr]$Handle
[void][WindowInputProbe]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 250
$before = New-Object WindowInputProbe+Rect
$origin = New-Object WindowInputProbe+Point
[void][WindowInputProbe]::GetWindowRect($hwnd,[ref]$before)
[void][WindowInputProbe]::ClientToScreen($hwnd,[ref]$origin)
$scale = [WindowInputProbe]::GetDpiForWindow($hwnd) / 96.0
$x = $origin.X + [int][Math]::Round($DragX * $scale)
$y = $origin.Y + [int][Math]::Round($DragY * $scale)
$available = [WindowInputProbe]::SetCursorPos($x,$y)
$foreground = [WindowInputProbe]::GetForegroundWindow() -eq $hwnd
if ($available -and $foreground) {
    Start-Sleep -Milliseconds 150
    [WindowInputProbe]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
    try { for($step=1;$step -le 8;$step++){ [void][WindowInputProbe]::SetCursorPos(($x+$step*10),($y+[int][Math]::Round($step*45.0/8)));Start-Sleep -Milliseconds 30 } }
    finally { [WindowInputProbe]::mouse_event(4,0,0,0,[UIntPtr]::Zero) }
    Start-Sleep -Milliseconds 400
}
$after = New-Object WindowInputProbe+Rect
$pointer = New-Object WindowInputProbe+Point
[void][WindowInputProbe]::GetWindowRect($hwnd,[ref]$after)
[void][WindowInputProbe]::GetCursorPos([ref]$pointer)
$result = @{ inputAvailable=$available;foreground=$foreground;dpiScale=$scale;dx=$after.L-$before.L;dy=$after.T-$before.T;pointerX=$pointer.X;pointerY=$pointer.Y;expectedX=$x+80;expectedY=$y+45 }
$result.passed = $available -and $foreground -and [Math]::Abs($result.dx-80) -le 4 -and [Math]::Abs($result.dy-45) -le 4
try {
 $bitmap=New-Object Drawing.Bitmap(($after.R-$after.L),($after.B-$after.T));$graphics=[Drawing.Graphics]::FromImage($bitmap)
 try {$graphics.CopyFromScreen($after.L,$after.T,0,0,$bitmap.Size);$bitmap.Save($Screenshot,[Drawing.Imaging.ImageFormat]::Png)}finally{$graphics.Dispose();$bitmap.Dispose()}
 $result.capture='captured'
} catch { $result.capture='unavailable';$result.captureError=$_.Exception.Message }
$result | ConvertTo-Json -Compress
