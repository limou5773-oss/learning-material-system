package com.learningmaterial.system.task;

import com.learningmaterial.system.common.ApiException;
import com.learningmaterial.system.material.FileStorage;
import com.learningmaterial.system.material.MaterialRow;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Iterator;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Only accepts a server-owned material, never user URLs or filesystem paths. */
@Component
public class OcrImagePreparation {
    static final int MAX_SIDE = 4096;
    static final long MAX_PIXELS = 12_000_000;
    static final int MAX_BYTES = 1_048_576;
    private final FileStorage storage;

    public OcrImagePreparation(FileStorage storage) {
        this.storage = storage;
    }

    public byte[] prepare(MaterialRow material, int pageNo) {
        try {
            BufferedImage image;
            if ("PDF".equals(material.getMaterialType())) image = render(material, pageNo);
            else if ("IMAGE".equals(material.getMaterialType()) && pageNo == 0)
                image = readImage(material);
            else throw new TaskExecutionException("OCR_IMAGE_INVALID");
            try {
                return encode(image);
            } finally {
                image.flush();
            }
        } catch (ApiException ex) {
            throw new TaskExecutionException("OCR_FILE_UNAVAILABLE");
        } catch (IOException ex) {
            throw new TaskExecutionException("OCR_IMAGE_INVALID");
        }
    }

    private BufferedImage readImage(MaterialRow material) throws IOException {
        try (ImageInputStream input =
                ImageIO.createImageInputStream(storage.open(material.getStorageKey()).toFile())) {
            if (input == null) throw new TaskExecutionException("OCR_IMAGE_INVALID");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new TaskExecutionException("OCR_IMAGE_INVALID");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                requireDimensions(width, height);
                int sample =
                        Math.max(
                                1,
                                (int)
                                        Math.ceil(
                                                Math.max(
                                                        Math.max(width, height) / (double) MAX_SIDE,
                                                        Math.sqrt(
                                                                (double) width
                                                                        * height
                                                                        / MAX_PIXELS))));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage image = reader.read(0, param);
                if (image == null) throw new TaskExecutionException("OCR_IMAGE_INVALID");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private BufferedImage render(MaterialRow material, int pageNo) throws IOException {
        try (PDDocument document =
                Loader.loadPDF(storage.open(material.getStorageKey()).toFile())) {
            if (document.isEncrypted()) throw new TaskExecutionException("PDF_ENCRYPTED");
            if (!document.getCurrentAccessPermission().canExtractContent())
                throw new TaskExecutionException("PDF_EXTRACTION_FORBIDDEN");
            if (pageNo < 1
                    || pageNo > document.getNumberOfPages()
                    || document.getNumberOfPages() > 100)
                throw new TaskExecutionException("INVALID_PAGE_RANGE");
            var page = document.getPage(pageNo - 1);
            var box = page.getCropBox();
            double width = box.getWidth(), height = box.getHeight();
            // Preflight before PDFRenderer allocates its raster, including malformed huge/nonfinite
            // boxes.
            if (!Double.isFinite(width)
                    || !Double.isFinite(height)
                    || width < 1
                    || height < 1
                    || width > 1_000_000
                    || height > 1_000_000) throw new TaskExecutionException("OCR_IMAGE_INVALID");
            double scale =
                    Math.min(
                            2d,
                            Math.min(
                                    MAX_SIDE / Math.max(width, height),
                                    Math.sqrt(MAX_PIXELS / (width * height))));
            if (Math.floor(Math.min(width, height) * scale) < 15)
                throw new TaskExecutionException("OCR_IMAGE_INVALID");
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            return renderer.renderImage(pageNo - 1, (float) scale, ImageType.RGB);
        } catch (InvalidPasswordException ex) {
            throw new TaskExecutionException("PDF_ENCRYPTED");
        }
    }

    private static byte[] encode(BufferedImage original) throws IOException {
        requireDimensions(original.getWidth(), original.getHeight());
        double factor =
                Math.min(
                        1d,
                        Math.min(
                                MAX_SIDE
                                        / (double)
                                                Math.max(original.getWidth(), original.getHeight()),
                                Math.sqrt(
                                        MAX_PIXELS
                                                / ((double) original.getWidth()
                                                        * original.getHeight()))));
        int width = (int) Math.floor(original.getWidth() * factor),
                height = (int) Math.floor(original.getHeight() * factor);
        for (int pass = 0; pass < 16; pass++) {
            requireDimensions(width, height);
            BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            try {
                Graphics2D g = rgb.createGraphics();
                try {
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, width, height);
                    g.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(original, 0, 0, width, height, null);
                } finally {
                    g.dispose();
                }
                for (float quality : new float[] {.85f, .65f, .45f}) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                    try (MemoryCacheImageOutputStream stream =
                            new MemoryCacheImageOutputStream(output)) {
                        writer.setOutput(stream);
                        ImageWriteParam param = writer.getDefaultWriteParam();
                        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                        param.setCompressionQuality(quality);
                        writer.write(null, new IIOImage(rgb, null, null), param);
                        stream.flush();
                    } finally {
                        writer.dispose();
                    }
                    if (output.size() <= MAX_BYTES) return output.toByteArray();
                }
            } finally {
                rgb.flush();
            }
            width = (int) Math.floor(width * .8);
            height = (int) Math.floor(height * .8);
        }
        throw new TaskExecutionException("OCR_IMAGE_TOO_LARGE");
    }

    private static void requireDimensions(int width, int height) {
        if (width < 15 || height < 15) throw new TaskExecutionException("OCR_IMAGE_INVALID");
    }
}
