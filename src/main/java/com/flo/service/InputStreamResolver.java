package com.flo.service;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class InputStreamResolver {

    InputStream resolve(MultipartFile file) throws IOException {

        String name = file.getOriginalFilename() == null
                      ? ""
                      : file.getOriginalFilename()
                            .toLowerCase();
        if (!name.endsWith(".zip")) {
            return file.getInputStream();
        }

        ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream());
        ZipEntry entry;
        while ((entry = zipInputStream.getNextEntry()) != null) {
            if (!entry.isDirectory()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                zipInputStream.transferTo(out);
                return new ByteArrayInputStream(out.toByteArray());
            }
        }
        throw new IOException("ZIP file does not contain a readable entry");
    }
}

