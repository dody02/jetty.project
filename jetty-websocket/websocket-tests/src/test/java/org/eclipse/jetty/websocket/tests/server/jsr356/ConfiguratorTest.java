//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.tests.server.jsr356;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.util.QuoteUtil;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.JsrCreator;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class ConfiguratorTest
{
    private static final Logger LOG = Log.getLogger(ConfiguratorTest.class);
    
    public static class EmptyConfigurator extends ServerEndpointConfig.Configurator
    {
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint(value = "/empty", configurator = EmptyConfigurator.class)
    public static class EmptySocket
    {
        @OnMessage
        public String echo(String message)
        {
            return message;
        }
    }
    
    public static class NoExtensionsConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested)
        {
            return Collections.emptyList();
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint(value = "/no-extensions", configurator = NoExtensionsConfigurator.class)
    public static class NoExtensionsSocket
    {
        @OnMessage
        public String echo(Session session, String message)
        {
            List<Extension> negotiatedExtensions = session.getNegotiatedExtensions();
            if (negotiatedExtensions == null)
            {
                return "negotiatedExtensions=null";
            }
            else
            {
                return "negotiatedExtensions=" + negotiatedExtensions.stream()
                        .map((ext) -> ext.getName())
                        .collect(Collectors.joining(",", "[", "]"));
            }
        }
    }
    
    public static class CaptureHeadersConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            super.modifyHandshake(sec, request, response);
            sec.getUserProperties().put("request-headers", request.getHeaders());
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint(value = "/capture-request-headers", configurator = CaptureHeadersConfigurator.class)
    public static class CaptureHeadersSocket
    {
        @OnMessage
        public String getHeaders(Session session, String headerKey)
        {
            StringBuilder response = new StringBuilder();
            
            response.append("Request Header [").append(headerKey).append("]: ");
            @SuppressWarnings("unchecked")
            Map<String, List<String>> headers = (Map<String, List<String>>) session.getUserProperties().get("request-headers");
            if (headers == null)
            {
                response.append("<no headers found in session.getUserProperties()>");
            }
            else
            {
                List<String> values = headers.get(headerKey);
                if (values == null)
                {
                    response.append("<header not found>");
                }
                else
                {
                    response.append(QuoteUtil.join(values, ","));
                }
            }
            
            return response.toString();
        }
    }
    
    public static class ProtocolsConfigurator extends ServerEndpointConfig.Configurator
    {
        public static AtomicReference<String> seenProtocols = new AtomicReference<>();
        
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            super.modifyHandshake(sec, request, response);
        }
        
        @Override
        public String getNegotiatedSubprotocol(List<String> supported, List<String> requested)
        {
            String seen = QuoteUtil.join(requested, ",");
            seenProtocols.compareAndSet(null, seen);
            return super.getNegotiatedSubprotocol(supported, requested);
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint(value = "/protocols", configurator = ProtocolsConfigurator.class)
    public static class ProtocolsSocket
    {
        @OnMessage
        public String onMessage(Session session, String msg)
        {
            StringBuilder response = new StringBuilder();
            response.append("Requested Protocols: [").append(ProtocolsConfigurator.seenProtocols.get()).append("]");
            return response.toString();
        }
    }
    
    public static class UniqueUserPropsConfigurator extends ServerEndpointConfig.Configurator
    {
        private AtomicInteger upgradeCount = new AtomicInteger(0);
        
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            int upgradeNum = upgradeCount.addAndGet(1);
            LOG.debug("Upgrade Num: {}", upgradeNum);
            sec.getUserProperties().put("upgradeNum", Integer.toString(upgradeNum));
            switch (upgradeNum)
            {
                case 1:
                    sec.getUserProperties().put("apple", "fruit from tree");
                    break;
                case 2:
                    sec.getUserProperties().put("blueberry", "fruit from bush");
                    break;
                case 3:
                    sec.getUserProperties().put("strawberry", "fruit from annual");
                    break;
                default:
                    sec.getUserProperties().put("fruit" + upgradeNum, "placeholder");
                    break;
            }
            
            super.modifyHandshake(sec, request, response);
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint(value = "/unique-user-props", configurator = UniqueUserPropsConfigurator.class)
    public static class UniqueUserPropsSocket
    {
        @OnMessage
        public String onMessage(Session session, String msg)
        {
            String value = (String) session.getUserProperties().get(msg);
            StringBuilder response = new StringBuilder();
            response.append("Requested User Property: [").append(msg).append("] = ");
            if (value == null)
            {
                response.append("<null>");
            }
            else
            {
                response.append('"').append(value).append('"');
            }
            return response.toString();
        }
    }
    
    public static class AddrConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
        {
            InetSocketAddress local = (InetSocketAddress) sec.getUserProperties().get(JsrCreator.PROP_LOCAL_ADDRESS);
            InetSocketAddress remote = (InetSocketAddress) sec.getUserProperties().get(JsrCreator.PROP_REMOTE_ADDRESS);
            
            sec.getUserProperties().put("found.local", local);
            sec.getUserProperties().put("found.remote", remote);
            
            super.modifyHandshake(sec, request, response);
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint(value = "/addr", configurator = AddrConfigurator.class)
    public static class AddressSocket
    {
        @OnMessage
        public String onMessage(Session session, String msg)
        {
            StringBuilder response = new StringBuilder();
            appendPropValue(session, response, "javax.websocket.endpoint.localAddress");
            appendPropValue(session, response, "javax.websocket.endpoint.remoteAddress");
            appendPropValue(session, response, "found.local");
            appendPropValue(session, response, "found.remote");
            return response.toString();
        }
        
        private void appendPropValue(Session session, StringBuilder response, String key)
        {
            InetSocketAddress value = (InetSocketAddress) session.getUserProperties().get(key);
            
            response.append("[").append(key).append("] = ");
            response.append(toSafeAddr(value));
            response.append(System.lineSeparator());
        }
    }
    
    public static class SelectedProtocolConfigurator extends ServerEndpointConfig.Configurator
    {
        @Override
        public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response)
        {
            List<String> selectedProtocol = response.getHeaders().get("Sec-WebSocket-Protocol");
            String protocol = "<>";
            if (selectedProtocol != null && !selectedProtocol.isEmpty())
                protocol = selectedProtocol.get(0);
            config.getUserProperties().put("selected-subprotocol", protocol);
        }
    }
    
    public static class GmtTimeDecoder implements Decoder.Text<Calendar>
    {
        private TimeZone TZ;
        
        @Override
        public Calendar decode(String s) throws DecodeException
        {
            if (TZ == null)
                throw new DecodeException(s, ".init() not called");
            try
            {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                dateFormat.setTimeZone(TZ);
                Date time = dateFormat.parse(s);
                Calendar cal = Calendar.getInstance();
                cal.setTimeZone(TZ);
                cal.setTime(time);
                return cal;
            }
            catch (ParseException e)
            {
                throw new DecodeException(s, "Unable to decode Time", e);
            }
        }
        
        @Override
        public void init(EndpointConfig config)
        {
            TZ = TimeZone.getTimeZone("GMT+0");
        }
        
        @Override
        public void destroy()
        {
        }
        
        @Override
        public boolean willDecode(String s)
        {
            return true;
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint(value = "/timedecoder",
            subprotocols = {"time", "gmt"},
            configurator = SelectedProtocolConfigurator.class,
            decoders = {GmtTimeDecoder.class})
    public static class TimeDecoderSocket
    {
        private TimeZone TZ = TimeZone.getTimeZone("GMT+0");
        
        @OnMessage
        public String onMessage(Calendar cal)
        {
            return String.format("cal=%s", newDateFormat().format(cal.getTime()));
        }
        
        private SimpleDateFormat newDateFormat()
        {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd G 'at' HH:mm:ss Z", Locale.ENGLISH);
            dateFormat.setTimeZone(TZ);
            return dateFormat;
        }
    }
    
    private static Server server;
    private static URI baseServerUri;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);
        
        ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
        container.addEndpoint(CaptureHeadersSocket.class);
        container.addEndpoint(EmptySocket.class);
        container.addEndpoint(NoExtensionsSocket.class);
        container.addEndpoint(ProtocolsSocket.class);
        container.addEndpoint(UniqueUserPropsSocket.class);
        container.addEndpoint(AddressSocket.class);
        container.addEndpoint(TimeDecoderSocket.class);
        
        server.start();
        baseServerUri = WSURI.toWebsocket(server.getURI()).resolve("/");
    }
    
    public static String toSafeAddr(InetSocketAddress addr)
    {
        if (addr == null)
        {
            return "<null>";
        }
        return String.format("%s:%d", addr.getAddress().getHostAddress(), addr.getPort());
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Rule
    public TestName testname = new TestName();
    
    private WebSocketClient client;
    
    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }
    
    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }
    
    @Test
    public void testEmptyConfigurator() throws Exception
    {
        URI wsUri = baseServerUri.resolve("/empty");
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.addExtensions("identity");
        Future<org.eclipse.jetty.websocket.api.Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        org.eclipse.jetty.websocket.api.Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        List<ExtensionConfig> negotiatedExtensions = clientSession.getUpgradeResponse().getExtensions();
        assertThat("UpgradeResponse.extensions", negotiatedExtensions, notNullValue());
        assertThat("UpgradeResponse.extensions.size", negotiatedExtensions.size(), is(1));
        assertThat("UpgradeResponse.extensions[0]", negotiatedExtensions.get(0).toString(), is("identity"));
        
        clientSession.close();
    }
    
    @Test
    public void testNoExtensionsConfigurator() throws Exception
    {
        URI wsUri = baseServerUri.resolve("/no-extensions");
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.addExtensions("identity");
        Future<org.eclipse.jetty.websocket.api.Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        org.eclipse.jetty.websocket.api.Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("NegoExts");
        
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming Message", incomingMessage, is("negotiatedExtensions=[]"));
        
        clientSession.close();
    }
    
    @Test
    public void testCaptureRequestHeadersConfigurator() throws Exception
    {
        URI wsUri = baseServerUri.resolve("/capture-request-headers");
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setHeader("X-Dummy", "Bogus");
        Future<org.eclipse.jetty.websocket.api.Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        org.eclipse.jetty.websocket.api.Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("X-Dummy");
        
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming Message", incomingMessage, is("Request Header [X-Dummy]: \"Bogus\""));
        
        clientSession.close();
    }
    
    @Test
    public void testUniqueUserPropsConfigurator() throws Exception
    {
        URI wsUri = baseServerUri.resolve("/unique-user-props");
        
        // First Request
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        Future<org.eclipse.jetty.websocket.api.Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        org.eclipse.jetty.websocket.api.Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("apple"); // first request has this UserProperty
        
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming Message", incomingMessage, is("Requested User Property: [apple] = \"fruit from tree\""));
        
        clientSession.close();
        
        // Second request
        clientSocket = new TrackingEndpoint(testname.getMethodName());
        upgradeRequest = new ClientUpgradeRequest();
        clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("apple"); // as this is second request, this should be null
        clientSession.getRemote().sendString("blueberry"); // second request has this UserProperty
        
        incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming Message", incomingMessage, is("Requested User Property: [apple] = <null>"));
        incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming Message", incomingMessage, is("Requested User Property: [blueberry] = \"fruit from bush\""));
        
        clientSession.close();
    }
    
    @Test
    public void testUserPropsAddress() throws Exception
    {
        URI wsUri = baseServerUri.resolve("/addr");
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        Future<org.eclipse.jetty.websocket.api.Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        org.eclipse.jetty.websocket.api.Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        InetSocketAddress expectedLocal = clientSession.getLocalAddress();
        InetSocketAddress expectedRemote = clientSession.getRemoteAddress();
        
        clientSession.getRemote().sendString("addr");
        
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        
        StringWriter expected = new StringWriter();
        PrintWriter out = new PrintWriter(expected);
        // local <-> remote are opposite on server (duh)
        out.printf("[javax.websocket.endpoint.localAddress] = %s%n", toSafeAddr(expectedRemote));
        out.printf("[javax.websocket.endpoint.remoteAddress] = %s%n", toSafeAddr(expectedLocal));
        out.printf("[found.local] = %s%n", toSafeAddr(expectedRemote));
        out.printf("[found.remote] = %s%n", toSafeAddr(expectedLocal));
        
        assertThat("Frame Response", incomingMessage, is(expected.toString()));
        
        clientSession.close();
    }
    
    /**
     * Test of Sec-WebSocket-Protocol, as seen in RFC-6455, 1 protocol
     *
     * @throws Exception on test failure
     */
    @Test
    public void testProtocol_Single() throws Exception
    {
        URI wsUri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("echo");
        Future<org.eclipse.jetty.websocket.api.Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        org.eclipse.jetty.websocket.api.Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("getProtocols");
        
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming message", incomingMessage, is("Requested Protocols: [\"echo\"]"));
        
        clientSession.close();
    }
    
    /**
     * Test of Sec-WebSocket-Protocol, as seen in RFC-6455, 3 protocols
     *
     * @throws Exception on test failure
     */
    @Test
    public void testProtocol_Triple() throws Exception
    {
        URI wsUri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("echo", "chat", "status");
        Future<org.eclipse.jetty.websocket.api.Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        org.eclipse.jetty.websocket.api.Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("getProtocols");
        
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming message", incomingMessage, is("Requested Protocols: [\"echo\",\"chat\",\"status\"]"));
        
        clientSession.close();
    }
    
    /**
     * Test of Sec-WebSocket-Protocol, using all lowercase header
     *
     * @throws Exception on test failure
     */
    @Test
    public void testProtocol_LowercaseHeader() throws Exception
    {
        URI wsUri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setHeader("sec-websocket-protocol", "echo, chat, status");
        Future<org.eclipse.jetty.websocket.api.Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        org.eclipse.jetty.websocket.api.Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("getProtocols");
        
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming message", incomingMessage, is("Requested Protocols: [\"echo\",\"chat\",\"status\"]"));
        
        clientSession.close();
    }
    
    /**
     * Test of Sec-WebSocket-Protocol, using non-spec case header
     *
     * @throws Exception on test failure
     */
    @Test
    public void testProtocol_AltHeaderCase() throws Exception
    {
        URI wsUri = baseServerUri.resolve("/protocols");
        ProtocolsConfigurator.seenProtocols.set(null);
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        // header name is not to spec (case wise)
        upgradeRequest.setHeader("Sec-Websocket-Protocol", "echo, chat, status");
        Future<org.eclipse.jetty.websocket.api.Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        org.eclipse.jetty.websocket.api.Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("getProtocols");
        
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming message", incomingMessage, is("Requested Protocols: [\"echo\",\"chat\",\"status\"]"));
        
        clientSession.close();
    }
    
    /**
     * Test of Sec-WebSocket-Protocol, using non-spec case header
     */
    @Test
    public void testDecoderWithProtocol() throws Exception
    {
        URI wsUri = baseServerUri.resolve("/timedecoder");
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("gmt");
        Future<org.eclipse.jetty.websocket.api.Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        org.eclipse.jetty.websocket.api.Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("2016-06-20T14:27:44");
        
        String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming message", incomingMessage, is("cal=2016.06.20 AD at 14:27:44 +0000"));
        
        clientSession.close();
    }
}
