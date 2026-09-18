package com.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class Protocol {
    public static final String JOIN = "JOIN";
    public static final String MESSAGE = "MESSAGE";
    public static final String FILE = "FILE";
    public static final String QUIT = "QUIT";
    public static final String SYSTEM = "SYSTEM";
    public static final String ERROR = "ERROR";

    private Protocol() {
    }

    public static String encode(String command, String... fields) {
        StringBuilder record = new StringBuilder(csv(command));
        for (String field : fields) {
            record.append(',').append(csv(field));
        }
        return record.toString();
    }

    public static Packet decode(String record) throws ProtocolException {
        if (record == null || record.indexOf('\n') >= 0 || record.indexOf('\r') >= 0) {
            throw new ProtocolException("Registro CSV invalido");
        }
        List<String> values = parseCsv(record);
        if (values.isEmpty() || values.get(0).isBlank()) {
            throw new ProtocolException("Comando ausente");
        }
        return new Packet(values.get(0), values.subList(1, values.size()));
    }

    public static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String unbase64Text(String value) throws ProtocolException {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Campo Base64 invalido");
        }
    }

    public static byte[] unbase64Bytes(String value) throws ProtocolException {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new ProtocolException("Campo Base64 invalido");
        }
    }

    private static String csv(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Campos CSV nao podem ser nulos");
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    private static List<String> parseCsv(String record) throws ProtocolException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldClosed = false;

        for (int index = 0; index < record.length(); index++) {
            char current = record.charAt(index);
            if (quoted) {
                if (current == '"') {
                    if (index + 1 < record.length() && record.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                        fieldClosed = true;
                    }
                } else {
                    field.append(current);
                }
            } else if (current == ',') {
                fields.add(field.toString());
                field.setLength(0);
                fieldClosed = false;
            } else if (current == '"') {
                if (field.length() != 0 || fieldClosed) {
                    throw new ProtocolException("Aspas CSV em posicao invalida");
                }
                quoted = true;
            } else if (fieldClosed) {
                throw new ProtocolException("Texto apos aspas CSV fechadas");
            } else {
                field.append(current);
            }
        }
        if (quoted) {
            throw new ProtocolException("Aspas CSV nao finalizadas");
        }
        fields.add(field.toString());
        return fields;
    }

    public record Packet(String command, List<String> fields) {
    }

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message) {
            super(message);
        }
    }
}
