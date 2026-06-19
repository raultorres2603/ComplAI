package cat.complai.utilities.s3;

/**
 * Interface for S3 PDF upload and pre-signed URL generation.
 *
 * <p>
 * Depend on this abstraction instead of the concrete {@link S3PdfUploader} class
 * to follow the Dependency Inversion Principle.
 * </p>
 */
public interface IS3PdfUploader {

    /**
     * Generates a pre-signed S3 GET URL for the given key.
     *
     * @param key the S3 object key
     * @return a pre-signed URL valid for 24 hours
     */
    String generatePresignedGetUrl(String key);

    /**
     * Uploads PDF bytes to the given key in the complaints bucket.
     *
     * @param key      the S3 object key
     * @param pdfBytes the PDF file content
     * @throws RuntimeException if the upload fails
     */
    void upload(String key, byte[] pdfBytes);
}
