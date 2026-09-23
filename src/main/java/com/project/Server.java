package com.project;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Server {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    private final Map<String, Set<ClientSession>> rooms = new ConcurrentHashMap<>();
    private final Map<String, SharedFile> sharedFiles = new ConcurrentHashMap<>();
    private final Map<String, Set<ClientSession>> pendingFileRequests = new ConcurrentHashMap<>();
    private final ExecutorService clients = Executors.newCachedThreadPool();

    public static void main(String[] args) throws IOException {
        new Server().start(5001);
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
        try (session; BufferedReader input = new BufferedReader(
                new InputStreamReader(session.socket.getInputStream(), StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = input.readLine()) != null) {
                try {
                    process(session, Protocol.decode(line));
                } catch (Protocol.ProtocolException | IllegalArgumentException exception) {
                    session.send(Protocol.encode(Protocol.ERROR, exception.getMessage()));
                }
            }
        } catch (IOException ignored) {
        } finally {
            leaveRoom(session);
        }
    }

    private void process(ClientSession session, Protocol.Packet packet)
            throws Protocol.ProtocolException, IOException {
        System.out.println(packet);
        switch (packet.command()) {
            case Protocol.JOIN -> join(session, packet.fields());
            case Protocol.MESSAGE -> message(session, packet.fields());
            case Protocol.FILE -> file(session, packet.fields());
            case Protocol.FILE_REQUEST -> fileRequest(session, packet.fields());
            case Protocol.FILE_REDELIVER -> fileRedeliver(session, packet.fields());
            case Protocol.FILE_UNAVAILABLE -> fileUnavailable(session, packet.fields());
            case Protocol.QUIT -> session.socket.close();
            default -> throw new Protocol.ProtocolException("Comando desconhecido: " + packet.command());
        }
    }

    private void join(ClientSession session, List<String> fields) throws Protocol.ProtocolException {
        requireFields(fields, 2, Protocol.JOIN);
        String room = requireName(fields.get(0), "Sala");
        String name = requireName(fields.get(1), "Nome");
        leaveRoom(session);
        session.room = room;
        session.name = name;
        rooms.computeIfAbsent(room, s -> ConcurrentHashMap.newKeySet()).add(session);
        broadcast(room, Protocol.encode(Protocol.SYSTEM, name + " entrou na sala."));
    }

    private void message(ClientSession session, List<String> fields) throws Protocol.ProtocolException {
        requireJoined(session);
        requireFields(fields, 1, Protocol.MESSAGE);
        String text = fields.get(0);
        if (text.length() > 8_000) {
            throw new Protocol.ProtocolException("Mensagem excede 8000 caracteres");
        }
        broadcast(session.room, Protocol.encode(Protocol.MESSAGE, session.name, text));
    }

    private void file(ClientSession session, List<String> fields) throws Protocol.ProtocolException {
        requireJoined(session);
        FileData file = readFile(fields, Protocol.FILE);
        SharedFile sharedFile = new SharedFile(session.room, session, file.filename);
        if (sharedFiles.putIfAbsent(file.id, sharedFile) != null) {
            throw new Protocol.ProtocolException("Identificador de arquivo ja existe");
        }
        broadcast(session.room, Protocol.encode(Protocol.FILE,
                file.id, session.name, file.filename,
                Base64.getEncoder().encodeToString(file.content)));
    }

    private void fileRequest(ClientSession requester, List<String> fields) throws Protocol.ProtocolException {
        requireJoined(requester);
        requireFields(fields, 1, Protocol.FILE_REQUEST);
        String fileId = fileId(fields.get(0));
        SharedFile sharedFile = sharedFiles.get(fileId);
        if (sharedFile == null || !sharedFile.room.equals(requester.room) || !isInRoom(sharedFile.sender, requester.room)) {
            if (sharedFile != null) {
                sharedFiles.remove(fileId, sharedFile);
            }
            sendUnavailable(requester, fileId, "O remetente nao esta mais disponivel nesta sala.");
            return;
        }
        pendingFileRequests.computeIfAbsent(fileId, ignored -> ConcurrentHashMap.newKeySet()).add(requester);
        sharedFile.sender.send(Protocol.encode(Protocol.FILE_REQUEST, fileId));
    }

    private void fileRedeliver(ClientSession sender, List<String> fields) throws Protocol.ProtocolException {
        requireJoined(sender);
        FileData file = readFile(fields, Protocol.FILE_REDELIVER);
        SharedFile sharedFile = requireOriginalSender(sender, file.id);
        if (!sharedFile.filename.equals(file.filename)) {
            throw new Protocol.ProtocolException("O nome do arquivo reenviado nao corresponde ao original");
        }
        Set<ClientSession> requesters = pendingFileRequests.remove(file.id);
        if (requesters == null) {
            return;
        }
        String record = Protocol.encode(Protocol.FILE_REDELIVER,
                file.id, sender.name, file.filename,
                Base64.getEncoder().encodeToString(file.content));
        requesters.stream()
                .filter(requester -> isInRoom(requester, sender.room))
                .forEach(requester -> requester.send(record));
    }

    private void fileUnavailable(ClientSession sender, List<String> fields) throws Protocol.ProtocolException {
        requireJoined(sender);
        requireFields(fields, 1, Protocol.FILE_UNAVAILABLE);
        String fileId = fileId(fields.get(0));
        requireOriginalSender(sender, fileId);
        Set<ClientSession> requesters = pendingFileRequests.remove(fileId);
        if (requesters != null) {
            requesters.forEach(requester -> sendUnavailable(requester, fileId,
                    "O remetente nao possui mais o arquivo original."));
        }
    }

    private SharedFile requireOriginalSender(ClientSession sender, String fileId)
            throws Protocol.ProtocolException {
        SharedFile sharedFile = sharedFiles.get(fileId);
        if (sharedFile == null || sharedFile.sender != sender || !sharedFile.room.equals(sender.room)) {
            throw new Protocol.ProtocolException("Somente o remetente original pode reenviar este arquivo");
        }
        return sharedFile;
    }

    private FileData readFile(List<String> fields, String command) throws Protocol.ProtocolException {
        requireFields(fields, 3, command);
        String filename = fields.get(1);
        if (filename.isBlank() || filename.length() > 120 || filename.contains("/") || filename.contains("\\")) {
            throw new Protocol.ProtocolException("Nome de arquivo invalido");
        }
        byte[] content = Protocol.unbase64Bytes(fields.get(2));
        if (content.length > MAX_FILE_BYTES) {
            throw new Protocol.ProtocolException("Arquivo excede 10 MB");
        }
        return new FileData(fileId(fields.get(0)), filename, content);
    }

    private String fileId(String value) throws Protocol.ProtocolException {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new Protocol.ProtocolException("Identificador de arquivo invalido");
        }
    }

    private void requireFields(List<String> fields, int expected, String command)
            throws Protocol.ProtocolException {
        if (fields.size() != expected) {
            throw new Protocol.ProtocolException(command + " recebeu campos invalidos");
        }
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

    private boolean isInRoom(ClientSession session, String room) {
        Set<ClientSession> members = rooms.get(room);
        return members != null && members.contains(session) && room.equals(session.room) && !session.socket.isClosed();
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
        sharedFiles.entrySet().removeIf(entry -> entry.getValue().sender == session);
        pendingFileRequests.forEach((fileId, requesters) -> {
            requesters.remove(session);
            if (requesters.isEmpty()) {
                pendingFileRequests.remove(fileId, requesters);
            }
        });
        broadcast(room, Protocol.encode(Protocol.SYSTEM, name + " saiu da sala."));
    }

    private void sendUnavailable(ClientSession requester, String fileId, String reason) {
        requester.send(Protocol.encode(Protocol.FILE_UNAVAILABLE, fileId, reason));
    }

    private void broadcast(String room, String record) {
        Set<ClientSession> members = rooms.get(room);
        System.out.println("Broadcast -> record: " + record);
        if (members != null && !members.isEmpty()) {
            members.forEach(member -> member.send(record));
        }
    }

    private record SharedFile(String room, ClientSession sender, String filename) {
    }

    private record FileData(String id, String filename, byte[] content) {
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
