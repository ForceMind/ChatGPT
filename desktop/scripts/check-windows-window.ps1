param([long]$Handle, [double]$DragX, [double]$DragY, [string]$Screenshot)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class WindowInputProbe {
 [StructLayout(LayoutKind.Sequential)] public struct Rect { public int L,T,R,B; }
 [StructLayout(LayoutKind.Sequential)] public struct Point { public int X,Y; }
 [StructLayout(LayoutKind.Sequential)] public struct Gui { public uint Size,Flags;public IntPtr Active,Focus,Capture,MenuOwner,MoveSize,Caret;public Rect CaretRect; }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h,out Rect r);
 [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h,ref Point p);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr c);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern bool GetCursorPos(out Point p);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h,out uint process);
 [DllImport("user32.dll")] public static extern bool GetGUIThreadInfo(uint thread,ref Gui info);
 [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h,uint msg,IntPtr w,IntPtr l);
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
$hitTest = [WindowInputProbe]::SendMessage($hwnd,0x84,[IntPtr]::Zero,[IntPtr](($x -band 65535) -bor (($y -band 65535) -shl 16))).ToInt64()
$initial = $before
$moveLoopReady = $false
if ($available -and $foreground) {
    Start-Sleep -Milliseconds 150
    [WindowInputProbe]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
    Start-Sleep -Milliseconds 250
    try {
        # Windows starts caption movement after an initial drag threshold.
        # Measure the requested displacement only after the OS move loop is active.
        [void][WindowInputProbe]::SetCursorPos(($x+10),($y+6))
        [uint32]$ownerProcess=0
        $thread=[WindowInputProbe]::GetWindowThreadProcessId($hwnd,[ref]$ownerProcess)
        for($attempt=0;$attempt -lt 30;$attempt++) {
            $gui=New-Object WindowInputProbe+Gui
            $gui.Size=[Runtime.InteropServices.Marshal]::SizeOf($gui)
            if([WindowInputProbe]::GetGUIThreadInfo($thread,[ref]$gui) -and ($gui.Flags -band 2) -ne 0 -and $gui.MoveSize -eq $hwnd){$moveLoopReady=$true;break}
            Start-Sleep -Milliseconds 50
        }
        Start-Sleep -Milliseconds 100
        [void][WindowInputProbe]::GetWindowRect($hwnd,[ref]$before)
        $x+=10;$y+=6
        for($step=1;$step -le 8;$step++){ [void][WindowInputProbe]::SetCursorPos(($x+$step*10),($y+[int][Math]::Round($step*45.0/8)));Start-Sleep -Milliseconds 40 }
    }
    finally { [WindowInputProbe]::mouse_event(4,0,0,0,[UIntPtr]::Zero) }
    Start-Sleep -Milliseconds 400
}
$after = New-Object WindowInputProbe+Rect
$pointer = New-Object WindowInputProbe+Point
[void][WindowInputProbe]::GetWindowRect($hwnd,[ref]$after)
[void][WindowInputProbe]::GetCursorPos([ref]$pointer)
$result = @{ inputAvailable=$available;foreground=$foreground;hitTest=$hitTest;moveLoopReady=$moveLoopReady;dpiScale=$scale;initial=@{x=$initial.L;y=$initial.T};before=@{x=$before.L;y=$before.T;width=$before.R-$before.L;height=$before.B-$before.T};after=@{x=$after.L;y=$after.T;width=$after.R-$after.L;height=$after.B-$after.T};dx=$after.L-$before.L;dy=$after.T-$before.T;pointerX=$pointer.X;pointerY=$pointer.Y;expectedX=$x+80;expectedY=$y+45 }
$result.passed = $available -and $foreground -and $hitTest -eq 2 -and $moveLoopReady -and [Math]::Abs($result.dx-80) -le 4 -and [Math]::Abs($result.dy-45) -le 4
try {
 $bitmap=New-Object Drawing.Bitmap(($after.R-$after.L),($after.B-$after.T));$graphics=[Drawing.Graphics]::FromImage($bitmap)
 try {$graphics.CopyFromScreen($after.L,$after.T,0,0,$bitmap.Size);$bitmap.Save($Screenshot,[Drawing.Imaging.ImageFormat]::Png)}finally{$graphics.Dispose();$bitmap.Dispose()}
 $result.capture='captured'
} catch { $result.capture='unavailable';$result.captureError=$_.Exception.Message }
$result | ConvertTo-Json -Compress
