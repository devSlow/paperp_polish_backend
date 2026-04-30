package com.paper.polish.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
public class LibreOfficeUtil {

    private static final String CONTAINER_NAME = "libreoffice";
    private static final String HOST_SHARE_DIR = "/tmp/lo-convert";
    private static final String CONTAINER_DATA_DIR = "/data";

    public byte[] convertToPdf(byte[] docxData, String docxFileName) {
        Path hostDir = null;
        try {
            hostDir = Path.of(HOST_SHARE_DIR);
            Files.createDirectories(hostDir);

            String uid = String.valueOf(System.currentTimeMillis());
            String inputName = uid + "_" + docxFileName;
            String pdfName = uid + "_" + docxFileName.replaceAll("\\.(?i)docx$", ".pdf");

            Path hostInput = hostDir.resolve(inputName);
            Files.write(hostInput, docxData);

            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec", CONTAINER_NAME,
                    "soffice", "--headless", "--convert-to", "pdf",
                    "--outdir", CONTAINER_DATA_DIR,
                    CONTAINER_DATA_DIR + "/" + inputName
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            log.info("LibreOffice output: {}", output);

            Files.deleteIfExists(hostInput);

            if (exitCode != 0) {
                throw new RuntimeException("LibreOffice 转换失败, exit code: " + exitCode + ", output: " + output);
            }

            Path hostPdf = hostDir.resolve(pdfName);
            if (!Files.exists(hostPdf)) {
                throw new RuntimeException("PDF 文件未生成, expected: " + hostPdf);
            }

            byte[] pdfData = Files.readAllBytes(hostPdf);
            Files.deleteIfExists(hostPdf);
            return pdfData;

        } catch (Exception e) {
            throw new RuntimeException("PDF 转换失败: " + e.getMessage(), e);
        }
    }
}
