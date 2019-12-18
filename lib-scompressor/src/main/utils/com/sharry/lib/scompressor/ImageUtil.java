package com.sharry.lib.scompressor;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

import static com.sharry.lib.scompressor.ImageUtil.ImageType.GIF;
import static com.sharry.lib.scompressor.ImageUtil.ImageType.JPEG;
import static com.sharry.lib.scompressor.ImageUtil.ImageType.PNG;
import static com.sharry.lib.scompressor.ImageUtil.ImageType.PNG_A;
import static com.sharry.lib.scompressor.ImageUtil.ImageType.UNKNOWN;

/**
 * Thanks for Glide.
 *
 * @author Sharry <a href="xiaoyu.zhu@1hai.cn">Contact me.</a>
 * @version 1.0
 * @since 2019-12-18 11:57
 */
final class ImageUtil {

    /**
     * The Image type.
     */
    enum ImageType {
        /**
         * GIF type with alpha.
         */
        GIF(true),
        /**
         * JPEG type without alpha.
         */
        JPEG(false),
        /**
         * RAW type without alpha.
         */
        RAW(false),
        /**
         * PNG type with alpha.
         */
        PNG_A(true),
        /**
         * PNG type without alpha.
         */
        PNG(false),
        /**
         * WebP type with alpha.
         */
        WEBP_A(true),
        /**
         * WebP type without alpha.
         */
        WEBP(false),
        /**
         * Unrecognized type.
         */
        UNKNOWN(false);

        private final boolean hasAlpha;

        ImageType(boolean hasAlpha) {
            this.hasAlpha = hasAlpha;
        }

        public boolean hasAlpha() {
            return hasAlpha;
        }
    }

