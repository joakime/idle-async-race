package org.eclipse.jetty.tests;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;

public class RaceMain
{
    public static void main(String[] args) throws Exception
    {
        Server server = new Server();

        AtomicInteger fillCount = new AtomicInteger(0);

        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory()
        {
            @Override
            public org.eclipse.jetty.io.Connection newConnection(Connector connector, EndPoint endPoint)
            {
                org.eclipse.jetty.server.HttpConnection conn = new HttpConnection(getHttpConfiguration(), connector, endPoint, getHttpCompliance(), isRecordHttpComplianceViolations())
                {
                    @Override
                    protected boolean fillAndParseForContent()
                    {
                        fillCount.incrementAndGet();
                        return super.fillAndParseForContent();
                    }
                };
                return configure(conn, connector, endPoint);
            }
        };

        ServerConnector connector = new ServerConnector(server, httpConnectionFactory);
        connector.setIdleTimeout(2000);
        connector.setPort(9999);

        server.addConnector(connector);

        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                System.err.println("HANDLE");
                baseRequest.setHandled(true);
                AsyncContext async = request.startAsync();
                ServletInputStream in = request.getInputStream();
                in.setReadListener(new ReadListener()
                {
                    private final byte[] buffer = new byte[1024];

                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        System.err.println("onDataAvailable");
                        while (in.isReady())
                        {
                            int l = in.read(buffer);
                            System.err.println("read=" + l);
                            if (l < 0)
                                break;
                        }
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                        response.getOutputStream().println("onAllDataRead");
                        System.err.println("onAllDataRead");
                        async.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        System.err.println("onError " + t);
                        t.printStackTrace();
                        new Throwable().printStackTrace();
                        // async.complete();
                    }
                });
            }
        });

        server.start();

        URI serverURI = server.getURI();

        try (Socket client = newSocket(serverURI.getHost(), serverURI.getPort()))
        {
            client.setSoTimeout(10000);

            if (client.isClosed())
            {
                throw new IllegalStateException("Client was prematurely closed");
            }

            try (OutputStream os = client.getOutputStream();
                 InputStream is = client.getInputStream())
            {
                os.write(("POST /path HTTP/1.0\r\n" +
                    "Host: " + serverURI.getHost() + ":" + serverURI.getPort() + "\r\n" +
                    "Content-Length: 1000\r\n" +
                    "\r\n").getBytes(StandardCharsets.UTF_8));
                os.flush();

                System.err.println("client Sleep 3s....");
                Thread.sleep(3000);
                System.err.println("client Sending some content...");
                os.write("Some Content\r\n".getBytes(StandardCharsets.UTF_8));

                try
                {
                    System.err.println("client reading (to EOF)....");
                    String in = IO.toString(is);
                    System.err.println("client read - in=" + in);
                }
                catch (SocketTimeoutException e)
                {
                    System.err.println("client read socket timeout exception: " + e);
                }
                catch (Throwable t)
                {
                    System.err.println("client read threw:");
                    t.printStackTrace();
                }

                System.err.println("Waiting....");

                Thread.sleep(5000);
            }
        }
        finally
        {
            server.stop();
            server.join();
        }
        System.out.printf("## fillCount = %,d%n", fillCount.get());
    }

    private static Socket newSocket(String host, int port) throws Exception
    {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        return socket;
    }
}
