package hsts.server.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns an uploaded document into the text a bot can read.
 *
 * <p>Requirement 68 lets a teacher upload PDF or Word. A bot is given text, so a
 * file has to become text at some point; doing it when the file is <b>added</b>
 * means a document that cannot be read is refused while she is still looking at
 * the screen, instead of being stored and silently contributing nothing.</p>
 *
 * <h2>Word works properly. PDF is best-effort, and says so.</h2>
 *
 * <p>A {@code .docx} is a ZIP file with the text in {@code word/document.xml}, so
 * it can be read exactly, with nothing but {@code java.util.zip}.</p>
 *
 * <p>A PDF is not so kind. Text inside one is usually held in Flate-compressed
 * streams with its own font encodings, and reading that properly needs a library
 * such as PDFBox. This project has three dependencies and adding a fourth for one
 * requirement was judged not worth it, so what is here reads the <b>uncompressed</b>
 * text operators that simpler PDFs contain, and <b>refuses the file</b> when that
 * yields nothing useful - telling the teacher to paste the text or upload the Word
 * version instead.</p>
 *
 * <p>That limitation is deliberate and recorded rather than hidden. A PDF accepted
 * and stored as gibberish would make the bot worse while looking like it worked.</p>
 */
public final class DocumentText {

    /** Below this many characters, an extraction has not really worked. */
    private static final int MINIMUM_USEFUL = 40;

    private DocumentText() {
    }

    /** Thrown when a file cannot be turned into usable text. */
    public static class UnreadableDocumentException extends Exception {

        private static final long serialVersionUID = 1L;

        public UnreadableDocumentException(String message) {
            super(message);
        }
    }

    // -----------------------------------------------------------------
    //  Word
    // -----------------------------------------------------------------

    /**
     * Text of a {@code .docx}.
     *
     * <p>The old {@code .doc} binary format is not supported and is refused by
     * name - it is not a ZIP, and guessing at it would produce nonsense.</p>
     */
    public static String fromWord(byte[] bytes) throws UnreadableDocumentException {
        if (bytes == null || bytes.length == 0) {
            throw new UnreadableDocumentException("That file is empty.");
        }
        // Every .docx begins "PK", the ZIP signature. A .doc does not.
        if (bytes.length < 2 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw new UnreadableDocumentException(
                    "That does not look like a .docx file. The older .doc format cannot "
                  + "be read - open it in Word and save it as .docx, or paste the text in.");
        }

        String documentXml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    documentXml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
            }
        } catch (IOException e) {
            throw new UnreadableDocumentException(
                    "That Word file could not be opened: " + e.getMessage());
        }

        if (documentXml == null) {
            throw new UnreadableDocumentException(
                    "That .docx has no document text in it.");
        }

        String text = stripWordXml(documentXml);
        if (text.length() < MINIMUM_USEFUL) {
            throw new UnreadableDocumentException(
                    "There was almost no text in that Word file (" + text.length()
                  + " characters). Check it is the right document.");
        }
        return text;
    }

    /**
     * Paragraph and line breaks become newlines; every other tag is dropped.
     *
     * <p>Public, like the three below it, so the parsing can be tested directly -
     * these are pure functions and need no file, no database and no server.</p>
     */
    public static String stripWordXml(String xml) {
        String withBreaks = xml
                .replaceAll("</w:p>", "\n")
                .replaceAll("<w:br\\s*/>", "\n")
                .replaceAll("<w:tab\\s*/>", "\t");
        String plain = withBreaks.replaceAll("<[^>]+>", "");
        return unescapeXml(plain).replaceAll("[ \\t]+", " ")
                                 .replaceAll("\\n{3,}", "\n\n")
                                 .trim();
    }

    public static String unescapeXml(String text) {
        return text.replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&apos;", "'")
                   .replace("&amp;", "&");     // last, or it double-unescapes
    }

    // -----------------------------------------------------------------
    //  PDF
    // -----------------------------------------------------------------

    /**
     * Best-effort text of a PDF, or a refusal explaining what to do instead.
     *
     * <p>Reads the {@code Tj} and {@code TJ} show-text operators of any content
     * that is not compressed. Modern PDFs compress nearly everything, so this
     * succeeds on simple or older files and fails on most others - by design it
     * fails <em>loudly</em>.</p>
     */
    public static String fromPdf(byte[] bytes) throws UnreadableDocumentException {
        if (bytes == null || bytes.length == 0) {
            throw new UnreadableDocumentException("That file is empty.");
        }
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P'
                || bytes[2] != 'D' || bytes[3] != 'F') {
            throw new UnreadableDocumentException("That does not look like a PDF file.");
        }

        // ISO-8859-1 maps every byte to a character one-for-one, so binary parts
        // survive the round trip without throwing; they are simply skipped below.
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        String text = extractPdfShowText(raw);

        if (text.length() < MINIMUM_USEFUL) {
            throw new UnreadableDocumentException(
                    "The text in that PDF is compressed, and this system cannot read "
                  + "compressed PDFs. Please paste the text in as free text, or upload "
                  + "a .docx version instead.");
        }
        return text;
    }

    /** Pulls the strings out of Tj / TJ operators in uncompressed content. */
    public static String extractPdfShowText(String raw) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c != '(') {
                i++;
                continue;
            }
            // A PDF literal string: ( ... ) with backslash escapes and nesting.
            StringBuilder piece = new StringBuilder();
            int depth = 1;
            i++;
            while (i < raw.length() && depth > 0) {
                char d = raw.charAt(i);
                if (d == '\\' && i + 1 < raw.length()) {
                    char e = raw.charAt(++i);
                    switch (e) {
                        case 'n' -> piece.append('\n');
                        case 'r' -> piece.append('\r');
                        case 't' -> piece.append('\t');
                        case '(' -> piece.append('(');
                        case ')' -> piece.append(')');
                        case '\\' -> piece.append('\\');
                        default -> piece.append(e);
                    }
                } else if (d == '(') {
                    depth++;
                    piece.append(d);
                } else if (d == ')') {
                    if (--depth > 0) {
                        piece.append(d);
                    }
                } else {
                    piece.append(d);
                }
                i++;
            }
            String value = piece.toString();
            if (looksLikeWords(value)) {
                out.append(value).append(' ');
            }
        }
        return out.toString().replaceAll("[ \\t]+", " ")
                             .replaceAll("\\n{3,}", "\n\n")
                             .trim();
    }

    /**
     * True when a string is plausibly readable text rather than binary noise.
     *
     * <p>Without this, the bytes of an embedded font or image that happen to sit
     * between brackets would be pasted into the bot's knowledge as rubbish.</p>
     */
    public static boolean looksLikeWords(String value) {
        if (value.length() < 2) {
            return false;
        }
        int printable = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 32 && c < 127 || c == '\n' || c == '\t') {
                printable++;
            }
        }
        return printable * 10 >= value.length() * 9;      // at least 90% printable
    }
}
