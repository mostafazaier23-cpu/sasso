package com.example.ethernetcameraviewer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads independent JPEG frames from an HTTP multipart MJPEG byte stream. */
final class MjpegFrames {
    private MjpegFrames() {}

    static byte[] readFrame(InputStream in, int maximumBytes) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(32 * 1024);
        boolean inFrame = false;
        int previous = -1;
        int next;

        while ((next = in.read()) != -1) {
            if (!inFrame) {
                if (previous == 0xFF && next == 0xD8) {
                    inFrame = true;
                    frame.write(0xFF);
                    frame.write(0xD8);
                }
            } else {
                frame.write(next);
                if (previous == 0xFF && next == 0xD9) {
                    return frame.toByteArray();
                }
                if (frame.size() > maximumBytes) {
                    throw new IOException("MJPEG frame exceeds size limit");
                }
            }
            previous = next;
        }
        return null;
    }
}
