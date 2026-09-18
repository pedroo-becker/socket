package com.project;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.Scanner;

/** Console client. Commands: /join SALA, /file CAMINHO and /quit. */
public final class Client {
    private static final int DEFAULT_PORT = 5000;

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        try (Scanner console = new Scanner(System.in, StandardCharsets.UTF_8);
             Socket socket = new Socket(host, port);
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            System.out.print("Seu nome: ");
            String name = console.nextLine().trim();
            System.out.print("Sala: ");
            String room = console.nextLine().trim();
            send(output, Protocol.encode(Protocol.JOIN, room, name));

            Path downloadDirectory = createDownloadDirectory(name);
            Thread receiver = Thread.ofVirtual().start(() -> receive(socket, downloadDirectory));
            System.out.println("Conectado. Seus downloads serao salvos em "
                    + downloadDirectory.toAbsolutePath());
            System.out.println("Digite texto, /join SALA, /file CAMINHO ou /quit.");
            while (console.hasNextLine()) {
                String input = console.nextLine();
                if (input.equals("/quit")) {
                    send(output, Protocol.encode(Protocol.QUIT));
                    break;
                }
                if (input.startsWith("/join ")) {
                    room = input.substring(6).trim();
                    send(output, Protocol.encode(Protocol.JOIN, room, name));
                } else if (input.startsWith("/file ")) {
                    sendFile(output, Path.of(input.substring(6).trim()));
                } else if (!input.isBlank()) {
                    send(output, Protocol.encode(Protocol.MESSAGE, Protocol.base64(input)));
                }
            }
            receiver.interrupt();
        }
    }

    private static void sendFile(BufferedWriter output, Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                System.out.println("Arquivo nao encontrado: " + path);
                return;
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > 10 * 1024 * 1024) {
                System.out.println("O arquivo excede 10 MB.");
                return;
            }
            send(output, Protocol.encode(Protocol.FILE,
                    Protocol.base64(path.getFileName().toString()), Base64.getEncoder().encodeToString(bytes)));
        } catch (IOException exception) {
            System.out.println("Falha ao ler arquivo: " + exception.getMessage());
        }
    }

    private static void receive(Socket socket, Path downloadDirectory) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                display(Protocol.decode(line), downloadDirectory);
            }
        } catch (IOException | Protocol.ProtocolException exception) {
            if (!socket.isClosed()) {
                System.out.println("Conexao encerrada: " + exception.getMessage());
            }
        }
    }

    private static void display(Protocol.Packet packet, Path downloadDirectory)
            throws Protocol.ProtocolException, IOException {
        switch (packet.command()) {
            case Protocol.SYSTEM -> System.out.println("[sistema] " + Protocol.unbase64Text(packet.fields().get(0)));
            case Protocol.ERROR -> System.out.println("[erro] " + Protocol.unbase64Text(packet.fields().get(0)));
            case Protocol.MESSAGE -> System.out.println("[" + Protocol.unbase64Text(packet.fields().get(0)) + "] "
                    + Protocol.unbase64Text(packet.fields().get(1)));
            case Protocol.FILE -> saveFile(packet, downloadDirectory);
            default -> System.out.println("[pacote ignorado] " + packet.command());
        }
    }

    private static void saveFile(Protocol.Packet packet, Path downloadDirectory)
            throws Protocol.ProtocolException, IOException {
        String sender = Protocol.unbase64Text(packet.fields().get(0));
        String filename = Protocol.unbase64Text(packet.fields().get(1));
        if (filename.isBlank() || filename.contains("/") || filename.contains("\\")) {
            throw new Protocol.ProtocolException("Nome de arquivo recebido invalido");
        }
        byte[] content = Protocol.unbase64Bytes(packet.fields().get(2));
        Path destination = downloadDirectory.resolve(Instant.now().toEpochMilli() + "-" + filename);
        Files.write(destination, content, StandardOpenOption.CREATE_NEW);
        System.out.println("[arquivo] " + sender + " compartilhou " + filename + " -> " + destination.toAbsolutePath());
    }

    private static Path createDownloadDirectory(String name) throws IOException {
        String folderName = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (folderName.isBlank()) {
            folderName = "usuario";
        }
        Path directory = Path.of("downloads", folderName);
        Files.createDirectories(directory);
        return directory;
    }

    private static synchronized void send(BufferedWriter output, String record) throws IOException {
        output.write(record);
        output.newLine();
        output.flush();
    }
}
