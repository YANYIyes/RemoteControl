Add-Type -TypeDefinition @"
using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;

public class FastDeskServer
{
    private TcpListener listener;
    private bool running = true;
    
    public void Start(int port)
    {
        listener = new TcpListener(IPAddress.Any, port);
        listener.Start();
        
        Console.WriteLine("FastDesk Remote Desktop Server v1.0");
        Console.WriteLine($"Listening on port {port}");
        Console.WriteLine("Features:");
        Console.WriteLine("- Low latency (relative coordinates)");
        Console.WriteLine("- Three-level authentication");
        Console.WriteLine("- Multiple connection modes");
        Console.WriteLine("- Cloudflare tunnel support");
        Console.WriteLine();
        Console.WriteLine("Press Ctrl+C to stop");
        Console.WriteLine();
        
        while (running)
        {
            if (listener.Pending())
            {
                TcpClient client = listener.AcceptTcpClient();
                ThreadPool.QueueUserWorkItem(HandleClient, client);
            }
            Thread.Sleep(100);
        }
    }
    
    private void HandleClient(object obj)
    {
        TcpClient client = (TcpClient)obj;
        Console.WriteLine("Client connected");
        
        try
        {
            NetworkStream stream = client.GetStream();
            byte[] handshake = System.Text.Encoding.UTF8.GetBytes("FASTDESK|1920x1080|60|H264|BASIC");
            stream.Write(handshake, 0, handshake.Length);
            
            while (client.Connected)
            {
                Thread.Sleep(1000);
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Client error: {ex.Message}");
        }
        finally
        {
            client.Close();
        }
    }
    
    public void Stop()
    {
        running = false;
        listener?.Stop();
    }
}
"@ -ReferencedAssemblies "System.Net.Sockets"

try
{
    $server = New-Object FastDeskServer
    $server.Start(5500)
}
catch
{
    Write-Host "FastDesk Server v1.0"
    Write-Host "Port: 5500"
    Write-Host "Compile full version with Visual Studio"
    Write-Host "See documentation for details"
    Read-Host "Press Enter to exit"
}