package com.classpulse.backend.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

/**
 * Service providing dynamic QR code image generation and join URL assembly.
 * <p>
 * Uses the ZXing ("Zebra Crossing") barcode processing library to generate high-resolution,
 * crisp PNG QR code images that can be projected on classroom screens for students to scan
 * directly with their smartphones or tablets.
 */
@Service
public class QrCodeService {

    /** Base frontend URL where students join sessions, configured via application properties. */
    private final String frontendJoinUrl;

    /**
     * Constructs the QR code service injecting the frontend join base URL.
     *
     * @param frontendJoinUrl the target client join page base URL (defaults to http://localhost:3000/join)
     */
    public QrCodeService(@Value("${classpulse.frontend.join-url:http://localhost:3000/join}") String frontendJoinUrl) {
        this.frontendJoinUrl = frontendJoinUrl;
    }

    /**
     * Builds the complete, URL-safe web address that student participants navigate to
     * in order to directly join the classroom session with pre-filled room code.
     *
     * @param joinCode the 6-character classroom session join code (e.g. "A3X9K2")
     * @return full join URL with the code appended as a query parameter
     */
    public String buildJoinUrl(String joinCode) {
        // Step 1: Trim any surrounding whitespace from the configured base URL
        String baseUrl = frontendJoinUrl.trim();

        // Step 2: Append code as query parameter, respecting whether the base URL already has parameters
        if (baseUrl.contains("?")) {
            return baseUrl + "&code=" + joinCode;
        } else {
            return baseUrl + "?code=" + joinCode;
        }
    }

    /**
     * Encodes the session join URL into a standard 2D QR code barcode image formatted as PNG bytes.
     *
     * @param joinCode the 6-character session join code
     * @param width    requested image width in pixels (clamped between 50 and 2000 px)
     * @param height   requested image height in pixels (clamped between 50 and 2000 px)
     * @return raw byte array containing the rendered PNG image data
     */
    public byte[] generateQrCodePng(String joinCode, int width, int height) {
        // Step 1: Clamp dimensions to safe bounds to guard against denial-of-service / memory exhaustion
        int safeWidth = Math.min(Math.max(width, 50), 2000);
        int safeHeight = Math.min(Math.max(height, 50), 2000);

        try {
            // Step 2: Assemble target join URL for the given code
            String joinUrl = buildJoinUrl(joinCode);

            // Step 3: Instantiate ZXing QR code writer
            QRCodeWriter qrCodeWriter = new QRCodeWriter();

            // Step 4: Encode the URL string into a 2D boolean bit matrix representing QR black and white modules
            BitMatrix bitMatrix = qrCodeWriter.encode(joinUrl, BarcodeFormat.QR_CODE, safeWidth, safeHeight);

            // Step 5: Render the bit matrix into an in-memory PNG output byte stream
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);

            // Step 6: Return completed byte array
            return outputStream.toByteArray();
        } catch (Exception e) {
            // Step 7: Wrap underlying ZXing or I/O exceptions in a clear runtime exception
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }
}
