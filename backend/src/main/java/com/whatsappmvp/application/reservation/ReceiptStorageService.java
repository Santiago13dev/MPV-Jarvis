package com.whatsappmvp.application.reservation;

import com.whatsappmvp.config.AppProperties;
import com.whatsappmvp.domain.exception.BusinessException;
import com.whatsappmvp.domain.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Almacenamiento de comprobantes de pago en disco (volumen backend_uploads), NO en PostgreSQL.
 * En BD solo se guarda la ruta relativa (reservation_receipts.file_path).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptStorageService {

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "application/pdf", "pdf");

    private final AppProperties props;

    public record Stored(String relativePath, String mimeType) {}

    public Stored store(UUID reservationId, byte[] data, String mimeType) {
        if (data == null || data.length == 0) {
            throw new BusinessException("El archivo está vacío");
        }
        if (data.length > MAX_BYTES) {
            throw new BusinessException("El archivo supera el tamaño máximo de 10 MB");
        }
        String mime = normalizeMime(mimeType);
        String ext = EXTENSIONS.get(mime);
        if (ext == null) {
            throw new BusinessException("Formato no permitido. Envía una imagen (JPG, PNG, WEBP) o un PDF");
        }
        try {
            Path dir = base().resolve("receipts").resolve(reservationId.toString());
            Files.createDirectories(dir);
            String name = UUID.randomUUID() + "." + ext;
            Files.write(dir.resolve(name), data, StandardOpenOption.CREATE_NEW);
            String relative = "receipts/" + reservationId + "/" + name;
            log.info("[RESERVATION] receipt stored reservationId={} file={} bytes={}", reservationId, relative, data.length);
            return new Stored(relative, mime);
        } catch (IOException e) {
            log.error("[RESERVATION] could not store receipt: {}", e.getMessage());
            throw new BusinessException("No se pudo guardar el comprobante");
        }
    }

    public Resource load(String relativePath) {
        Path base = base();
        Path target = base.resolve(relativePath).normalize();
        // Protección contra path traversal
        if (!target.startsWith(base) || !Files.isRegularFile(target)) {
            throw new NotFoundException("Archivo no encontrado");
        }
        return new FileSystemResource(target);
    }

    private Path base() {
        return Paths.get(props.getUploads().getBasePath()).toAbsolutePath().normalize();
    }

    private String normalizeMime(String mime) {
        if (mime == null) return "";
        String m = mime.toLowerCase(Locale.ROOT).trim();
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0, semi).trim();
        return m.equals("image/jpg") ? "image/jpeg" : m;
    }
}
