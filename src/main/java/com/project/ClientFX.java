package com.project;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientFX extends Application {

    private static final int DEFAULT_PORT = 5001;
    private static final double CHAT_IMAGE_WIDTH = 360;

    private final TextField hostField = new TextField("localhost");
    private final TextField portField = new TextField(String.valueOf(DEFAULT_PORT));
    private final TextField nameField = new TextField();
    private final TextField roomField = new TextField("geral");

    private final Button connectButton = new Button("Conectar");
    private final Button disconnectButton = new Button("Desconectar");
    private final Button changeRoomButton = new Button("Trocar sala");

    private final TextArea messageField = new TextArea();
    private final Button sendButton = new Button("Enviar");
    private final Button fileButton = new Button("Arquivo");

    private final VBox chatBox = new VBox(10);
    private final ScrollPane chatScroll = new ScrollPane(chatBox);

    private final VBox previewBox = new VBox(8);
    private final Label previewName = new Label();
    private final ImageView previewImage = new ImageView();
    private final Button sendPreviewButton = new Button("Enviar imagem");
    private final Button cancelPreviewButton = new Button("Cancelar");

    private final Label statusLabel = new Label("Desconectado");

    private Socket socket;
    private BufferedWriter output;
    private Thread receiverThread;

    private volatile String currentName = "";
    private volatile Path downloadDirectory;

    private volatile Path pendingImage;
    private final Map<String, SentFile> sentFiles = new ConcurrentHashMap<>();
    private final Map<String, DownloadedFile> downloadedFiles = new ConcurrentHashMap<>();

    @Override
    public void start(Stage stage) {
        stage.setTitle("Chat Muito Bala by Pedro & Gunther");
        stage.setMinWidth(1100);
        stage.setMinHeight(650);

        configureControls();

        chatScroll.setFitToWidth(true);
        chatScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        chatBox.setFillWidth(true);
        chatBox.setPadding(new Insets(10));

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setTop(buildConnectionBar());

        VBox center = new VBox(
                10,
                buildChatTitle(),
                chatScroll,
                buildPreviewBox(),
                buildComposer()
        );
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        root.setCenter(center);
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1000, 720);
        stage.setScene(scene);

        stage.setOnCloseRequest(event -> disconnect());

        chatBox.heightProperty().addListener((obs, oldValue, newValue) ->
                Platform.runLater(() -> chatScroll.setVvalue(1.0))
        );

        stage.show();
    }

    private void configureControls() {
        portField.setPrefWidth(90);
        messageField.setPromptText("Digite uma mensagem...");
        messageField.setPrefRowCount(2);
        messageField.setWrapText(true);

        disconnectButton.setDisable(true);
        changeRoomButton.setDisable(true);
        sendButton.setDisable(true);
        fileButton.setDisable(true);

        sendPreviewButton.setDisable(true);
        previewBox.setVisible(false);
        previewBox.setManaged(false);

        connectButton.setOnAction(event -> connect());
        disconnectButton.setOnAction(event -> disconnect());
        changeRoomButton.setOnAction(event -> changeRoom());
        sendButton.setOnAction(event -> sendMessage());
        fileButton.setOnAction(event -> chooseFile());
        sendPreviewButton.setOnAction(event -> sendPendingImage());
        cancelPreviewButton.setOnAction(event -> clearPreview());

        messageField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                sendMessage();
            }
        });
    }

    private HBox buildConnectionBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);

        bar.getChildren().addAll(
                new Label("Host:"), hostField,
                new Label("Porta:"), portField,
                new Label("Nome:"), nameField,
                new Label("Sala:"), roomField,
                connectButton, disconnectButton, changeRoomButton
        );

        HBox.setHgrow(hostField, Priority.ALWAYS);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        HBox.setHgrow(roomField, Priority.ALWAYS);
        return bar;
    }

    private Label buildChatTitle() {
        Label title = new Label("Conversa");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        return title;
    }

    private VBox buildPreviewBox() {
        previewBox.setPadding(new Insets(10));
        previewBox.setStyle(
                "-fx-border-color: #c8c8c8;" +
                        "-fx-border-radius: 6;" +
                        "-fx-background-color: #f7f7f7;" +
                        "-fx-background-radius: 6;"
        );

        Label title = new Label("Pré-visualização da imagem");
        title.setStyle("-fx-font-weight: bold;");

        previewImage.setFitWidth(280);
        previewImage.setFitHeight(180);
        previewImage.setPreserveRatio(true);
        previewImage.setSmooth(true);

        HBox buttons = new HBox(8, sendPreviewButton, cancelPreviewButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        previewBox.getChildren().setAll(title, previewName, previewImage, buttons);
        return previewBox;
    }

    private HBox buildComposer() {
        HBox composer = new HBox(8);
        composer.setAlignment(Pos.BOTTOM_CENTER);
        HBox.setHgrow(messageField, Priority.ALWAYS);
        composer.getChildren().addAll(messageField, sendButton, fileButton);
        return composer;
    }

    private HBox buildStatusBar() {
        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(8, 0, 0, 0));
        return statusBar;
    }

    private void connect() {
        if (isConnected()) {
            return;
        }

        String host = hostField.getText().trim();
        String name = nameField.getText().trim();
        String room = roomField.getText().trim();

        if (host.isBlank()) {
            showError("Informe o host.");
            return;
        }
        if (name.isBlank()) {
            showError("Informe seu nome.");
            return;
        }
        if (room.isBlank()) {
            showError("Informe a sala.");
            return;
        }

        final int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException exception) {
            showError("A porta precisa ser um número.");
            return;
        }

        if (port < 1 || port > 65535) {
            showError("A porta precisa estar entre 1 e 65535.");
            return;
        }

        connectButton.setDisable(true);
        setStatus("Conectando...");

        Thread.ofVirtual().start(() -> {
            try {
                Socket newSocket = new Socket(host, port);
                BufferedWriter newOutput = new BufferedWriter(
                        new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8)
                );

                Path directory = createDownloadDirectory(name);

                socket = newSocket;
                output = newOutput;
                currentName = name;
                downloadDirectory = directory;

                send(newOutput, Protocol.encode(Protocol.JOIN, room, name));

                receiverThread = Thread.ofVirtual().start(() -> receive(newSocket));

                Platform.runLater(() -> {
                    statusLabel.setText("Conectado a " + host + ":" + port + " | sala: " + room);
                    disconnectButton.setDisable(false);
                    changeRoomButton.setDisable(false);
                    sendButton.setDisable(false);
                    fileButton.setDisable(false);
                    hostField.setDisable(true);
                    portField.setDisable(true);
                    nameField.setDisable(true);
                    roomField.setDisable(false);

                    addSystemMessage("Conectado. Seus arquivos recebidos serão salvos em:\n"
                            + directory.toAbsolutePath());
                });
            } catch (IOException exception) {
                Platform.runLater(() -> {
                    connectButton.setDisable(false);
                    setStatus("Erro ao conectar.");
                    showError("Não foi possível conectar:\n" + exception.getMessage());
                });
            }
        });
    }

    private void changeRoom() {
        if (!isConnected()) {
            showError("Você não está conectado.");
            return;
        }

        String newRoom = roomField.getText().trim();
        if (newRoom.isBlank()) {
            showError("Informe a nova sala.");
            return;
        }

        try {
            send(Protocol.encode(Protocol.JOIN, newRoom, currentName));
            sentFiles.clear();
            setStatus("Conectado | sala: " + newRoom);
        } catch (IOException exception) {
            showError("Falha ao trocar de sala:\n" + exception.getMessage());
        }
    }

    private void sendMessage() {
        if (!isConnected()) {
            showError("Você não está conectado.");
            return;
        }

        String text = messageField.getText().trim();
        if (text.isBlank()) {
            return;
        }
        if (text.length() > 8000) {
            showError("A mensagem não pode ultrapassar 8000 caracteres.");
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                send(Protocol.encode(Protocol.MESSAGE, text));
                Platform.runLater(messageField::clear);
            } catch (IOException exception) {
                handleConnectionError(exception);
            }
        });
    }

    private void chooseFile() {
        if (!isConnected()) {
            showError("Conecte-se antes de enviar um arquivo.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Selecionar arquivo");

        java.io.File selected = chooser.showOpenDialog(fileButton.getScene().getWindow());
        if (selected == null) {
            return;
        }

        Path path = selected.toPath();
        if (isImage(path)) {
            loadImagePreview(path);
        } else {
            sendFileInBackground(path);
        }
    }

    private void loadImagePreview(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                showError("Arquivo não encontrado.");
                return;
            }

            long size = Files.size(path);
            Image image;
            try (var input = Files.newInputStream(path)) {
                image = new Image(input);
            }

            if (image.isError()) {
                showError("Não foi possível abrir essa imagem.");
                return;
            }

            pendingImage = path;
            previewImage.setImage(image);
            previewName.setText(path.getFileName() + " (" + formatBytes(size) + ")");
            previewBox.setVisible(true);
            previewBox.setManaged(true);
            sendPreviewButton.setDisable(false);
        } catch (IOException exception) {
            showError("Falha ao preparar a imagem:\n" + exception.getMessage());
        }
    }

    private void sendPendingImage() {
        Path image = pendingImage;
        if (image == null) {
            return;
        }

        clearPreview();
        sendFileInBackground(image);
    }

    private void clearPreview() {
        pendingImage = null;
        previewImage.setImage(null);
        previewName.setText("");
        sendPreviewButton.setDisable(true);
        previewBox.setVisible(false);
        previewBox.setManaged(false);
    }

    private void sendFileInBackground(Path path) {
        Thread.ofVirtual().start(() -> {
            try {
                sendFile(path);
            } catch (IOException exception) {
                handleConnectionError(exception);
            }
        });
    }

    private void sendFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            Platform.runLater(() -> showError("Arquivo não encontrado: " + path));
            return;
        }

        byte[] bytes = Files.readAllBytes(path);
        String fileId = UUID.randomUUID().toString();
        String filename = path.getFileName().toString();
        sentFiles.put(fileId, new SentFile(filename, path.toAbsolutePath()));
        try {
            send(Protocol.encode(
                    Protocol.FILE,
                    fileId,
                    filename,
                    Base64.getEncoder().encodeToString(bytes)
            ));
        } catch (IOException exception) {
            sentFiles.remove(fileId);
            throw exception;
        }
    }

    private void receive(Socket receivingSocket) {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                receivingSocket.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = input.readLine()) != null) {
                try {
                    Protocol.Packet packet = Protocol.decode(line);
                    processPacket(packet);
                } catch (Protocol.ProtocolException exception) {
                    Platform.runLater(() -> addErrorMessage(
                            "Pacote inválido: " + exception.getMessage()
                    ));
                }
            }
        } catch (IOException exception) {
            if (!receivingSocket.isClosed()) {
                Platform.runLater(() -> {
                    addErrorMessage("Conexão encerrada: " + exception.getMessage());
                    setDisconnectedState();
                });
            }
        }
    }

    private void processPacket(Protocol.Packet packet) {
        try {
            switch (packet.command()) {
                case Protocol.SYSTEM -> {
                    String text = packet.fields().get(0);
                    Platform.runLater(() -> addSystemMessage(text));
                }
                case Protocol.ERROR -> {
                    String text = packet.fields().get(0);
                    Platform.runLater(() -> addErrorMessage(text));
                }
                case Protocol.MESSAGE -> {
                    String sender = packet.fields().get(0);
                    String text = packet.fields().get(1);
                    Platform.runLater(() -> addTextMessage(sender, text));
                }
                case Protocol.FILE -> processReceivedFile(packet, false);
                case Protocol.FILE_REDELIVER -> processReceivedFile(packet, true);
                case Protocol.FILE_REQUEST -> respondToFileRequest(packet);
                case Protocol.FILE_UNAVAILABLE -> processFileUnavailable(packet);
                default -> Platform.runLater(() -> addSystemMessage(
                        "Pacote ignorado: " + packet.command()
                ));
            }
        } catch (Protocol.ProtocolException | IOException | RuntimeException exception) {
            Platform.runLater(() -> addErrorMessage(
                    "Falha ao processar pacote: " + exception.getMessage()
            ));
        }
    }

    private void processReceivedFile(Protocol.Packet packet, boolean redelivery)
            throws Protocol.ProtocolException, IOException {

        String fileId = packet.fields().get(0);
        String sender = packet.fields().get(1);
        String filename = packet.fields().get(2);
        byte[] content = Protocol.unbase64Bytes(packet.fields().get(3));

        Path directory = downloadDirectory;
        if (directory == null) {
            directory = createDownloadDirectory(
                    currentName.isBlank() ? "usuario" : currentName
            );
            downloadDirectory = directory;
        }

        Path destination = directory.resolve(
                Instant.now().toEpochMilli() + "-" + filename
        );

        Files.write(destination, content, StandardOpenOption.CREATE_NEW);

        Image image = null;
        if (isImageName(filename)) {
            Image candidate = new Image(new ByteArrayInputStream(content));
            if (!candidate.isError()) {
                image = candidate;
            }
        }
        Path finalDestination = destination;
        Image finalImage = image;
        Platform.runLater(() -> updateReceivedFile(
                fileId, sender, filename, finalImage, finalDestination, redelivery
        ));
    }

    private void respondToFileRequest(Protocol.Packet packet) {
        String fileId = packet.fields().get(0);
        Thread.ofVirtual().start(() -> resendFile(fileId));
    }

    private void resendFile(String fileId) {
        SentFile sentFile = sentFiles.get(fileId);
        if (sentFile == null || !Files.isRegularFile(sentFile.path)) {
            sendUnavailable(fileId);
            return;
        }
        try {
            byte[] content = Files.readAllBytes(sentFile.path);
            send(Protocol.encode(Protocol.FILE_REDELIVER, fileId,
                    sentFile.filename, Base64.getEncoder().encodeToString(content)));
        } catch (IOException exception) {
            sendUnavailable(fileId);
        }
    }

    private void sendUnavailable(String fileId) {
        try {
            send(Protocol.encode(Protocol.FILE_UNAVAILABLE, fileId));
        } catch (IOException exception) {
            handleConnectionError(exception);
        }
    }

    private void processFileUnavailable(Protocol.Packet packet) throws Protocol.ProtocolException {
        String fileId = packet.fields().get(0);
        String reason = packet.fields().get(1);
        Platform.runLater(() -> {
            DownloadedFile file = downloadedFiles.get(fileId);
            if (file != null) {
                file.markUnavailable(reason);
            }
            addErrorMessage("Arquivo indisponível: " + reason);
        });
    }

    private void updateReceivedFile(String fileId, String sender, String filename, Image image,
                                    Path destination, boolean redelivery) {
        DownloadedFile existing = downloadedFiles.get(fileId);
        if (redelivery && existing != null) {
            existing.markReceived(destination);
            return;
        }
        addAttachment(fileId, sender, filename, image, destination);
    }

    private void addTextMessage(String sender, String text) {
        Label senderLabel = new Label(sender);
        senderLabel.setStyle("-fx-font-weight: bold;");

        Label textLabel = new Label(text);
        textLabel.setWrapText(true);

        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        int minute = now.getMinute();
        Label dateTime = new Label(hour + ":" + minute);

        dateTime.setMaxWidth(Double.MAX_VALUE);
        dateTime.setAlignment(Pos.CENTER_RIGHT);

        HBox.setHgrow(dateTime, Priority.ALWAYS);

        VBox bubble = new VBox(4, senderLabel, textLabel, dateTime);
        bubble.setPadding(new Insets(9));
        bubble.setMaxWidth(560);
        bubble.setStyle("-fx-background-color: #e9eef7; -fx-background-radius: 10;");

        HBox row = new HBox(bubble);
        row.setPadding(new Insets(0, 10, 0, 10));
        row.setAlignment(Pos.CENTER_LEFT);

        if (sender.equals(currentName)) {
            row.setAlignment(Pos.CENTER_RIGHT);
            bubble.setStyle("-fx-background-color: #d7f5d1; -fx-background-radius: 10;");
        }

        chatBox.getChildren().add(row);
    }

    private void addAttachment(String fileId, String sender, String filename, Image image, Path destination) {
        DownloadedFile downloadedFile = new DownloadedFile(fileId, destination);
        Label senderLabel = new Label(sender);
        senderLabel.setStyle("-fx-font-weight: bold;");

        VBox bubble = new VBox(6, senderLabel);
        bubble.setPadding(new Insets(9));
        bubble.setStyle("-fx-background-color: #eeeeee; -fx-background-radius: 10;");
        bubble.setMaxWidth(500);

        if (image != null) {
            ImageView imageView = new ImageView(image);
            imageView.setFitWidth(CHAT_IMAGE_WIDTH);
            imageView.setFitHeight(300);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);
            imageView.setStyle("-fx-cursor: hand;");
            imageView.setOnMouseClicked(event -> openImageViewer(sender, filename, image));
            bubble.getChildren().addAll(imageView, new Label(filename), new Label("Clique na imagem para ampliar"));
            bubble.setMaxWidth(CHAT_IMAGE_WIDTH + 30);
            bubble.setStyle("-fx-background-color: #f0f0f0; -fx-background-radius: 10;");
        } else {
            Label fileLabel = new Label("📎 " + filename);
            fileLabel.setWrapText(true);
            bubble.getChildren().add(fileLabel);
        }

        Button openButton = new Button(image == null ? "Abrir" : "Abrir arquivo");
        openButton.setOnAction(event -> openFile(downloadedFile.destination));
        Button redownloadButton = new Button("Baixar novamente");
        redownloadButton.setOnAction(event -> requestRedelivery(downloadedFile));
        Label downloadStatus = new Label("Salvo em " + destination.getFileName());
        downloadStatus.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        downloadedFile.attach(openButton, redownloadButton, downloadStatus);

        bubble.getChildren().addAll(openButton, redownloadButton, downloadStatus);

        HBox row = new HBox(bubble);
        row.setPadding(new Insets(0, 10, 0, 10));
        row.setAlignment(sender.equals(currentName) ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        chatBox.getChildren().add(row);
        downloadedFiles.put(fileId, downloadedFile);
    }

    private void requestRedelivery(DownloadedFile downloadedFile) {
        if (!isConnected()) {
            showError("Conecte-se para solicitar uma nova cópia do arquivo.");
            return;
        }
        if (Files.exists(downloadedFile.destination)) {
            showError("Arquivo ja existe");
            return;
        }
        downloadedFile.markRequested();
        Thread.ofVirtual().start(() -> {
            try {
                send(Protocol.encode(Protocol.FILE_REQUEST, downloadedFile.fileId));
            } catch (IOException exception) {
                handleConnectionError(exception);
            }
        });
    }

    private void addSystemMessage(String text) {
        Label label = new Label("[sistema] " + text);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #666666; -fx-font-style: italic;");

        HBox row = new HBox(label);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(4, 10, 4, 10));
        chatBox.getChildren().add(row);
    }

    private void addErrorMessage(String text) {
        Label label = new Label("[erro] " + text);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #b00020;");

        HBox row = new HBox(label);
        row.setAlignment(Pos.CENTER);
        row.setPadding(new Insets(4, 10, 4, 10));
        chatBox.getChildren().add(row);
    }

    private void openImageViewer(String sender, String filename, Image image) {
        Stage viewer = new Stage();
        viewer.setTitle(sender + " - " + filename);
        viewer.initModality(Modality.APPLICATION_MODAL);

        ImageView largeView = new ImageView(image);
        largeView.setPreserveRatio(true);
        largeView.setSmooth(true);

        ScrollPane scrollPane = new ScrollPane(largeView);
        scrollPane.setPannable(true);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);

        Button closeButton = new Button("Fechar");
        closeButton.setOnAction(event -> viewer.close());

        BorderPane root = new BorderPane();
        root.setCenter(scrollPane);
        root.setBottom(new StackPane(closeButton));
        BorderPane.setMargin(closeButton, new Insets(8));

        Scene scene = new Scene(root, 900, 700);
        viewer.setScene(scene);

        scene.widthProperty().addListener((obs, oldValue, newValue) ->
                resizeLargeImage(largeView, scene));
        scene.heightProperty().addListener((obs, oldValue, newValue) ->
                resizeLargeImage(largeView, scene));

        viewer.showAndWait();
    }

    private void resizeLargeImage(ImageView imageView, Scene scene) {
        imageView.setFitWidth(Math.max(200, scene.getWidth() - 40));
        imageView.setFitHeight(Math.max(200, scene.getHeight() - 80));
    }

    private void openFile(Path destination) {
        try {
            if (!Files.isRegularFile(destination)) {
                showError("O arquivo foi removido. Use o botão 'Baixar novamente'.");
                return;
            }
            if (!Desktop.isDesktopSupported()) {
                showError("O sistema não oferece suporte para abrir arquivos automaticamente.");
                return;
            }
            java.awt.Desktop.getDesktop().open(destination.toFile());
        } catch (Exception exception) {
            showError("Não foi possível abrir o arquivo:\n" + exception.getMessage());
        }
    }

    private Path createDownloadDirectory(String name) throws IOException {
        String folderName = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (folderName.isBlank()) {
            folderName = "usuario";
        }

        Path directory = Path.of("downloads", folderName);
        Files.createDirectories(directory);
        return directory;
    }

    private boolean isImage(Path path) {
        return isImageName(path.getFileName().toString());
    }

    private boolean isImageName(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".gif")
                || lower.endsWith(".bmp");
    }

    private boolean isConnected() {
        return socket != null
                && socket.isConnected()
                && !socket.isClosed()
                && output != null;
    }

    private void send(String record) throws IOException {
        BufferedWriter currentOutput = output;
        if (currentOutput == null) {
            throw new IOException("Cliente não está conectado.");
        }
        send(currentOutput, record);
    }

    private static synchronized void send(BufferedWriter writer, String record)
            throws IOException {
        writer.write(record);
        writer.newLine();
        writer.flush();
    }

    private void disconnect() {
        Socket currentSocket = socket;

        if (currentSocket == null) {
            setDisconnectedState();
            return;
        }

        try {
            if (!currentSocket.isClosed() && output != null) {
                send(Protocol.encode(Protocol.QUIT));
            }
        } catch (IOException ignored) {
        }

        try {
            currentSocket.close();
        } catch (IOException ignored) {
        }

        if (receiverThread != null) {
            receiverThread.interrupt();
        }

        setDisconnectedState();
    }

    private void setDisconnectedState() {
        socket = null;
        output = null;
        receiverThread = null;
        sentFiles.clear();

        Platform.runLater(() -> {
            connectButton.setDisable(false);
            disconnectButton.setDisable(true);
            changeRoomButton.setDisable(true);
            sendButton.setDisable(true);
            fileButton.setDisable(true);
            hostField.setDisable(false);
            portField.setDisable(false);
            nameField.setDisable(false);
            setStatus("Desconectado");
        });
    }

    private void handleConnectionError(Exception exception) {
        Platform.runLater(() -> {
            addErrorMessage("Falha de comunicação: " + exception.getMessage());
            disconnect();
        });
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Erro");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private record SentFile(String filename, Path path) {
    }

    private static final class DownloadedFile {
        private final String fileId;
        private volatile Path destination;
        private Button openButton;
        private Button redownloadButton;
        private Label statusLabel;

        private DownloadedFile(String fileId, Path destination) {
            this.fileId = fileId;
            this.destination = destination;
        }

        private void attach(Button openButton, Button redownloadButton, Label statusLabel) {
            this.openButton = openButton;
            this.redownloadButton = redownloadButton;
            this.statusLabel = statusLabel;
        }

        private void markRequested() {
            redownloadButton.setDisable(true);
            statusLabel.setText("Solicitando nova cópia...");
        }

        private void markReceived(Path newDestination) {
            destination = newDestination;
            openButton.setDisable(false);
            redownloadButton.setDisable(false);
            statusLabel.setText("Baixado novamente em " + newDestination.getFileName());
        }

        private void markUnavailable(String reason) {
            openButton.setDisable(!Files.isRegularFile(destination));
            redownloadButton.setDisable(false);
            statusLabel.setText("Indisponível: " + reason);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
