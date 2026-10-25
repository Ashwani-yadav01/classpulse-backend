package com.classpulse.backend.session;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

@Service
public class QrCodeService {

    private final String frontendJoinUrl;

    public QrCodeService(@Value("${classpulse.frontend.join-url:http://localhost:3000/join}") String frontendJoinUrl) {
        this.frontendJoinUrl = frontendJoinUrl;
    }

    public String buildJoinUrl(String joinCode) {
        String baseUrl = frontendJoinUrl.trim();
        if (baseUrl.contains("?")) {
            return baseUrl + "&code=" + joinCode;
        } else {
            return baseUrl + "?code=" + joinCode;
        }
    }

    public byte[] generateQrCodePng(String joinCode, int width, int height) {
        try {
            String joinUrl = buildJoinUrl(joinCode);
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(joinUrl, BarcodeFormat.QR_CODE, width, height);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate QR code", e);
        }
    }
}