    private static final int GIF_HEADER = 0x474946;
    private static final int PNG_HEADER = 0x89504E47;
    static final int EXIF_MAGIC_NUMBER = 0xFFD8;
    // "MM".
    private static final int MOTOROLA_TIFF_MAGIC_NUMBER = 0x4D4D;
    // "II".
    private static final int INTEL_TIFF_MAGIC_NUMBER = 0x4949;
    private static final String JPEG_EXIF_SEGMENT_PREAMBLE = "Exif\0\0";
    static final byte[] JPEG_EXIF_SEGMENT_PREAMBLE_BYTES =
            JPEG_EXIF_SEGMENT_PREAMBLE.getBytes(Charset.forName("UTF-8"));
    private static final int SEGMENT_SOS = 0xDA;
    private static final int MARKER_EOI = 0xD9;
    static final int SEGMENT_START_ID = 0xFF;
    static final int EXIF_SEGMENT_TYPE = 0xE1;
    private static final int ORIENTATION_TAG_TYPE = 0x0112;
    private static final int[] BYTES_PER_FORMAT = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};
    // WebP-related
    // "RIFF"
    private static final int RIFF_HEADER = 0x52494646;
    // "WEBP"
    private static final int WEBP_HEADER = 0x57454250;
    // "VP8" null.
    private static final int VP8_HEADER = 0x56503800;
    private static final int VP8_HEADER_MASK = 0xFFFFFF00;
    private static final int VP8_HEADER_TYPE_MASK = 0x000000FF;
    // 'X'
    private static final int VP8_HEADER_TYPE_EXTENDED = 0x00000058;
    // 'L'
    private static final int VP8_HEADER_TYPE_LOSSLESS = 0x0000004C;
    private static final int WEBP_EXTENDED_ALPHA_FLAG = 1 << 4;
    private static final int WEBP_LOSSLESS_ALPHA_FLAG = 1 << 3;

    static boolean hasAlpha(InputStream fd) {
        try {
            ImageType type = getType(new StreamReader(fd));
            return type.hasAlpha();
        } catch (IOException e) {
            return true;
        }
    }

    private static ImageType getType(Reader reader) throws IOException {
        final int firstTwoBytes = reader.getUInt16();

        // JPEG.
        if (firstTwoBytes == EXIF_MAGIC_NUMBER) {
            return JPEG;
        }

        final int firstFourBytes = (firstTwoBytes << 16 & 0xFFFF0000) | (reader.getUInt16() & 0xFFFF);
        // PNG.
        if (firstFourBytes == PNG_HEADER) {
            // See: http://stackoverflow.com/questions/2057923/how-to-check-a-png-for-grayscale-alpha
            // -color-type
            reader.skip(25 - 4);
            int alpha = reader.getByte();
            // A RGB indexed PNG can also have transparency. Better safe than sorry!
            return alpha >= 3 ? PNG_A : PNG;
        }

        // GIF from first 3 bytes.
        if (firstFourBytes >> 8 == GIF_HEADER) {
            return GIF;
        }

        // WebP (reads up to 21 bytes). See https://developers.google.com/speed/webp/docs/riff_container
        // for details.
        if (firstFourBytes != RIFF_HEADER) {
            return UNKNOWN;
        }
        // Bytes 4 - 7 contain length information. Skip these.
        reader.skip(4);
        final int thirdFourBytes =
                (reader.getUInt16() << 16 & 0xFFFF0000) | (reader.getUInt16() & 0xFFFF);
        if (thirdFourBytes != WEBP_HEADER) {
            return UNKNOWN;
        }
        final int fourthFourBytes =
                (reader.getUInt16() << 16 & 0xFFFF0000) | (reader.getUInt16() & 0xFFFF);
        if ((fourthFourBytes & VP8_HEADER_MASK) != VP8_HEADER) {
            return UNKNOWN;
        }
        if ((fourthFourBytes & VP8_HEADER_TYPE_MASK) == VP8_HEADER_TYPE_EXTENDED) {
            // Skip some more length bytes and check for transparency/alpha flag.
            reader.skip(4);
            return (reader.getByte() & WEBP_EXTENDED_ALPHA_FLAG) != 0 ? ImageType.WEBP_A : ImageType.WEBP;
        }
        if ((fourthFourBytes & VP8_HEADER_TYPE_MASK) == VP8_HEADER_TYPE_LOSSLESS) {
            // See chromium.googlesource.com/webm/libwebp/+/master/doc/webp-lossless-bitstream-spec.txt
            // for more info.
            reader.skip(4);
            return (reader.getByte() & WEBP_LOSSLESS_ALPHA_FLAG) != 0 ? ImageType.WEBP_A : ImageType.WEBP;
        }
        return ImageType.WEBP;
    }

    private interface Reader {
        int getUInt16() throws IOException;

        short getUInt8() throws IOException;

        long skip(long total) throws IOException;

        int read(byte[] buffer, int byteCount) throws IOException;

        int getByte() throws IOException;
    }

    private static final class StreamReader implements Reader {
        private final InputStream is;

        // Motorola / big endian byte order.
        StreamReader(InputStream is) {
            this.is = is;
        }

        @Override
        public int getUInt16() throws IOException {
            return (is.read() << 8 & 0xFF00) | (is.read() & 0xFF);
        }

        @Override
        public short getUInt8() throws IOException {
            return (short) (is.read() & 0xFF);
        }

        @Override
        public long skip(long total) throws IOException {
            if (total < 0) {
                return 0;
            }

            long toSkip = total;
            while (toSkip > 0) {
                long skipped = is.skip(toSkip);
                if (skipped > 0) {
                    toSkip -= skipped;
                } else {
                    // Skip has no specific contract as to what happens when you reach the end of
                    // the stream. To differentiate between temporarily not having more data and
                    // having finished the stream, we read a single byte when we fail to skip any
                    // amount of data.
                    int testEofByte = is.read();
                    if (testEofByte == -1) {
                        break;
                    } else {
                        toSkip--;
                    }
                }
            }
            return total - toSkip;
        }

        @Override
        public int read(byte[] buffer, int byteCount) throws IOException {
            int toRead = byteCount;
            int read;
            while (toRead > 0 && ((read = is.read(buffer, byteCount - toRead, toRead)) != -1)) {
                toRead -= read;
            }
            return byteCount - toRead;
        }

        @Override
        public int getByte() throws IOException {
            return is.read();
        }
    }
}