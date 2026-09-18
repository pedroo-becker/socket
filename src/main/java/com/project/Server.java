package com.project;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Server {
    private static final int DEFAULT_PORT = 5000;
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private final Map<String, Set<ClientSession>> rooms = new ConcurrentHashMap<>();
    private final ExecutorService clients = Executors.newCachedThreadPool();

    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? DEFAULT_PORT : Integer.parseInt(args[0]);
        new Server().start(port);
    }

    private void start(int port) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("Servidor ouvindo na porta " + port);
            while (true) {
                Socket socket = server.accept();
                clients.submit(() -> handle(new ClientSession(socket)));
            }
        }
    }

    private void handle(ClientSession session) {
        try (session; BufferedReader input = new BufferedReader(new InputStreamReader(
                session.socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                try {
                    process(session, Protocol.decode(line));
                } catch (Protocol.ProtocolException | IllegalArgumentException exception) {
                    session.send(Protocol.encode(Protocol.ERROR, Protocol.base64(exception.getMessage())));
                }
            }
        } catch (IOException ignored) {
        } finally {
            leaveRoom(session);
        }
    }

    private void process(ClientSession session, Protocol.Packet packet) throws Protocol.ProtocolException, IOException {
        switch (packet.command()) {
            case Protocol.JOIN -> join(session, packet.fields());
            case Protocol.MESSAGE -> message(session, packet.fields());
            case Protocol.FILE -> file(session, packet.fields());
            case Protocol.QUIT -> session.socket.close();
            default -> throw new Protocol.ProtocolException("Comando desconhecido: " + packet.command());
        }
    }

    private void join(ClientSession session, java.util.List<String> fields) throws Protocol.ProtocolException {
        if (fields.size() != 2) {
            throw new Protocol.ProtocolException("JOIN requer sala e nome");
        }
        String room = requireName(fields.get(0), "Sala");
        String name = requireName(fields.get(1), "Nome");
        leaveRoom(session);
        session.room = room;
        session.name = name;
        rooms.computeIfAbsent(room, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        broadcast(room, Protocol.encode(Protocol.SYSTEM,
                Protocol.base64(name + " entrou na sala.")));
    }

    private void message(ClientSession session, java.util.List<String> fields) throws Protocol.ProtocolException {
        requireJoined(session);
        if (fields.size() != 1) {
            throw new Protocol.ProtocolException("MESSAGE requer um texto em Base64");
        }
        String text = Protocol.unbase64Text(fields.get(0));
        if (text.length() > 8_000) {
            throw new Protocol.ProtocolException("Mensagem excede 8000 caracteres");
        }
        broadcast(session.room, Protocol.encode(Protocol.MESSAGE,
                Protocol.base64(session.name), Protocol.base64(text)));
    }

    private void file(ClientSession session, java.util.List<String> fields) throws Protocol.ProtocolException {
        requireJoined(session);
        if (fields.size() != 2) {
            throw new Protocol.ProtocolException("FILE requer nome e conteudo em Base64");
        }
        String filename = Protocol.unbase64Text(fields.get(0));
        if (filename.isBlank() || filename.length() > 120 || filename.contains("/") || filename.contains("\\")) {
            throw new Protocol.ProtocolException("Nome de arquivo invalido");
        }
        byte[] content = Protocol.unbase64Bytes(fields.get(1));
        if (content.length > MAX_FILE_BYTES) {
            throw new Protocol.ProtocolException("Arquivo excede 10 MB");
        }
        broadcast(session.room, Protocol.encode(Protocol.FILE,
                Protocol.base64(session.name), Protocol.base64(filename),
                java.util.Base64.getEncoder().encodeToString(content)));
    }

    private void requireJoined(ClientSession session) throws Protocol.ProtocolException {
        if (session.room == null) {
            throw new Protocol.ProtocolException("Entre em uma sala antes de enviar dados");
        }
    }

    private String requireName(String value, String label) throws Protocol.ProtocolException {
        if (value == null || value.isBlank() || value.length() > 64 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new Protocol.ProtocolException(label + " invalido");
        }
        return value;
    }

    private void leaveRoom(ClientSession session) {
        String room = session.room;
        String name = session.name;
        if (room == null) {
            return;
        }
        session.room = null;
        session.name = null;
        Set<ClientSession> members = rooms.get(room);
        if (members != null) {
            members.remove(session);
            if (members.isEmpty()) {
                rooms.remove(room, members);
            }
        }
        broadcast(room, Protocol.encode(Protocol.SYSTEM, Protocol.base64(name + " saiu da sala.")));
    }

    private void broadcast(String room, String record) {
        Set<ClientSession> members = rooms.get(room);
        if (members != null) {
            members.forEach(member -> member.send(record));
        }
    }

    private static final class ClientSession implements AutoCloseable {
        private final Socket socket;
        private final BufferedWriter output;
        private volatile String room;
        private volatile String name;

        private ClientSession(Socket socket) {
            try {
                this.socket = socket;
                this.output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException("Nao foi possivel abrir conexao", exception);
            }
        }

        private synchronized void send(String record) {
            try {
                output.write(record);
                output.newLine();
                output.flush();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
